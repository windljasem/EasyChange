package ua.easychange

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ------------------ MODELS ------------------
data class Fx(
    val base: String, 
    val quote: String, 
    val buy: Double?, 
    val sell: Double?, 
    val mid: Double
)

data class CurrencyInfo(
    val code: String,
    val flag: String,
    val name: String
)

data class CachedRates(
    val rates: List<Fx>,
    val btcPrice: Double?,
    val ethPrice: Double?,
    val timestamp: Long,
    val previousRates: List<Fx>? = null,
    val exchangers: List<KantorExchanger>? = null
)

// KANTOR моделі
data class KantorExchanger(
    val id: String,
    val name: String,
    val rates: Map<String, KantorRate>
)

data class KantorRate(
    val buy: Double?,
    val sell: Double?
)

// ------------------ API INTERFACES ------------------
interface NbuApi {
    @GET("NBUStatService/v1/statdirectory/exchange?json")
    suspend fun load(): List<NbuDto>
}

data class NbuDto(
    val r030: Int? = null,
    val txt: String? = null,
    val rate: Double? = null,
    val cc: String? = null,
    val exchangedate: String? = null
)

interface NbpApi {
    @GET("api/exchangerates/tables/a/?format=json")
    suspend fun load(): List<NbpTable>
}

data class NbpTable(
    val table: String,
    val no: String,
    val effectiveDate: String,
    val rates: List<NbpRate>
)

data class NbpRate(
    val currency: String,
    val code: String,
    val mid: Double
)

interface BinanceApi {
    @GET("api/v3/ticker/price")
    suspend fun getPrice(@Query("symbol") s: String): BinanceDto
}

data class BinanceDto(val price: String)

// ------------------ CONSTANTS ------------------
val CURRENCIES = listOf(
    CurrencyInfo("UAH", "🇺🇦", "Гривня"),
    CurrencyInfo("USD", "🇺🇸", "Долар США"),
    CurrencyInfo("EUR", "🇪🇺", "Євро"),
    CurrencyInfo("PLN", "🇵🇱", "Злотий"),
    CurrencyInfo("GBP", "🇬🇧", "Фунт"),
    CurrencyInfo("CHF", "🇨🇭", "Франк"),
    CurrencyInfo("CZK", "🇨🇿", "Крона"),
    CurrencyInfo("CNY", "🇨🇳", "Юань")
)

val KANTOR_CITIES = listOf(
    "lviv" to "Львів",
    "kiev" to "Київ",
    "odessa" to "Одеса",
    "kharkiv" to "Харків"
)

// ------------------ UTILITY FUNCTIONS ------------------
fun convert(amount: Double, from: String, to: String, rates: List<Fx>): Double? {
    if (from == to) return amount
    if (amount == 0.0) return 0.0
    
    // Пряма конвертація
    rates.firstOrNull { it.base == from && it.quote == to }?.let { 
        return amount * it.mid 
    }
    
    // Зворотна конвертація
    rates.firstOrNull { it.base == to && it.quote == from }?.let { 
        return amount / it.mid 
    }
    
    // Через UAH
    val fromUah = rates.firstOrNull { it.base == from && it.quote == "UAH" }
    val toUah = rates.firstOrNull { it.base == to && it.quote == "UAH" }
    
    if (fromUah != null && toUah != null) {
        val uahAmount = amount * fromUah.mid
        return uahAmount / toUah.mid
    }
    
    // Через PLN
    val fromPln = rates.firstOrNull { it.base == from && it.quote == "PLN" }
    val toPln = rates.firstOrNull { it.base == to && it.quote == "PLN" }
    
    if (fromPln != null && toPln != null) {
        val plnAmount = amount * fromPln.mid
        return plnAmount / toPln.mid
    }
    
    // Через USD
    val fromUsd = rates.firstOrNull { it.base == from && it.quote == "USD" }
        ?: rates.firstOrNull { it.base == "USD" && it.quote == from }
    val toUsd = rates.firstOrNull { it.base == to && it.quote == "USD" }
        ?: rates.firstOrNull { it.base == "USD" && it.quote == to }
    
    if (fromUsd != null && toUsd != null) {
        val usdAmount = if (fromUsd.base == from) {
            amount * fromUsd.mid
        } else {
            amount / fromUsd.mid
        }
        
        return if (toUsd.base == to) {
            usdAmount / toUsd.mid
        } else {
            usdAmount * toUsd.mid
        }
    }
    
    return null
}

