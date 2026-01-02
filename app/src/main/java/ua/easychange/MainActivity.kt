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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    CurrencyInfo("CAD", "🇨🇦", "Дол. Канади"),
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

// HTML Парсинг для KANTOR
suspend fun parseKantorData(city: String): Pair<List<Fx>, List<KantorExchanger>> {
    return withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url("https://kurstoday.com.ua/$city")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("KANTOR", "HTTP error: ${response.code}")
                return@withContext Pair(emptyList(), emptyList())
            }
            
            val html = response.body?.string() ?: return@withContext Pair(emptyList(), emptyList())
            
            // Парсимо середні курси (верхня таблиця)
            val avgRates = mutableListOf<Fx>()
            
            // Regex для верхньої таблиці: <td>USD</td>...<td>42.13</td><td>42.48</td>
            val tablePattern = """<td[^>]*>\s*<img[^>]*>\s*([A-Z]{3})</td>.*?<td[^>]*>([0-9.]+)</td>\s*<td[^>]*>([0-9.]+)</td>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            tablePattern.findAll(html).forEach { match ->
                try {
                    val code = match.groupValues[1]
                    val buy = match.groupValues[2].toDoubleOrNull()
                    val sell = match.groupValues[3].toDoubleOrNull()
                    
                    if (buy != null && sell != null) {
                        val mid = (buy + sell) / 2.0
                        avgRates.add(Fx(code, "UAH", buy, sell, mid))
                        Log.d("KANTOR", "Parsed avg rate: $code = $buy/$sell")
                    }
                } catch (e: Exception) {
                    Log.w("KANTOR", "Error parsing avg rate: ${e.message}")
                }
            }
            
            // Парсимо обмінники
            val exchangers = mutableListOf<KantorExchanger>()
            
            // Знаходимо всі блоки обмінників: <div id="#48">...</div>
            val exchangerBlockPattern = """<div[^>]*id\s*=\s*["']#(\d+)["'][^>]*>(.*?)</div>\s*</div>\s*</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
            
            exchangerBlockPattern.findAll(html).forEach { blockMatch ->
                try {
                    val id = blockMatch.groupValues[1]
                    val block = blockMatch.groupValues[2]
                    
                    // Назва обмінника
                    val namePattern = """<h3[^>]*>(.*?)</h3>""".toRegex()
                    val nameMatch = namePattern.find(block)
                    var name = nameMatch?.groupValues?.get(1)?.trim() ?: ""
                    
                    // Видаляємо HTML теги з назви
                    name = name.replace("""<[^>]*>""".toRegex(), "").trim()
                    
                    if (name.isEmpty()) {
                        // Спробуємо альтернативний патерн
                        val altNamePattern = """class\s*=\s*["']exchanger-name["'][^>]*>(.*?)<""".toRegex()
                        name = altNamePattern.find(block)?.groupValues?.get(1)?.replace("""<[^>]*>""".toRegex(), "")?.trim() ?: "Обмінник #$id"
                    }
                    
                    // Парсимо курси обмінника
                    val ratesMap = mutableMapOf<String, KantorRate>()
                    
                    // Шукаємо рядки таблиці з курсами: <td>USD</td><td>41.80</td><td>42.40</td>
                    val rateRowPattern = """<td[^>]*>\s*<img[^>]*>\s*([A-Z]{3})</td>\s*<td[^>]*>([0-9.—\s]+)</td>\s*<td[^>]*>([0-9.—\s]+)</td>""".toRegex()
                    
                    rateRowPattern.findAll(block).forEach { rateMatch ->
                        val currCode = rateMatch.groupValues[1]
                        val buyText = rateMatch.groupValues[2].trim()
                        val sellText = rateMatch.groupValues[3].trim()
                        
                        val buy = if (buyText == "—" || buyText.isEmpty()) null else buyText.toDoubleOrNull()
                        val sell = if (sellText == "—" || sellText.isEmpty()) null else sellText.toDoubleOrNull()
                        
                        ratesMap[currCode] = KantorRate(buy, sell)
                    }
                    
                    if (ratesMap.isNotEmpty()) {
                        exchangers.add(KantorExchanger(id, name, ratesMap))
                        Log.d("KANTOR", "Parsed exchanger: #$id - $name with ${ratesMap.size} rates")
                    }
                    
                } catch (e: Exception) {
                    Log.w("KANTOR", "Error parsing exchanger block: ${e.message}")
                }
            }
            
            Log.d("KANTOR", "Total: ${avgRates.size} avg rates, ${exchangers.size} exchangers")
            Pair(avgRates, exchangers)
            
        } catch (e: Exception) {
            Log.e("KANTOR", "Error loading KANTOR data: ${e.message}", e)
            Pair(emptyList(), emptyList())
        }
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
    
    // Кеш для кожного джерела
    val cache = remember { mutableMapOf<String, CachedRates>() }
    
    fun saveCurrency(currency: String) {
        prefs.edit().putString("last_currency", currency).apply()
        baseCurrency = currency
    }

    fun refresh() {
        val currentTime = System.currentTimeMillis()
        val cacheKey = if (source == "KANTOR") "$source-$kantorCity" else source
        
        // Перевіряємо кеш (60 секунд)
        cache[cacheKey]?.let { cached ->
            if (currentTime - cached.timestamp < 60000) {
                rates = cached.rates
                btcPrice = cached.btcPrice
                ethPrice = cached.ethPrice
                exchangers = cached.exchangers ?: emptyList()
                val seconds = ((currentTime - cached.timestamp) / 1000).toInt()
                Log.d("EasyChange", "Using cache for $cacheKey (${seconds}s old)")
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
                                val (avgRates, exch) = parseKantorData(kantorCity)
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

                    // BTC та ETH
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
                        val previousRates = cache[cacheKey]?.rates
                        cache[cacheKey] = CachedRates(newRates, newBtc, newEth, currentTime, previousRates, newExchangers)
                        rates = newRates
                        btcPrice = newBtc
                        ethPrice = newEth
                        exchangers = newExchangers
                        
                        val format = SimpleDateFormat("dd.MM.yyyy 'о' HH:mm", Locale("uk"))
                        lastUpdate = "Курс оновлено ${format.format(Date())}"
                    } else if (cache[cacheKey] != null) {
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
        // Верхня частина
        Column(modifier = Modifier.padding(16.dp)) {
            // Кнопки джерел
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            modifier = Modifier.weight(0.6f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
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

            lastUpdate?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }

            // Кроскурс (один рядок)
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

            // Поле введення
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

            if (isLoading) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Завантаження...", fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // Список валют
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            val amountDouble = amount.toDoubleOrNull() ?: 0.0

            if (rates.isNotEmpty()) {
                CURRENCIES.filter { it.code != baseCurrency }.forEach { curr ->
                    val value = convert(amountDouble, baseCurrency, curr.code, rates)
                    
                    // Тренди
                    val cacheKey = if (source == "KANTOR") "$source-$kantorCity" else source
                    val previousRates = cache[cacheKey]?.previousRates
                    val previousValue = if (previousRates != null && amountDouble > 0) {
                        convert(amountDouble, baseCurrency, curr.code, previousRates)
                    } else null
                    
                    val diff = if (value != null && previousValue != null) value - previousValue else null
                    val trend = if (diff != null) {
                        when {
                            diff > 0.01 -> "🔺"
                            diff < -0.01 -> "🔻"
                            else -> "🔷"
                        }
                    } else null
                    
                    val trendColor = when (trend) {
                        "🔺" -> androidx.compose.ui.graphics.Color(0xFFE53935)
                        "🔻" -> androidx.compose.ui.graphics.Color(0xFF43A047)
                        "🔷" -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable {
                                if (source == "KANTOR" && exchangers.isNotEmpty()) {
                                    expandedCurrency = if (expandedCurrency == curr.code) null else curr.code
                                }
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
                                    // ДЛЯ KANTOR - показуємо BUY/SELL
                                    if (source == "KANTOR") {
                                        val rate = rates.firstOrNull { it.base == curr.code }
                                        if (rate?.buy != null && rate.sell != null) {
                                            Text(
                                                "${String.format(Locale.US, "%.2f", rate.buy)} / ${String.format(Locale.US, "%.2f", rate.sell)}",
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontSize = 14.sp
                                            )
                                        } else {
                                            Text("—", fontSize = 14.sp)
                                        }
                                    } else {
                                        // Для NBU, NBP - тільки значення
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
                                    
                                    if (source == "KANTOR" && exchangers.isNotEmpty()) {
                                        Text(
                                            if (expandedCurrency == curr.code) "▲" else "▼",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            
                            // Розгорнутий список обмінників
                            if (source == "KANTOR" && expandedCurrency == curr.code) {
                                Divider()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        "📍 Обмінники ${KANTOR_CITIES.find { it.first == kantorCity }?.second}:",
                                        fontSize = 11.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    
                                    exchangers.forEach { exchanger ->
                                        val rate = exchanger.rates[curr.code]
                                        if (rate?.buy != null || rate?.sell != null) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    exchanger.name,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    "${rate?.buy?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} / ${rate?.sell?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}",
                                                    fontSize = 12.sp,
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

            // Криптовалюти
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
                        Text("₿ BTC", style = MaterialTheme.typography.titleMedium)
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
                        Text("Ξ ETH", style = MaterialTheme.typography.titleMedium)
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
    
    // Діалог вибору міста
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
                            Text(cityName, modifier = Modifier.fillMaxWidth())
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
