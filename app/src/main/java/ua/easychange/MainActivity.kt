package ua.easychange

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
    val previousRates: List<Fx>? = null  // для порівняння
)

// ------------------ API INTERFACES ------------------
interface MonoApi {
    @GET("bank/currency")
    suspend fun load(): List<MonoDto>
}

data class MonoDto(
    val currencyCodeA: Int,
    val currencyCodeB: Int,
    val rateBuy: Double? = null,
    val rateSell: Double? = null,
    val rateCross: Double? = null
)

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

interface KursApi {
    @GET("api/currency/interbank")
    suspend fun load(): KursInterbankResponse
}

data class KursInterbankResponse(
    val data: List<KursRate>
)

data class KursRate(
    val currency: String,
    val code: String,
    val buy: Double,
    val sell: Double
)

interface ExchangeRateApi {
    @GET("v6/latest/USD")
    suspend fun load(): ExchangeRateResponse
}

data class ExchangeRateResponse(
    val base_code: String,
    val rates: Map<String, Double>
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

// ------------------ UTILITY FUNCTIONS ------------------
fun MonoDto.code(i: Int) = when (i) {
    840 -> "USD"
    978 -> "EUR"
    985 -> "PLN"
    980 -> "UAH"
    826 -> "GBP"
    756 -> "CHF"
    203 -> "CZK"
    124 -> "CAD"
    156 -> "CNY"
    else -> null
}

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

// ------------------ MAIN ACTIVITY ------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val kurs = Retrofit.Builder()
            .baseUrl("https://kurs.com.ua/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KursApi::class.java)

        val mono = Retrofit.Builder()
            .baseUrl("https://api.monobank.ua/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MonoApi::class.java)

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

        val exchangeRate = Retrofit.Builder()
            .baseUrl("https://open.exchangerate-api.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ExchangeRateApi::class.java)

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
                    MainScreen(kurs, mono, nbu, nbp, exchangeRate, binance)
                }
            }
        }
    }
}