// KANTOR API моделі (реальна структура)
data class KantorAverageResponse(
    val usd: KantorCurrencyRate?,
    val eur: KantorCurrencyRate?,
    val rur: KantorCurrencyRate?,
    val gbp: KantorCurrencyRate?,
    val chf: KantorCurrencyRate?,
    val pln: KantorCurrencyRate?,
    val czk: KantorCurrencyRate?,
    val cny: KantorCurrencyRate?
)

data class KantorCurrencyRate(
    val buy: String,  // API повертає string, а не double
    val sel: String   // "sel", не "sale"!
)

// Service API поки відкладаємо - спочатку працюємо з average

// JSON API для KANTOR (замість HTML парсингу)
suspend fun fetchKantorData(city: String): Pair<List<Fx>, List<KantorExchanger>> {
    return withContext(Dispatchers.IO) {
        try {
            Log.d("KANTOR", "=== Fetching JSON API for city: $city ===")
            val client = OkHttpClient()
            
            // Завантажуємо середні курси
            val avgRequest = Request.Builder()
                .url("https://kurstoday.com.ua/api/average/$city")
                .build()
            
            Log.d("KANTOR", "Requesting: https://kurstoday.com.ua/api/average/$city")
            val avgResponse = client.newCall(avgRequest).execute()
            Log.d("KANTOR", "Average API response code: ${avgResponse.code()}")
            
            if (!avgResponse.isSuccessful) {
                Log.e("KANTOR", "Average API error: ${avgResponse.code()}")
                return@withContext Pair(emptyList(), emptyList())
            }
            
            val avgJson = avgResponse.body()?.string()
            if (avgJson == null) {
                Log.e("KANTOR", "Average API response body is null")
                return@withContext Pair(emptyList(), emptyList())
            }
            
            Log.d("KANTOR", "Average API response: ${avgJson.take(500)}")
            
            // Парсимо JSON
            val gson = com.google.gson.Gson()
            val avgData = try {
                val parsed = gson.fromJson(avgJson, KantorAverageResponse::class.java)
                Log.d("KANTOR", "JSON parsed successfully")
                parsed
            } catch (e: Exception) {
                Log.e("KANTOR", "JSON parse error: ${e.message}")
                Log.e("KANTOR", "JSON content was: $avgJson")
                return@withContext Pair(emptyList(), emptyList())
            }
            
            // Конвертуємо в Fx
            val avgRates = mutableListOf<Fx>()
            
            // Мапа валют з відповіді
            val currencyMap = mapOf(
                "USD" to avgData.usd,
                "EUR" to avgData.eur,
                "RUR" to avgData.rur,
                "GBP" to avgData.gbp,
                "CHF" to avgData.chf,
                "PLN" to avgData.pln,
                "CZK" to avgData.czk,
                "CNY" to avgData.cny
            )
            
            currencyMap.forEach { (code, rate) ->
                if (rate != null && CURRENCIES.any { it.code == code }) {
                    // API повертає "—" для відсутніх курсів
                    val buyDouble = if (rate.buy == "—" || rate.buy == "тАФ") null else rate.buy.toDoubleOrNull()
                    val sellDouble = if (rate.sel == "—" || rate.sel == "тАФ") null else rate.sel.toDoubleOrNull()
                    
                    if (buyDouble != null && sellDouble != null) {
                        val mid = (buyDouble + sellDouble) / 2.0
                        avgRates.add(Fx(code, "UAH", buyDouble, sellDouble, mid))
                        Log.d("KANTOR", "✓ Average rate: $code = buy:$buyDouble / sell:$sellDouble / mid:$mid")
                    } else {
                        Log.d("KANTOR", "✗ Skipped $code: buy=${rate.buy}, sel=${rate.sel}")
                    }
                }
            }
            
            // TODO: Завантаження детальних курсів обмінників (поки відкладено)
            val exchangers = mutableListOf<KantorExchanger>()
            /*
            val serviceRequest = Request.Builder()
                .url("https://kurstoday.com.ua/api/service/$city")
                .build()
            
            Log.d("KANTOR", "Requesting: https://kurstoday.com.ua/api/service/$city")
            val serviceResponse = client.newCall(serviceRequest).execute()
            Log.d("KANTOR", "Service API response code: ${serviceResponse.code()}")
            
            if (serviceResponse.isSuccessful) {
                val serviceJson = serviceResponse.body()?.string()
                if (serviceJson != null) {
                    Log.d("KANTOR", "Service API response: ${serviceJson.take(500)}")
                    // TODO: Парсинг service API
                }
            }
            */
            
            Log.d("KANTOR", "=== Parse complete ===")
            Log.d("KANTOR", "Total: ${avgRates.size} avg rates, ${exchangers.size} exchangers")
            
            Pair(avgRates, exchangers)
            
        } catch (e: Exception) {
            Log.e("KANTOR", "Error loading KANTOR: ${e.message}", e)
            Pair(emptyList(), emptyList())
        }
    }
}

