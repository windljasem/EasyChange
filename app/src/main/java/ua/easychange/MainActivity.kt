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
import androidx.compose.ui.graphics.Color
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
import retrofit2.http.Path
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
    val exchangers: List<KantorExchanger>? = null,
    val previousBtcPrice: Double? = null,
    val previousEthPrice: Double? = null
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

interface HexarateApi {
    @GET("api/rates/{from}/{to}/latest")
    suspend fun getRate(
        @Path("from") from: String,
        @Path("to") to: String
    ): HexarateLatestResponse
}

data class HexarateLatestResponse(
    val status_code: Int,
    val data: HexarateLatestData
)

data class HexarateLatestData(
    val base: String,
    val target: String,
    val mid: Double,
    val unit: Int
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
    CurrencyInfo("ALL", "🇦🇱", "Лек")
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
    val pln: KantorCurrencyRate?,
    val gbp: KantorCurrencyRate?
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
            Log.d("KANTOR", "=== Fetching data from kurstoday.com.ua homepage ===")
            val client = OkHttpClient()
            
            // Завантажуємо головну сторінку
            val timestamp = System.currentTimeMillis()
            val htmlRequest = Request.Builder()
                .url("https://kurstoday.com.ua/?_t=$timestamp")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("User-Agent", "Mozilla/5.0 (Android)")
                .build()
            
            Log.d("KANTOR", "Requesting: https://kurstoday.com.ua/?_t=$timestamp")
            val htmlResponse = client.newCall(htmlRequest).execute()
            Log.d("KANTOR", "HTML response code: ${htmlResponse.code()}")
            
            if (!htmlResponse.isSuccessful) {
                Log.e("KANTOR", "HTML fetch error: ${htmlResponse.code()}")
                return@withContext Pair(emptyList(), emptyList())
            }
            
            val html = htmlResponse.body()?.string()
            if (html == null) {
                Log.e("KANTOR", "HTML response body is null")
                return@withContext Pair(emptyList(), emptyList())
            }
            
            Log.d("KANTOR", "HTML loaded, size: ${html.length} chars")
            
            // Парсимо HTML - шукаємо ВСІ таблиці з валютами
            val rates = mutableListOf<Fx>()
            
            // Паттерн: USD + два числа поруч (42.31 та 42.69)
            val pattern = """(USD|EUR|PLN|ALL)[^0-9]+([\d.]+)[^0-9]+([\d.]+)""".toRegex()
            
            val allMatches = pattern.findAll(html).toList()
            Log.d("KANTOR", "Found ${allMatches.size} currency patterns in HTML")
            
            // Логуємо ВСІ знайдені входження
            allMatches.forEachIndexed { index, match ->
                val code = match.groupValues[1]
                val val1 = match.groupValues[2]
                val val2 = match.groupValues[3]
                Log.d("KANTOR", "Match[$index]: $code $val1 $val2")
            }
            
            // ФІЛЬТРУЄМО: залишаємо тільки валідні значення (1-100)
            // ALL (лек) ~ 4-5, USD ~ 42, EUR ~ 50, PLN ~ 12, GBP ~ 56
            val validMatches = allMatches.filter { match ->
                val val1 = match.groupValues[2].toDoubleOrNull()
                val val2 = match.groupValues[3].toDoubleOrNull()
                val1 != null && val2 != null && val1 > 1 && val2 > 1 && val1 < 100 && val2 < 100
            }
            
            Log.d("KANTOR", "Valid matches after filter: ${validMatches.size}")
            validMatches.forEachIndexed { index, match ->
                val code = match.groupValues[1]
                val val1 = match.groupValues[2]
                val val2 = match.groupValues[3]
                Log.d("KANTOR", "Valid[$index]: $code $val1 $val2")
            }
            
            // Групуємо по валюті - беремо ДРУГУ групу (обмінники, не банки)
            val currencyGroups = validMatches.groupBy { it.groupValues[1] }
            
            currencyGroups.forEach { (code, matches) ->
                Log.d("KANTOR", "$code: found ${matches.size} occurrences")
                
                // Беремо другу групу (індекс 1) якщо є, інакше першу
                val match = if (matches.size > 1) matches[1] else matches.firstOrNull()
                
                if (match != null) {
                    val buyStr = match.groupValues[2].trim()
                    val sellStr = match.groupValues[3].trim()
                    
                    Log.d("KANTOR", "$code: using buy=$buyStr, sell=$sellStr")
                    
                    val buy = buyStr.toDoubleOrNull()
                    val sell = sellStr.toDoubleOrNull()
                    
                    if (buy != null && sell != null) {
                        val mid = (buy + sell) / 2.0
                        rates.add(Fx(code, "UAH", buy, sell, mid))
                        Log.d("KANTOR", "✓ Parsed $code: buy=$buy, sell=$sell, mid=$mid")
                    }
                }
            }
            
            val exchangers = emptyList<KantorExchanger>()
            
            // Якщо нічого не знайшли - логуємо
            if (rates.isEmpty()) {
                Log.e("KANTOR", "FAILED to parse any rates from HTML!")
                Log.e("KANTOR", "HTML size: ${html.length} chars")
            }
            
            Log.d("KANTOR", "=== Parse complete ===")
            Log.d("KANTOR", "Total: ${rates.size} rates from HTML")
            Log.d("KANTOR", "Timestamp: ${System.currentTimeMillis()}")
            rates.forEach {
                Log.d("KANTOR", "Final rate: ${it.base}/${it.quote} buy=${it.buy} sell=${it.sell}")
            }
            
            Pair(rates, exchangers)
            
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

        val hexarate = Retrofit.Builder()
            .baseUrl("https://hexarate.paikama.co/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HexarateApi::class.java)

        val binance = Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BinanceApi::class.java)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF5E35B1),        // Фіолетовий для активних кнопок
                    secondary = Color(0xFF78909C),      // Сіро-синій для вторинних елементів
                    tertiary = Color(0xFF8D6E63),       // Коричневий для кнопки міста
                    background = Color(0xFFECEFF1),     // Світло-сірий фон
                    surface = Color(0xFFFFFFFF),        // Білий для карток
                    surfaceVariant = Color(0xFFE8EAF6), // Світло-фіолетовий для картки кроскурсу
                    onPrimary = Color(0xFFFFFFFF),      // Білий текст на primary
                    onSecondary = Color(0xFFFFFFFF),    // Білий текст на secondary
                    onBackground = Color(0xFF263238),   // Темно-сірий текст на фоні
                    onSurface = Color(0xFF263238),      // Темно-сірий текст на картках
                    onSurfaceVariant = Color(0xFF546E7A) // Сірий для вторинного тексту
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(nbu, hexarate, binance)
                }
            }
        }
    }
}

