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

// Окремий кеш для кожного джерела
data class SourceCachedRates(
    val rates: List<Fx>,
    val btcPrice: Double?,
    val ethPrice: Double?,
    val timestamp: Long,
    val previousRates: List<Fx>? = null
)

// ------------------ API INTERFACES ------------------
interface MonoApi {
    @GET("bank/currency")
    suspend fun load(): List<MonoDto>
}

data class MonoDto(
    val currencyCodeA: Int,
    val currencyCodeB: Int,
    val date: Long,
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

interface KursTodayApi {
    @GET("v1/currency/rates")
    suspend fun load(
        @Query("currency") currency: String,
        @Query("type") type: String = "exchange"
    ): KursTodayRate
}

data class KursTodayRate(
    val currency: String,
    val buy: Double,
    val sell: Double
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

        val kursToday = Retrofit.Builder()
            .baseUrl("https://kurs.com.ua/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KursTodayApi::class.java)

        val exchangeRate = Retrofit.Builder()
            .baseUrl("https://v6.exchangerate-api.com/")
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
                    Main(mono, nbu, nbp, kurs, kursToday, exchangeRate, binance)
                }
            }
        }
    }
}

@Composable
fun Main(
    mono: MonoApi,
    nbu: NbuApi,
    nbp: NbpApi,
    kurs: KursApi,
    kursToday: KursTodayApi,
    exchangeRate: ExchangeRateApi,
    binance: BinanceApi
) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("easychange", Context.MODE_PRIVATE)
    
    var source by remember { mutableStateOf(prefs.getString("source", "MONO") ?: "MONO") }
    var baseCurrency by remember { mutableStateOf(prefs.getString("base", "USD") ?: "USD") }
    
    // Окремий кеш для кожного джерела
    var sourceCache by remember { 
        mutableStateOf<Map<String, SourceCachedRates>>(emptyMap()) 
    }
    
    var rates by remember { mutableStateOf(emptyList<Fx>()) }
    var btcPrice by remember { mutableStateOf<Double?>(null) }
    var ethPrice by remember { mutableStateOf<Double?>(null) }
    var amount by remember { mutableStateOf("100") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastUpdated by remember { mutableStateOf<String?>(null) }
    var showCurrencyPicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun saveSource(s: String) {
        source = s
        prefs.edit().putString("source", s).apply()
    }

    fun saveCurrency(c: String) {
        baseCurrency = c
        prefs.edit().putString("base", c).apply()
    }
    
    // Завантаження даних з окремим кешем для кожного джерела
    suspend fun loadData(forceRefresh: Boolean = false): SourceCachedRates? {
        val now = System.currentTimeMillis()
        val cached = sourceCache[source]
        
        // Перевіряємо кеш для поточного джерела
        if (!forceRefresh && cached != null && (now - cached.timestamp) < 5 * 60 * 1000) {
            Log.d("EasyChange", "Використовую кеш для $source")
            return cached
        }

        return withContext(Dispatchers.IO) {
            try {
                val newRates = when (source) {
                    "MONO" -> {
                        Log.d("EasyChange", "Завантаження MONO")
                        val data = mono.load()
                        Log.d("EasyChange", "MONO відповідь: ${data.size} записів")
                        
                        data.mapNotNull { dto ->
                            val a = dto.code(dto.currencyCodeA) ?: return@mapNotNull null
                            val b = dto.code(dto.currencyCodeB) ?: return@mapNotNull null
                            
                            when {
                                dto.rateBuy != null && dto.rateSell != null -> {
                                    val mid = (dto.rateBuy + dto.rateSell) / 2.0
                                    Fx(a, b, dto.rateBuy, dto.rateSell, mid)
                                }
                                dto.rateCross != null -> {
                                    Fx(a, b, null, null, dto.rateCross)
                                }
                                else -> null
                            }
                        }
                    }

                    "NBU" -> {
                        Log.d("EasyChange", "Завантаження NBU")
                        val data = nbu.load()
                        Log.d("EasyChange", "NBU відповідь: ${data.size} записів")
                        
                        data.mapNotNull { dto ->
                            val code = dto.cc ?: return@mapNotNull null
                            val rate = dto.rate ?: return@mapNotNull null
                            
                            if (CURRENCIES.any { it.code == code }) {
                                Fx(code, "UAH", null, null, rate)
                            } else null
                        }
                    }

                    "NBP" -> {
                        Log.d("EasyChange", "Завантаження NBP")
                        val data = nbp.load()
                        Log.d("EasyChange", "NBP відповідь: ${data.size} таблиць")
                        
                        data.firstOrNull()?.rates?.mapNotNull { dto ->
                            if (CURRENCIES.any { it.code == dto.code }) {
                                Fx(dto.code, "PLN", null, null, dto.mid)
                            } else null
                        } ?: emptyList()
                    }

                    "KURS" -> {
                        Log.d("EasyChange", "Завантаження KURS")
                        val data = kurs.load()
                        Log.d("EasyChange", "KURS відповідь: ${data.data.size} записів")
                        
                        data.data.mapNotNull { dto ->
                            if (CURRENCIES.any { it.code == dto.code }) {
                                val mid = (dto.buy + dto.sell) / 2.0
                                Fx(dto.code, "UAH", dto.buy, dto.sell, mid)
                            } else null
                        }
                    }

                    "KURS_TODAY" -> {
                        Log.d("EasyChange", "Завантаження KURS_TODAY")
                        val rates = mutableListOf<Fx>()
                        
                        listOf("usd", "eur", "pln", "gbp", "chf", "czk", "cad", "cny").forEach { curr ->
                            try {
                                val data = kursToday.load(curr)
                                val code = curr.uppercase()
                                val mid = (data.buy + data.sell) / 2.0
                                rates.add(Fx(code, "UAH", data.buy, data.sell, mid))
                            } catch (e: Exception) {
                                Log.w("EasyChange", "Помилка для $curr: ${e.message}")
                            }
                        }
                        rates
                    }

                    "EXCHANGE_RATE" -> {
                        Log.d("EasyChange", "Завантаження EXCHANGE_RATE")
                        val data = exchangeRate.load()
                        Log.d("EasyChange", "EXCHANGE_RATE відповідь: ${data.rates.size} курсів")
                        
                        data.rates.mapNotNull { (code, rate) ->
                            if (CURRENCIES.any { it.code == code }) {
                                Fx("USD", code, null, null, rate)
                            } else null
                        }
                    }

                    else -> {
                        Log.w("EasyChange", "Невідоме джерело: $source")
                        emptyList()
                    }
                }

                val newBtcPrice = try {
                    binance.getPrice("BTCUSDT").price.toDoubleOrNull()
                } catch (e: Exception) {
                    Log.w("EasyChange", "Помилка BTC: ${e.message}")
                    null
                }

                val newEthPrice = try {
                    binance.getPrice("ETHUSDT").price.toDoubleOrNull()
                } catch (e: Exception) {
                    Log.w("EasyChange", "Помилка ETH: ${e.message}")
                    null
                }

                SourceCachedRates(
                    rates = newRates,
                    btcPrice = newBtcPrice,
                    ethPrice = newEthPrice,
                    timestamp = now,
                    previousRates = cached?.rates
                )
            } catch (e: Exception) {
                Log.e("EasyChange", "Помилка завантаження: ${e.message}", e)
                null
            }
        }
    }

    fun refresh() {
        scope.launch {
            isLoading = true
            errorMessage = null
            
            val result = loadData(forceRefresh = true)
            
            if (result != null) {
                // Оновлюємо кеш для поточного джерела
                sourceCache = sourceCache + (source to result)
                
                rates = result.rates
                btcPrice = result.btcPrice
                ethPrice = result.ethPrice
                
                val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                lastUpdated = sdf.format(Date())
                
                Log.d("EasyChange", "Завантажено ${rates.size} курсів для $source")
            } else {
                errorMessage = "Не вдалося завантажити дані"
                
                // Якщо є кеш, використовуємо його
                val cached = sourceCache[source]
                if (cached != null) {
                    rates = cached.rates
                    btcPrice = cached.btcPrice
                    ethPrice = cached.ethPrice
                    errorMessage = "Використано кешовані дані"
                }
            }
            
            isLoading = false
        }
    }

    // Завантаження при зміні джерела
    LaunchedEffect(source) {
        isLoading = true
        errorMessage = null
        
        val result = loadData(forceRefresh = false)
        
        if (result != null) {
            // Оновлюємо кеш для поточного джерела
            sourceCache = sourceCache + (source to result)
            
            rates = result.rates
            btcPrice = result.btcPrice
            ethPrice = result.ethPrice
            
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            lastUpdated = sdf.format(Date())
        } else {
            errorMessage = "Не вдалося завантажити дані"
        }
        
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp)
    ) {
        // Верхня панель з елементами керування
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            // Заголовок з назвою джерела
            Text(
                text = when (source) {
                    "MONO" -> "Монобанк"
                    "NBU" -> "НБУ"
                    "NBP" -> "NBP (Польща)"
                    "KURS" -> "Міжбанк"
                    "KURS_TODAY" -> "Обмінники"
                    "EXCHANGE_RATE" -> "Exchange Rate"
                    else -> source
                },
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Час оновлення
            lastUpdated?.let {
                Text(
                    "Оновлено: $it",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // Вибір джерела
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(
                    "MONO" to "MONO",
                    "NBU" to "НБУ",
                    "NBP" to "NBP",
                    "KURS" to "МБ",
                    "KURS_TODAY" to "ОБМ",
                    "EXCHANGE_RATE" to "ER"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = source == key,
                        onClick = { saveSource(key) },
                        label = { Text(label, fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    )
                }
            }

            // Інформація про кеш
            if (sourceCache.isNotEmpty()) {
                Text(
                    "Кеш: ${sourceCache.keys.joinToString(", ")}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
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
                    
                    // Отримуємо попередню ціну для порівняння з кешу поточного джерела
                    val previousRates = sourceCache[source]?.previousRates
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