// ------------------ PERSISTENT CACHE HELPERS ------------------
fun savePreviousRates(context: Context, cacheKey: String, rates: List<Fx>) {
    try {
        val prefs = context.getSharedPreferences("EasyChangeCache", Context.MODE_PRIVATE)
        val gson = Gson()
        val json = gson.toJson(rates)
        prefs.edit().putString("prev_$cacheKey", json).apply()
        Log.d("Cache", "Saved previous rates for $cacheKey: ${rates.size} items")
    } catch (e: Exception) {
        Log.e("Cache", "Error saving previous rates: ${e.message}")
    }
}

fun loadPreviousRates(context: Context, cacheKey: String): List<Fx>? {
    return try {
        val prefs = context.getSharedPreferences("EasyChangeCache", Context.MODE_PRIVATE)
        val json = prefs.getString("prev_$cacheKey", null) ?: return null
        val gson = Gson()
        val type = object : TypeToken<List<Fx>>() {}.type
        val rates: List<Fx> = gson.fromJson(json, type)
        Log.d("Cache", "Loaded previous rates for $cacheKey: ${rates.size} items")
        rates
    } catch (e: Exception) {
        Log.e("Cache", "Error loading previous rates: ${e.message}")
        null
    }
}

// ------------------ MAIN ACTIVITY ------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nbu = Retrofit.Builder()
            .baseUrl("https://bank.gov.ua/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NbuApi::class.java)

        val nbp = Retrofit.Builder()
            .baseUrl("https://api.nbp.pl/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NbpApi::class.java)

        val binance = Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BinanceApi::class.java)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(nbu, nbp, binance)
                }
            }
        }
    }
}