// ------------------ MAIN SCREEN ------------------
@Composable
fun MainScreen(
    kurs: KursApi,
    mono: MonoApi,
    nbu: NbuApi,
    nbp: NbpApi,
    exchangeRate: ExchangeRateApi,
    binance: BinanceApi
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("EasyChangePrefs", Context.MODE_PRIVATE) }
    
    var source by remember { mutableStateOf("MONO") }
    var baseCurrency by remember { 
        mutableStateOf(prefs.getString("last_currency", "USD") ?: "USD") 
    }
    var amount by remember { mutableStateOf("1") }
    var rates by remember { mutableStateOf<List<Fx>>(emptyList()) }
    var btcPrice by remember { mutableStateOf<Double?>(null) }
    var ethPrice by remember { mutableStateOf<Double?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var lastUpdate by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    var cacheInfo by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    
    // Кеш для кожного джерела (60 секунд)
    val cache = remember { mutableMapOf<String, CachedRates>() }
    
    // Зберігаємо вибрану валюту
    fun saveCurrency(currency: String) {
        prefs.edit().putString("last_currency", currency).apply()
        baseCurrency = currency
    }

    fun refresh() {
        val currentTime = System.currentTimeMillis()
        
        // Перевіряємо кеш (60 секунд)
        cache[source]?.let { cached ->
            if (currentTime - cached.timestamp < 60000) {
                // Дані свіжі - беремо з кешу
                rates = cached.rates
                btcPrice = cached.btcPrice
                ethPrice = cached.ethPrice
                val seconds = ((currentTime - cached.timestamp) / 1000).toInt()
                cacheInfo = "Дані з кешу ($seconds сек. тому)"
                Log.d("EasyChange", "Using cache for $source (${seconds}s old)")
                return
            }
        }
        
        scope.launch {
            isLoading = true
            errorMessage = null
            cacheInfo = null
            
            withContext(Dispatchers.IO) {
                try {
                    Log.d("EasyChange", "Loading from: $source")
                    
                    val newRates = when (source) {
                        "KURS" -> {
                            try {
                                val response = kurs.load()
                                Log.d("EasyChange", "KURS: ${response.data.size} rates")
                                
                                response.data.map { rate ->
                                    Fx(rate.code, "UAH", rate.buy, rate.sell, (rate.buy + rate.sell) / 2)
                                }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "KURS error: ${e.message}", e)
                                errorMessage = "KURS: ${e.message}"
                                // Повертаємо старий кеш якщо є
                                cache[source]?.rates ?: emptyList()
                            }
                        }

                        "MONO" -> {
                            try {
                                val response = mono.load()
                                Log.d("EasyChange", "MONO: ${response.size} items")
                                
                                response.mapNotNull {
                                    val base = it.code(it.currencyCodeA)
                                    val quote = it.code(it.currencyCodeB)
                                    
                                    if (base != null && quote == "UAH") {
                                        val mid = it.rateCross 
                                            ?: ((it.rateBuy ?: 0.0) + (it.rateSell ?: 0.0)) / 2
                                        
                                        if (mid > 0) {
                                            Fx(base, quote, it.rateBuy, it.rateSell, mid)
                                        } else null
                                    } else null
                                }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "MONO error: ${e.message}", e)
                                errorMessage = "MONO: ${e.message}"
                                // Повертаємо старий кеш якщо є
                                cache[source]?.rates ?: emptyList()
                            }
                        }

                        "NBU" -> {
                            try {
                                val response = nbu.load()
                                Log.d("EasyChange", "NBU: ${response.size} items")
                                
                                response
                                    .filter { it.cc != null && it.rate != null && it.rate > 0 }
                                    .map { Fx(it.cc!!, "UAH", null, null, it.rate!!) }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "NBU error: ${e.message}", e)
                                errorMessage = "NBU: ${e.message}"
                                cache[source]?.rates ?: emptyList()
                            }
                        }

                        "NBP" -> {
                            try {
                                val response = nbp.load()
                                Log.d("EasyChange", "NBP: ${response.size} tables")
                                
                                if (response.isNotEmpty()) {
                                    response[0].rates.map { rate ->
                                        Fx(rate.code, "PLN", null, null, rate.mid)
                                    }
                                } else {
                                    errorMessage = "NBP: порожня відповідь"
                                    cache[source]?.rates ?: emptyList()
                                }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "NBP error: ${e.message}", e)
                                errorMessage = "NBP: ${e.message}"
                                cache[source]?.rates ?: emptyList()
                            }
                        }

                        else -> cache[source]?.rates ?: emptyList()
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
                        newBtc = cache[source]?.btcPrice
                    }
                    
                    try {
                        val ethResponse = binance.getPrice("ETHUSDT")
                        newEth = ethResponse.price.toDoubleOrNull()
                        Log.d("EasyChange", "ETH: $newEth USD")
                    } catch (e: Exception) {
                        Log.e("EasyChange", "ETH error: ${e.message}")
                        newEth = cache[source]?.ethPrice
                    }

                    // Зберігаємо в кеш
                    if (newRates.isNotEmpty()) {
                        // Зберігаємо попередні курси для порівняння
                        val previousRates = cache[source]?.rates
                        cache[source] = CachedRates(newRates, newBtc, newEth, currentTime, previousRates)
                        rates = newRates
                        btcPrice = newBtc
                        ethPrice = newEth
                        
                        val format = SimpleDateFormat("dd.MM.yyyy 'о' HH:mm", Locale("uk"))
                        lastUpdate = "Курс оновлено ${format.format(Date())}"
                    } else if (cache[source] != null) {
                        // Якщо не вдалось завантажити, але є кеш - використовуємо його
                        val cached = cache[source]!!
                        rates = cached.rates
                        btcPrice = cached.btcPrice
                        ethPrice = cached.ethPrice
                        errorMessage = (errorMessage ?: "") + " (показано з кешу)"
                    }

                } catch (e: Exception) {
                    Log.e("EasyChange", "Error: ${e.message}", e)
                    errorMessage = "Помилка: ${e.message}"
                    
                    // Завжди намагаємось показати хоч щось з кешу
                    cache[source]?.let {
                        rates = it.rates
                        btcPrice = it.btcPrice
                        ethPrice = it.ethPrice
                        errorMessage = (errorMessage ?: "") + " (показано з кешу)"
                    }
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(source) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Верхня частина - не скролиться
        Column(modifier = Modifier.padding(16.dp)) {
            // Кнопки джерел - зменшена висота
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { source = "KURS" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "KURS") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("KURS", fontSize = 13.sp)
                            Text("kurs.com.ua", fontSize = 8.sp)
                        }
                    }
                    
                    Button(
                        onClick = { source = "MONO" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "MONO") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("MONO", fontSize = 13.sp)
                            Text("monobank.ua", fontSize = 8.sp)
                        }
                    }
                }
                
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
            }

            Spacer(Modifier.height(12.dp))

            // Час оновлення
            lastUpdate?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }
            
            // Інфо про кеш
            cacheInfo?.let {
                Text(
                    it, 
                    fontSize = 10.sp, 
                    color = MaterialTheme.colorScheme.secondary,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
                Spacer(Modifier.height(8.dp))
            }

            // Кроскурс USD/EUR над полем введення
            if (rates.isNotEmpty()) {
                val usdToEur = convert(1.0, "USD", "EUR", rates)
                val eurToUsd = convert(1.0, "EUR", "USD", rates)
                
                // Отримуємо попередні значення для тенденцій
                val prevRates = cache[source]?.previousRates
                val prevUsdToEur = if (prevRates != null) convert(1.0, "USD", "EUR", prevRates) else null
                val prevEurToUsd = if (prevRates != null) convert(1.0, "EUR", "USD", prevRates) else null
                
                if (usdToEur != null || eurToUsd != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp)
                        ) {
                            if (usdToEur != null) {
                                val diff = if (prevUsdToEur != null) usdToEur - prevUsdToEur else 0.0
                                val trend = when {
                                    diff > 0.0001 -> "🔺"
                                    diff < -0.0001 -> "🔻"
                                    else -> "🔷"
                                }
                                val color = when {
                                    diff > 0.0001 -> androidx.compose.ui.graphics.Color(0xFFE53935)
                                    diff < -0.0001 -> androidx.compose.ui.graphics.Color(0xFF43A047)
                                    else -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text(
                                        "1 USD = ${String.format(Locale.US, "%.4f", usdToEur)} EUR",
                                        fontSize = 12.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                    )
                                    if (prevUsdToEur != null && kotlin.math.abs(diff) > 0.0001) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "$trend${if (diff > 0) "+" else ""}${String.format(Locale.US, "%.4f", diff)}",
                                            fontSize = 10.sp,
                                            color = color
                                        )
                                    } else if (prevUsdToEur != null) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(trend, fontSize = 10.sp, color = color)
                                    }
                                }
                            }
                            if (eurToUsd != null) {
                                val diff = if (prevEurToUsd != null) eurToUsd - prevEurToUsd else 0.0
                                val trend = when {
                                    diff > 0.0001 -> "🔺"
                                    diff < -0.0001 -> "🔻"
                                    else -> "🔷"
                                }
                                val color = when {
                                    diff > 0.0001 -> androidx.compose.ui.graphics.Color(0xFFE53935)
                                    diff < -0.0001 -> androidx.compose.ui.graphics.Color(0xFF43A047)
                                    else -> androidx.compose.ui.graphics.Color(0xFF1E88E5)
                                }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Text(
                                        "1 EUR = ${String.format(Locale.US, "%.4f", eurToUsd)} USD",
                                        fontSize = 12.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                    )
                                    if (prevEurToUsd != null && kotlin.math.abs(diff) > 0.0001) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "$trend${if (diff > 0) "+" else ""}${String.format(Locale.US, "%.4f", diff)}",
                                            fontSize = 10.sp,
                                            color = color
                                        )
                                    } else if (prevEurToUsd != null) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(trend, fontSize = 10.sp, color = color)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
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

            // Помилка
            errorMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

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
                CURRENCIES.filter { it.code != baseCurrency }.forEach { curr ->
                    val value = convert(amountDouble, baseCurrency, curr.code, rates)
                    
                    // Отримуємо попередню ціну для порівняння
                    val previousRates = cache[source]?.previousRates
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
                    ) {
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
                                if (trend != null) {
                                    Text(
                                        trend,
                                        fontSize = 16.sp,
                                        color = trendColor
                                    )
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
}