// ------------------ MAIN SCREEN ------------------
@Composable
fun MainScreen(
    nbu: NbuApi,
    hexarate: HexarateApi,
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
    var showCityPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Кеш для кожного джерела (тільки в пам'яті, для швидкого доступу протягом сесії)
    val cache = remember { mutableMapOf<String, CachedRates>() }
    
    // Зберігаємо вибрану валюту
    fun saveCurrency(currency: String) {
        prefs.edit().putString("last_currency", currency).apply()
        baseCurrency = currency
    }

    fun refresh(force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val cacheKey = if (source == "KANTOR") "$source-$kantorCity" else source
        
        // Перевіряємо кеш у пам'яті (60 секунд), якщо НЕ force
        if (!force) {
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
        } else {
            Log.d("EasyChange", "Force refresh for $cacheKey - ignoring cache")
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

                        "EUR" -> {
                            try {
                                val rates = mutableListOf<Fx>()
                                
                                // Запит 1: USD → UAH
                                try {
                                    val uahResponse = hexarate.getRate("USD", "UAH")
                                    Log.d("EasyChange", "Hexarate USD→UAH: ${uahResponse.data.mid}")
                                    rates.add(Fx("UAH", "USD", null, null, uahResponse.data.mid))
                                    // Обернена пара
                                    rates.add(Fx("USD", "UAH", null, null, 1.0 / uahResponse.data.mid))
                                } catch (e: Exception) {
                                    Log.e("EasyChange", "Hexarate USD→UAH failed: ${e.message}")
                                }
                                
                                // Запит 2: USD → EUR
                                try {
                                    val eurResponse = hexarate.getRate("USD", "EUR")
                                    Log.d("EasyChange", "Hexarate USD→EUR: ${eurResponse.data.mid}")
                                    rates.add(Fx("EUR", "USD", null, null, eurResponse.data.mid))
                                    // Обернена пара
                                    rates.add(Fx("USD", "EUR", null, null, 1.0 / eurResponse.data.mid))
                                } catch (e: Exception) {
                                    Log.e("EasyChange", "Hexarate USD→EUR failed: ${e.message}")
                                }
                                
                                // Запит 3: USD → PLN
                                try {
                                    val plnResponse = hexarate.getRate("USD", "PLN")
                                    Log.d("EasyChange", "Hexarate USD→PLN: ${plnResponse.data.mid}")
                                    rates.add(Fx("PLN", "USD", null, null, plnResponse.data.mid))
                                    // Обернена пара
                                    rates.add(Fx("USD", "PLN", null, null, 1.0 / plnResponse.data.mid))
                                } catch (e: Exception) {
                                    Log.e("EasyChange", "Hexarate USD→PLN failed: ${e.message}")
                                }
                                
                                // Запит 4: USD → ALL
                                try {
                                    val allResponse = hexarate.getRate("USD", "ALL")
                                    Log.d("EasyChange", "Hexarate USD→ALL: ${allResponse.data.mid}")
                                    rates.add(Fx("ALL", "USD", null, null, allResponse.data.mid))
                                    // Обернена пара
                                    rates.add(Fx("USD", "ALL", null, null, 1.0 / allResponse.data.mid))
                                } catch (e: Exception) {
                                    Log.e("EasyChange", "Hexarate USD→ALL failed: ${e.message}")
                                }
                                
                                Log.d("EasyChange", "Hexarate total parsed: ${rates.size} rates (с обернениями)")
                                rates.forEach { fx ->
                                    Log.d("EasyChange", "Parsed: ${fx.base}/${fx.quote} = ${fx.mid}")
                                }
                                
                                newRates = rates
                                newExchangers = emptyList()
                            } catch (e: Exception) {
                                Log.e("EasyChange", "Hexarate error: ${e.message}", e)
                                newRates = cache[cacheKey]?.rates ?: emptyList()
                                newExchangers = emptyList()
                            }
                        }

                        "KANTOR" -> {
                            try {
                                Log.d("KANTOR", "=== Calling fetchKantorData for city: $kantorCity ===")
                                val (avgRates, exch) = fetchKantorData(kantorCity)
                                Log.d("KANTOR", "fetchKantorData returned: ${avgRates.size} rates, ${exch.size} exchangers")
                                
                                if (avgRates.isNotEmpty()) {
                                    avgRates.forEach { rate ->
                                        Log.d("KANTOR", "Received rate: ${rate.base}/${rate.quote} buy=${rate.buy} sell=${rate.sell} mid=${rate.mid}")
                                    }
                                } else {
                                    Log.w("KANTOR", "fetchKantorData returned EMPTY list!")
                                }
                                
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
                        
                        // Отримуємо попередні ціни crypto з старого кешу
                        val previousBtc = cache[cacheKey]?.btcPrice
                        val previousEth = cache[cacheKey]?.ethPrice
                        
                        // Зберігаємо нові дані як попередні (для наступного разу)
                        savePreviousRates(context, cacheKey, newRates)
                        
                        // Оновлюємо кеш у пам'яті
                        cache[cacheKey] = CachedRates(newRates, newBtc, newEth, currentTime, previousRates, newExchangers, previousBtc, previousEth)
                        
                        rates = newRates
                        btcPrice = newBtc
                        ethPrice = newEth
                        exchangers = newExchangers
                        
                        Log.d("UI", "=== UI updated for $cacheKey ===")
                        Log.d("UI", "rates.size = ${rates.size}")
                        rates.take(3).forEach {
                            Log.d("UI", "UI rate: ${it.base}/${it.quote} buy=${it.buy} sell=${it.sell}")
                        }
                        
                        val format = SimpleDateFormat("dd.MM.yyyy 'о' HH:mm", Locale("uk"))
                        lastUpdate = "Оновлено ${format.format(Date())}"
                        
                        Log.d("Cache", "Updated cache for $cacheKey: ${newRates.size} rates, ${newExchangers.size} exchangers")
                        Log.d("Cache", "previousRates: ${previousRates?.size ?: 0}, prevBTC: $previousBtc, prevETH: $previousEth")
                        newRates.take(3).forEach { 
                            Log.d("Cache", "Rate: ${it.base}/${it.quote} = buy:${it.buy}, sell:${it.sell}, mid:${it.mid}")
                        }
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

    // При старті - завантажуємо NBU
    LaunchedEffect(Unit) {
        Log.d("EasyChange", "App started - loading NBU")
        if (cache["NBU"] == null) {
            source = "NBU"
            refresh(force = true)
        }
    }
    
    // При зміні джерела - завантажуємо це джерело
    LaunchedEffect(source, kantorCity) {
        if (source != "NBU" || cache["NBU"] == null) {
            Log.d("EasyChange", "Source changed to: $source")
            refresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Верхня частина - не скролиться
        Column(modifier = Modifier.padding(16.dp)) {
            // Кнопки джерел
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Верхній ряд - NBU і EUR (Hexarate)
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
                        onClick = { source = "EUR" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "EUR") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("EUR", fontSize = 13.sp)
                            Text("hexarate.paikama.co", fontSize = 8.sp)
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
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
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
                                color = MaterialTheme.colorScheme.onSurface,
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
                        // Прапор тільки інформаційно, без tap
                        Text(curr?.flag ?: "🏳", fontSize = 24.sp, modifier = Modifier.padding(start = 12.dp))
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isLoading
                )
                
                Button(
                    onClick = { refresh(force = true) },
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
                
                CURRENCIES.filter { curr ->
                    curr.code != baseCurrency && 
                    // ALL показуємо тільки для Hexarate (EUR)
                    (curr.code != "ALL" || source == "EUR")
                }.forEach { curr ->
                    // Для KANTOR UAH потрібна особлива логіка
                    val isKantorUah = source == "KANTOR" && baseCurrency != "UAH" && curr.code == "UAH"
                    
                    val value = convert(amountDouble, baseCurrency, curr.code, rates)
                    
                    if (source == "KANTOR") {
                        val fx = rates.firstOrNull { it.base == curr.code && it.quote == "UAH" }
                        Log.d("UI", "KANTOR ${curr.code}: fx=${fx != null}, buy=${fx?.buy}, sell=${fx?.sell}, value=$value")
                    }
                    
                    // Отримуємо попередню ціну для порівняння
                    val cacheKey = if (source == "KANTOR") "$source-$kantorCity" else source
                    val previousRates = cache[cacheKey]?.previousRates
                    val previousValue = if (previousRates != null && amountDouble > 0) {
                        if (isKantorUah) {
                            // Для UAH використовуємо базову валюту
                            convert(amountDouble, baseCurrency, "UAH", previousRates)
                        } else {
                            convert(amountDouble, baseCurrency, curr.code, previousRates)
                        }
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
                            .clickable {
                                // Tap на картку → змінює базову валюту
                                baseCurrency = curr.code
                                saveCurrency(curr.code)  // Зберігаємо вибір
                                // Також розгортає exchangers для KANTOR (якщо є)
                                if (source == "KANTOR" && exchangers.isNotEmpty()) {
                                    expandedCurrency = if (expandedCurrency == curr.code) null else curr.code
                                }
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = androidx.compose.ui.Alignment.Top
                            ) {
                                // Ліва частина - назва валюти
                                Text(
                                    "${curr.flag} ${curr.code}",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.alignByBaseline()
                                )
                                
                                if (source == "KANTOR") {
                                    // Для KANTOR - два стовпці: курси та калькулятор
                                    if (isKantorUah) {
                                        // UAH - зворотна конвертація (базова валюта → UAH)
                                        val baseFx = rates.firstOrNull { it.base == baseCurrency && it.quote == "UAH" }
                                        if (baseFx?.buy != null && baseFx.sell != null && amountDouble > 0) {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalAlignment = androidx.compose.ui.Alignment.Top
                                            ) {
                                                // Стовпець 1: Курси
                                                Column(horizontalAlignment = androidx.compose.ui.Alignment.Start) {
                                                    Text(
                                                        "К: ${String.format(Locale.US, "%.2f", baseFx.buy)}",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        "П: ${String.format(Locale.US, "%.2f", baseFx.sell)}",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                                
                                                // Стовпець 2: Калькулятор
                                                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                                    val buyCalc = amountDouble * baseFx.buy
                                                    Text(
                                                        "${String.format(Locale.US, "%.2f", buyCalc)} ₴",
                                                        fontSize = 13.sp,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    val sellCalc = amountDouble * baseFx.sell
                                                    Text(
                                                        "${String.format(Locale.US, "%.2f", sellCalc)} ₴",
                                                        fontSize = 13.sp,
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.secondary
                                                    )
                                                }
                                                
                                                // Стовпець 3: Тренд
                                                Column(
                                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                                    modifier = Modifier.width(50.dp)
                                                ) {
                                                    if (trend != null) {
                                                        Text(
                                                            trend,
                                                            fontSize = 16.sp,
                                                            color = trendColor
                                                        )
                                                        // Показуємо зміну
                                                        if (diff != null) {
                                                            val sign = if (diff > 0) "+" else ""
                                                            Text(
                                                                "$sign${String.format(Locale.US, "%.2f", diff)}",
                                                                fontSize = 9.sp,
                                                                color = trendColor
                                                            )
                                                        }
                                                    }
                                                }
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
                                        // Інші валюти
                                        val fx = rates.firstOrNull { it.base == curr.code && it.quote == "UAH" }
                                        if (fx?.buy != null && fx.sell != null) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = androidx.compose.ui.Alignment.Top
                                        ) {
                                            // Стовпець 1: Курси (К/П)
                                            Column(horizontalAlignment = androidx.compose.ui.Alignment.Start) {
                                                Text(
                                                    "К: ${String.format(Locale.US, "%.2f", fx.buy)}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    "П: ${String.format(Locale.US, "%.2f", fx.sell)}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.secondary
                                                )
                                            }
                                            
                                            // Стовпець 2: Калькулятор
                                            if (amountDouble > 0) {
                                                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                                    if (baseCurrency == "UAH") {
                                                        // UAH → валюта (ділити на курс)
                                                        // Ви купуєте валюту (обмінник продає) - платите по sell
                                                        val buyCalc = amountDouble / fx.sell
                                                        Text(
                                                            "${String.format(Locale.US, "%.2f", buyCalc)}",
                                                            fontSize = 13.sp,
                                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        // Ви продаєте валюту (обмінник купує) - отримуєте по buy
                                                        val sellCalc = amountDouble / fx.buy
                                                        Text(
                                                            "${String.format(Locale.US, "%.2f", sellCalc)}",
                                                            fontSize = 13.sp,
                                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.secondary
                                                        )
                                                    } else if (baseCurrency == curr.code) {
                                                        // Валюта → UAH (множити на курс)
                                                        // Ви продаєте валюту - отримуєте по buy
                                                        val buyCalc = amountDouble * fx.buy
                                                        Text(
                                                            "${String.format(Locale.US, "%.2f", buyCalc)} ₴",
                                                            fontSize = 13.sp,
                                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        // Ви купуєте валюту - платите по sell
                                                        val sellCalc = amountDouble * fx.sell
                                                        Text(
                                                            "${String.format(Locale.US, "%.2f", sellCalc)} ₴",
                                                            fontSize = 13.sp,
                                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.secondary
                                                        )
                                                    } else {
                                                        // Валюта → валюта (через UAH)
                                                        val baseToUah = rates.firstOrNull { it.base == baseCurrency && it.quote == "UAH" }
                                                        if (baseToUah?.buy != null && baseToUah.sell != null) {
                                                            // Спочатку базову → UAH (по mid), потім UAH → цільову
                                                            val uahAmount = amountDouble * ((baseToUah.buy + baseToUah.sell) / 2.0)
                                                            val buyCalc = uahAmount / fx.sell
                                                            val sellCalc = uahAmount / fx.buy
                                                            Text(
                                                                "${String.format(Locale.US, "%.2f", buyCalc)}",
                                                                fontSize = 13.sp,
                                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.primary
                                                            )
                                                            Text(
                                                                "${String.format(Locale.US, "%.2f", sellCalc)}",
                                                                fontSize = 13.sp,
                                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.secondary
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            
                                            // Стовпець 3: Тренд
                                            Column(
                                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                                modifier = Modifier.width(50.dp)
                                            ) {
                                                if (trend != null) {
                                                    Text(
                                                        trend,
                                                        fontSize = 16.sp,
                                                        color = trendColor
                                                    )
                                                    // Показуємо зміну
                                                    if (diff != null) {
                                                        val sign = if (diff > 0) "+" else ""
                                                        Text(
                                                            "$sign${String.format(Locale.US, "%.2f", diff)}",
                                                            fontSize = 9.sp,
                                                            color = trendColor
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        } else {
                                            Text(
                                                "НЕ ВИЗНАЧЕНО",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }  // Закриваємо else для не-UAH валют
                                } else {
                                    // Для NBU/EUR - як раніше
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
            btcPrice?.let { btcPriceValue ->
                // Обчислюємо тренд BTC
                val cacheKey = if (source == "KANTOR") "$source-$kantorCity" else source
                val previousBtc = cache[cacheKey]?.previousBtcPrice
                val btcDiff = if (previousBtc != null) {
                    btcPriceValue - previousBtc
                } else null
                val btcTrend = if (btcDiff != null) {
                    when {
                        btcDiff > 10.0 -> "🔺"
                        btcDiff < -10.0 -> "🔻"
                        else -> "🔷"
                    }
                } else null
                
                val btcTrendColor = when (btcTrend) {
                    "🔺" -> androidx.compose.ui.graphics.Color(0xFF43A047) // зелений (зросла ціна)
                    "🔻" -> androidx.compose.ui.graphics.Color(0xFFE53935) // червоний (впала ціна)
                    "🔷" -> androidx.compose.ui.graphics.Color(0xFF1E88E5) // синій (без змін)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            "₿ BTC",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                String.format(Locale.US, "%.2f", btcPriceValue) + " USD",
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 16.sp
                            )
                            if (btcTrend != null) {
                                Text(
                                    btcTrend,
                                    fontSize = 16.sp,
                                    color = btcTrendColor
                                )
                            }
                        }
                    }
                }
            }

            ethPrice?.let { ethPriceValue ->
                // Обчислюємо тренд ETH
                val cacheKey = if (source == "KANTOR") "$source-$kantorCity" else source
                val previousEth = cache[cacheKey]?.previousEthPrice
                val ethDiff = if (previousEth != null) {
                    ethPriceValue - previousEth
                } else null
                val ethTrend = if (ethDiff != null) {
                    when {
                        ethDiff > 5.0 -> "🔺"
                        ethDiff < -5.0 -> "🔻"
                        else -> "🔷"
                    }
                } else null
                
                val ethTrendColor = when (ethTrend) {
                    "🔺" -> androidx.compose.ui.graphics.Color(0xFF43A047) // зелений (зросла ціна)
                    "🔻" -> androidx.compose.ui.graphics.Color(0xFFE53935) // червоний (впала ціна)
                    "🔷" -> androidx.compose.ui.graphics.Color(0xFF1E88E5) // синій (без змін)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(
                            "Ξ ETH",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                String.format(Locale.US, "%.2f", ethPriceValue) + " USD",
                                style = MaterialTheme.typography.bodyLarge,
                                fontSize = 16.sp
                            )
                            if (ethTrend != null) {
                                Text(
                                    ethTrend,
                                    fontSize = 16.sp,
                                    color = ethTrendColor
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