// ------------------ MAIN SCREEN ------------------
@Composable
fun MainScreen(
    nbu: NbuApi,
    nbp: NbpApi,
    binance: BinanceApi
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("EasyChangePrefs", Context.MODE_PRIVATE) }
    
    var source by remember { mutableStateOf("NBU") }
    var kantorCity by remember { mutableStateOf("lviv") }
    var baseCurrency by remember { 
        mutableStateOf(prefs.getString("last_currency", "USD") ?: "USD") 
    }
    var amount by remember { mutableStateOf("1") }
    var rates by remember { mutableStateOf<List<Fx>>(emptyList()) }
    var exchangers by remember { mutableStateOf<List<KantorExchanger>>(emptyList()) }
    var expandedCurrency by remember { mutableStateOf<String?>(null) }
    var btcPrice by remember { mutableStateOf<Double?>(null) }
    var ethPrice by remember { mutableStateOf<Double?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var lastUpdate by remember { mutableStateOf<String?>(null) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var showCityPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Кеш для кожного джерела (тільки в пам'яті, для швидкого доступу протягом сесії)
    val cache = remember { mutableMapOf<String, CachedRates>() }
    
    // Зберігаємо вибрану валюту
    fun saveCurrency(currency: String) {
        prefs.edit().putString("last_currency", currency).apply()
        baseCurrency = currency
    }

    fun refresh() {
        val currentTime = System.currentTimeMillis()
        val cacheKey = if (source == "KANTOR") "$source-$kantorCity" else source
        
        // Перевіряємо кеш у пам'яті (60 секунд)
        cache[cacheKey]?.let { cached ->
            if (currentTime - cached.timestamp < 60000) {
                // Дані свіжі - беремо з кешу
                rates = cached.rates
                btcPrice = cached.btcPrice
                ethPrice = cached.ethPrice
                exchangers = cached.exchangers ?: emptyList()
                val seconds = ((currentTime - cached.timestamp) / 1000).toInt()
                lastUpdate = "Кеш (${seconds}с тому)"
                Log.d("EasyChange", "Using memory cache for $cacheKey (${seconds}s old)")
                return
            }
        }
        
        scope.launch {
            isLoading = true
            
            withContext(Dispatchers.IO) {
                try {
                    Log.d("EasyChange", "Loading from: $source")
                    
                    var newRates: List<Fx>
                    var newExchangers: List<KantorExchanger>
                    
                    when (source) {
                        "NBU" -> {
                            try {
                                val response = nbu.load()
                                Log.d("EasyChange", "NBU: ${response.size} items")
                                
                                newRates = response
                                    .filter { it.cc != null && it.rate != null && it.rate > 0 }
                                    .map { Fx(it.cc!!, "UAH", null, null, it.rate!!) }
                                newExchangers = emptyList()
                            } catch (e: Exception) {
                                Log.e("EasyChange", "NBU error: ${e.message}", e)
                                newRates = cache[cacheKey]?.rates ?: emptyList()
                                newExchangers = emptyList()
                            }
                        }

                        "NBP" -> {
                            try {
                                val response = nbp.load()
                                Log.d("EasyChange", "NBP: ${response.size} tables")
                                
                                if (response.isNotEmpty()) {
                                    newRates = response[0].rates.map { rate ->
                                        Fx(rate.code, "PLN", null, null, rate.mid)
                                    }
                                    newExchangers = emptyList()
                                } else {
                                    newRates = cache[cacheKey]?.rates ?: emptyList()
                                    newExchangers = emptyList()
                                }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "NBP error: ${e.message}", e)
                                newRates = cache[cacheKey]?.rates ?: emptyList()
                                newExchangers = emptyList()
                            }
                        }

                        "KANTOR" -> {
                            try {
                                val (avgRates, exch) = fetchKantorData(kantorCity)
                                newRates = avgRates
                                newExchangers = exch
                            } catch (e: Exception) {
                                Log.e("EasyChange", "KANTOR error: ${e.message}", e)
                                newRates = cache[cacheKey]?.rates ?: emptyList()
                                newExchangers = cache[cacheKey]?.exchangers ?: emptyList()
                            }
                        }

                        else -> {
                            newRates = cache[cacheKey]?.rates ?: emptyList()
                            newExchangers = emptyList()
                        }
                    }

                    // Додаємо BTC та ETH
                    var newBtc: Double? = null
                    var newEth: Double? = null
                    
                    try {
                        val btcResponse = binance.getPrice("BTCUSDT")
                        newBtc = btcResponse.price.toDoubleOrNull()
                        Log.d("EasyChange", "BTC: $newBtc USD")
                    } catch (e: Exception) {
                        Log.e("EasyChange", "BTC error: ${e.message}")
                        newBtc = cache[cacheKey]?.btcPrice
                    }
                    
                    try {
                        val ethResponse = binance.getPrice("ETHUSDT")
                        newEth = ethResponse.price.toDoubleOrNull()
                        Log.d("EasyChange", "ETH: $newEth USD")
                    } catch (e: Exception) {
                        Log.e("EasyChange", "ETH error: ${e.message}")
                        newEth = cache[cacheKey]?.ethPrice
                    }

                    // Зберігаємо в кеш
                    if (newRates.isNotEmpty()) {
                        // Завантажуємо попередні дані з SharedPreferences
                        val previousRates = loadPreviousRates(context, cacheKey)
                        
                        // Зберігаємо нові дані як попередні (для наступного разу)
                        savePreviousRates(context, cacheKey, newRates)
                        
                        // Оновлюємо кеш у пам'яті
                        cache[cacheKey] = CachedRates(newRates, newBtc, newEth, currentTime, previousRates, newExchangers)
                        
                        rates = newRates
                        btcPrice = newBtc
                        ethPrice = newEth
                        exchangers = newExchangers
                        
                        val format = SimpleDateFormat("dd.MM.yyyy 'о' HH:mm", Locale("uk"))
                        lastUpdate = "Оновлено ${format.format(Date())}"
                        
                        Log.d("Cache", "Updated cache for $cacheKey with previousRates: ${previousRates?.size ?: 0}")
                    } else if (cache[cacheKey] != null) {
                        // Якщо не вдалося завантажити - використовуємо старий кеш
                        val cached = cache[cacheKey]!!
                        rates = cached.rates
                        btcPrice = cached.btcPrice
                        ethPrice = cached.ethPrice
                        exchangers = cached.exchangers ?: emptyList()
                        
                        val format = SimpleDateFormat("dd.MM.yyyy 'о' HH:mm", Locale("uk"))
                        lastUpdate = "Останнє оновлення: ${format.format(Date(cached.timestamp))}"
                    }

                } catch (e: Exception) {
                    Log.e("EasyChange", "Error: ${e.message}", e)
                    
                    cache[cacheKey]?.let {
                        rates = it.rates
                        btcPrice = it.btcPrice
                        ethPrice = it.ethPrice
                        exchangers = it.exchangers ?: emptyList()
                        
                        val format = SimpleDateFormat("dd.MM.yyyy 'о' HH:mm", Locale("uk"))
                        lastUpdate = "Останнє оновлення: ${format.format(Date(it.timestamp))}"
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(source, kantorCity) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Верхня частина - не скролиться
        Column(modifier = Modifier.padding(16.dp)) {
            // Кнопки джерел
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Верхній ряд - NBU і NBP
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { source = "NBU" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "NBU") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("NBU", fontSize = 13.sp)
                            Text("bank.gov.ua", fontSize = 8.sp)
                        }
                    }
                    
                    Button(
                        onClick = { source = "NBP" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "NBP") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("NBP", fontSize = 13.sp)
                            Text("nbp.pl", fontSize = 8.sp)
                        }
                    }
                }
                
                // Нижній ряд - KANTOR з кнопкою міста
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { source = "KANTOR" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "KANTOR") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("KANTOR", fontSize = 13.sp)
                            Text("kurstoday.com.ua", fontSize = 8.sp)
                        }
                    }
                    
                    if (source == "KANTOR") {
                        Button(
                            onClick = { showCityPicker = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            Text(
                                KANTOR_CITIES.find { it.first == kantorCity }?.second ?: kantorCity,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Час оновлення
            lastUpdate?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }

            // Кроскурс USD/EUR (ОДИН РЯДОК)
            if (rates.isNotEmpty()) {
                val usdToEur = convert(1.0, "USD", "EUR", rates)
                val eurToUsd = convert(1.0, "EUR", "USD", rates)
                
                if (usdToEur != null || eurToUsd != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Кроскурс",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (usdToEur != null) {
                                    Text(
                                        "1 USD = ${String.format(Locale.US, "%.4f", usdToEur)} EUR",
                                        fontSize = 10.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                    )
                                }
                                if (eurToUsd != null) {
                                    Text(
                                        "1 EUR = ${String.format(Locale.US, "%.4f", eurToUsd)} USD",
                                        fontSize = 10.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Поле введення з прапором зліва і кнопкою оновлення справа
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { 
                        if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) {
                            amount = it
                        }
                    },
                    label = { Text(baseCurrency) },
                    leadingIcon = {
                        val curr = CURRENCIES.find { it.code == baseCurrency }
                        IconButton(onClick = { showCurrencyPicker = true }) {
                            Text(curr?.flag ?: "🏳", fontSize = 24.sp)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isLoading
                )
                
                Button(
                    onClick = { refresh() },
                    modifier = Modifier.height(56.dp),
                    enabled = !isLoading,
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Text("⟳", fontSize = 20.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Завантаження
            if (isLoading) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Завантаження...", fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // Список валют - скролиться
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            val amountDouble = amount.toDoubleOrNull() ?: 0.0

            if (rates.isNotEmpty()) {
                Log.d("UI", "=== Displaying rates ===")
                Log.d("UI", "Source: $source")
                Log.d("UI", "Total rates: ${rates.size}")
                Log.d("UI", "Base currency: $baseCurrency")
                Log.d("UI", "Amount: $amountDouble")
                
                rates.take(3).forEach { r ->
                    Log.d("UI", "Rate sample: ${r.base}/${r.quote} = mid:${r.mid}, buy:${r.buy}, sell:${r.sell}")
                }
                
                CURRENCIES.filter { it.code != baseCurrency }.forEach { curr ->
                    val value = convert(amountDouble, baseCurrency, curr.code, rates)
                    
                    if (source == "KANTOR") {
                        val fx = rates.firstOrNull { it.base == curr.code && it.quote == "UAH" }
                        Log.d("UI", "KANTOR ${curr.code}: fx=${fx != null}, buy=${fx?.buy}, sell=${fx?.sell}, value=$value")
                    }
                    
                    // Отримуємо попередню ціну для порівняння
                    val cacheKey = if (source == "KANTOR") "$source-$kantorCity" else source
                    val previousRates = cache[cacheKey]?.previousRates
                    val previousValue = if (previousRates != null && amountDouble > 0) {
                        convert(amountDouble, baseCurrency, curr.code, previousRates)
                    } else null
                    
                    // Обчислюємо зміну
                    val diff = if (value != null && previousValue != null) value - previousValue else null
                    val trend = if (diff != null) {
                        when {
                            diff > 0.01 -> "🔺"
                            diff < -0.01 -> "🔻"
                            else -> "🔷"
                        }
                    } else null
                    
                    val trendColor = when (trend) {
                        "🔺" -> androidx.compose.ui.graphics.Color(0xFFE53935) // червоний (дорожче)
                        "🔻" -> androidx.compose.ui.graphics.Color(0xFF43A047) // зелений (дешевше)
                        "🔷" -> androidx.compose.ui.graphics.Color(0xFF1E88E5) // синій (без змін)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable(enabled = source == "KANTOR" && exchangers.isNotEmpty()) {
                                expandedCurrency = if (expandedCurrency == curr.code) null else curr.code
                            }
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Text(
                                    "${curr.flag} ${curr.code}",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    if (source == "KANTOR") {
                                        // Для KANTOR показуємо buy/sell
                                        val fx = rates.firstOrNull { it.base == curr.code && it.quote == "UAH" }
                                        if (fx?.buy != null && fx.sell != null) {
                                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                                Text(
                                                    "К: ${String.format(Locale.US, "%.2f", fx.buy)}",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                                )
                                                Text(
                                                    "П: ${String.format(Locale.US, "%.2f", fx.sell)}",
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                                )
                                            }
                                        } else {
                                            Text(
                                                "НЕ ВИЗНАЧЕНО",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
                                        }
                                    } else {
                                        // Для NBU/NBP показуємо конвертовану суму
                                        Text(
                                            if (value != null) {
                                                String.format(Locale.US, "%.2f", value)
                                            } else {
                                                "НЕ ВИЗНАЧЕНО"
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (value != null) 
                                                MaterialTheme.colorScheme.onSurface 
                                            else 
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = if (value != null) 16.sp else 12.sp
                                        )
                                    }
                                    if (trend != null) {
                                        Text(
                                            trend,
                                            fontSize = 16.sp,
                                            color = trendColor
                                        )
                                    }
                                }
                            }
                            
                            // Розгортання обмінників для KANTOR
                            if (source == "KANTOR" && expandedCurrency == curr.code && exchangers.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
                                ) {
                                    HorizontalDivider(thickness = 1.dp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Обмінники:",
                                        fontSize = 12.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    
                                    exchangers.forEach { exchanger ->
                                        val rate = exchanger.rates[curr.code]
                                        if (rate != null && (rate.buy != null || rate.sell != null)) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    exchanger.name,
                                                    fontSize = 11.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    "К: ${rate.buy?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} / " +
                                                    "П: ${rate.sell?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}",
                                                    fontSize = 11.sp,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (!isLoading) {
                Text("Дані не завантажено", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(12.dp))

            // Криптовалюти BTC та ETH
            if (btcPrice != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "₿ BTC",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            String.format(Locale.US, "%.2f", btcPrice) + " USD",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            if (ethPrice != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Ξ ETH",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            String.format(Locale.US, "%.2f", ethPrice) + " USD",
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // Діалог вибору валюти
    if (showCurrencyPicker) {
        AlertDialog(
            onDismissRequest = { showCurrencyPicker = false },
            title = { Text("Оберіть базову валюту") },
            text = {
                Column {
                    CURRENCIES.forEach { curr ->
                        TextButton(
                            onClick = {
                                saveCurrency(curr.code)
                                showCurrencyPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "${curr.flag} ${curr.code} - ${curr.name}",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCurrencyPicker = false }) {
                    Text("Закрити")
                }
            }
        )
    }

    // Діалог вибору міста для KANTOR
    if (showCityPicker) {
        AlertDialog(
            onDismissRequest = { showCityPicker = false },
            title = { Text("Оберіть місто") },
            text = {
                Column {
                    KANTOR_CITIES.forEach { (cityCode, cityName) ->
                        TextButton(
                            onClick = {
                                kantorCity = cityCode
                                showCityPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                cityName,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCityPicker = false }) {
                    Text("Закрити")
                }
            }
        )
    }
}
