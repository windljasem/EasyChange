package ua.easychange

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
data class Fx(val base: String, val quote: String, val buy: Double?, val sell: Double?, val mid: Double)

data class CurrencyInfo(
    val code: String,
    val flag: String,
    val name: String
)

// ------------------ API ------------------
interface KursApi {
    @GET("api/market/exchange-rates")
    suspend fun load(): KursResponse
}
data class KursResponse(val data: List<KursDto>)
data class KursDto(val base: String, val quote: String, val buy: Double, val sell: Double)

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

interface MinfinApi {
    @GET("ua/currency/")
    suspend fun load(): String
}

interface BinanceApi {
    @GET("api/v3/ticker/price")
    suspend fun btc(@Query("symbol") s: String = "BTCUSDT"): BinanceDto
}
data class BinanceDto(val price: String)

// ------------------ CONSTANTS ------------------
val CURRENCIES = listOf(
    CurrencyInfo("UAH", "🇺🇦", "Гривня"),
    CurrencyInfo("USD", "🇺🇸", "Долар США"),
    CurrencyInfo("EUR", "🇪🇺", "Євро"),
    CurrencyInfo("PLN", "🇵🇱", "Злотий"),
    CurrencyInfo("GBP", "🇬🇧", "Фунт"),
    CurrencyInfo("HRK", "🇭🇷", "Куна"),
    CurrencyInfo("CHF", "🇨🇭", "Франк"),
    CurrencyInfo("CZK", "🇨🇿", "Крона"),
    CurrencyInfo("BGN", "🇧🇬", "Лев"),
    CurrencyInfo("CAD", "🇨🇦", "Дол. Канади")
)

// ------------------ UTILS ------------------
fun MonoDto.code(i: Int) = when (i) {
    840 -> "USD"
    978 -> "EUR"
    985 -> "PLN"
    980 -> "UAH"
    826 -> "GBP"
    191 -> "HRK"
    756 -> "CHF"
    203 -> "CZK"
    975 -> "BGN"
    124 -> "CAD"
    else -> null
}

fun parseMinfinHtml(html: String): List<Fx> {
    val rates = mutableListOf<Fx>()
    val currencies = listOf("USD", "EUR", "PLN", "GBP", "CHF", "CZK")
    
    try {
        for (curr in currencies) {
            val regex = """data-currency="$curr"[^>]*>[\s\S]*?<td[^>]*>([\d.]+)</td>[\s\S]*?<td[^>]*>([\d.]+)</td>""".toRegex()
            val match = regex.find(html)
            if (match != null) {
                val buy = match.groupValues[1].toDoubleOrNull()
                val sell = match.groupValues[2].toDoubleOrNull()
                if (buy != null && sell != null && buy > 0 && sell > 0) {
                    rates.add(Fx(curr, "UAH", buy, sell, (buy + sell) / 2))
                }
            }
        }
    } catch (e: Exception) {
        Log.e("EasyChange", "Minfin parsing error: ${e.message}")
    }
    
    return rates
}

fun convert(a: Double, from: String, to: String, r: List<Fx>): Double? {
    if (from == to) return a
    
    r.firstOrNull { it.base == from && it.quote == to }?.let { 
        return a * it.mid 
    }
    
    r.firstOrNull { it.base == to && it.quote == from }?.let { 
        return a / it.mid 
    }
    
    // Через UAH
    val fromUah = r.firstOrNull { it.base == from && it.quote == "UAH" }
    val toUah = r.firstOrNull { it.base == to && it.quote == "UAH" }
    
    if (fromUah != null && toUah != null) {
        val uahAmount = a * fromUah.mid
        return uahAmount / toUah.mid
    }
    
    // Через USD
    val toUsd = r.firstOrNull { it.base == from && it.quote == "USD" }
        ?: r.firstOrNull { it.quote == from && it.base == "USD" }
    
    val fromUsd = r.firstOrNull { it.base == "USD" && it.quote == to }
        ?: r.firstOrNull { it.quote == "USD" && it.base == to }
    
    if (toUsd != null && fromUsd != null) {
        val usdAmount = if (toUsd.base == from) a * toUsd.mid else a / toUsd.mid
        return if (fromUsd.base == "USD") usdAmount * fromUsd.mid else usdAmount / fromUsd.mid
    }
    
    return null
}

// ------------------ ACTIVITY ------------------
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

        val minfin = Retrofit.Builder()
            .baseUrl("https://minfin.com.ua/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MinfinApi::class.java)

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
                    MainScreen(kurs, mono, nbu, minfin, binance)
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    kurs: KursApi,
    mono: MonoApi,
    nbu: NbuApi,
    minfin: MinfinApi,
    binance: BinanceApi
) {
    var source by remember { mutableStateOf("KURS") }
    var baseCurrency by remember { mutableStateOf("USD") }
    var amount by remember { mutableStateOf("100") }
    var rates by remember { mutableStateOf<List<Fx>>(emptyList()) }
    var btc by remember { mutableStateOf<Double?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var lastUpdate by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCurrencyPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            isLoading = true
            errorMessage = null
            
            withContext(Dispatchers.IO) {
                try {
                    Log.d("EasyChange", "Loading rates from: $source")
                    
                    rates = when (source) {
                        "KURS" -> {
                            try {
                                val response = kurs.load()
                                Log.d("EasyChange", "KURS loaded: ${response.data.size} rates")
                                
                                if (response.data.isEmpty()) {
                                    errorMessage = "KURS: отримано 0 курсів"
                                }
                                
                                response.data
                                    .filter { it.buy > 0 && it.sell > 0 }
                                    .map {
                                        Log.d("EasyChange", "KURS: ${it.base}/${it.quote} = ${it.buy}/${it.sell}")
                                        Fx(it.base, it.quote, it.buy, it.sell, (it.buy + it.sell) / 2)
                                    }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "KURS error: ${e.message}", e)
                                errorMessage = "KURS помилка: ${e.message}"
                                emptyList()
                            }
                        }

                        "MONO" -> {
                            try {
                                val response = mono.load()
                                Log.d("EasyChange", "MONO loaded: ${response.size} rates")
                                
                                response.mapNotNull {
                                    val b = it.code(it.currencyCodeA)
                                    val q = it.code(it.currencyCodeB)
                                    
                                    if (b != null && q == "UAH") {
                                        val buy = it.rateBuy
                                        val sell = it.rateSell
                                        val cross = it.rateCross
                                        
                                        val mid = when {
                                            cross != null && cross > 0 -> cross
                                            buy != null && sell != null && buy > 0 && sell > 0 -> (buy + sell) / 2
                                            buy != null && buy > 0 -> buy
                                            sell != null && sell > 0 -> sell
                                            else -> null
                                        }
                                        
                                        if (mid != null && mid > 0) {
                                            Fx(b, q, buy, sell, mid)
                                        } else null
                                    } else null
                                }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "MONO error: ${e.message}", e)
                                errorMessage = "MONO помилка: ${e.message}"
                                emptyList()
                            }
                        }

                        "NBU" -> {
                            try {
                                val response = nbu.load()
                                Log.d("EasyChange", "NBU loaded: ${response.size} rates")
                                
                                response
                                    .filter { 
                                        it.cc != null && 
                                        it.rate != null && 
                                        it.rate > 0 &&
                                        it.cc in CURRENCIES.map { c -> c.code }
                                    }
                                    .map {
                                        Fx(it.cc!!, "UAH", null, null, it.rate!!)
                                    }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "NBU error: ${e.message}", e)
                                errorMessage = "NBU помилка: ${e.message}"
                                emptyList()
                            }
                        }

                        "INTERBANK" -> {
                            try {
                                val html = minfin.load()
                                Log.d("EasyChange", "MINFIN loaded HTML: ${html.length} chars")
                                
                                val parsed = parseMinfinHtml(html)
                                Log.d("EasyChange", "MINFIN parsed: ${parsed.size} rates")
                                
                                if (parsed.isEmpty()) {
                                    errorMessage = "INTERBANK: не знайдено курсів у HTML"
                                }
                                
                                parsed.forEach {
                                    Log.d("EasyChange", "MINFIN: ${it.base}/${it.quote} = ${it.mid}")
                                }
                                
                                parsed
                            } catch (e: Exception) {
                                Log.e("EasyChange", "MINFIN error: ${e.message}", e)
                                errorMessage = "INTERBANK помилка: ${e.message}"
                                emptyList()
                            }
                        }

                        else -> emptyList()
                    }

                    if (rates.isNotEmpty()) {
                        val dateFormat = SimpleDateFormat("dd.MM.yyyy 'о' HH:mm", Locale("uk"))
                        lastUpdate = "Курс оновлено ${dateFormat.format(Date())}"
                    }

                    try {
                        val btcResponse = binance.btc()
                        btc = btcResponse.price.toDoubleOrNull()
                    } catch (e: Exception) {
                        Log.e("EasyChange", "BTC error: ${e.message}", e)
                        btc = null
                    }

                } catch (e: Exception) {
                    Log.e("EasyChange", "General error: ${e.message}", e)
                    errorMessage = "Загальна помилка: ${e.message}"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(source) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Кнопки джерел
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "KURS" to "kurs.com.ua",
                    "MONO" to "monobank.ua"
                ).forEach { (code, url) ->
                    Button(
                        onClick = { source = code },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == code) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text(code, fontSize = 13.sp)
                            Text(url, fontSize = 8.sp)
                        }
                    }
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "NBU" to "bank.gov.ua",
                    "INTERBANK" to "minfin.com.ua"
                ).forEach { (code, url) ->
                    Button(
                        onClick = { source = code },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == code) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Text(code, fontSize = 13.sp)
                            Text(url, fontSize = 8.sp, lineHeight = 10.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Час останнього оновлення
        lastUpdate?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        // Поле введення з вибором валюти
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = amount,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                        amount = newValue
                    }
                },
                label = { 
                    val curr = CURRENCIES.find { it.code == baseCurrency }
                    Text("${curr?.flag ?: ""} $baseCurrency")
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !isLoading
            )
            
            Button(
                onClick = { showCurrencyPicker = true },
                modifier = Modifier.height(56.dp)
            ) {
                Text("⚙")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Показ помилки
        errorMessage?.let {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // Індикатор завантаження
        if (isLoading) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Завантаження...", fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        // Конвертовані валюти
        val amountDouble = amount.toDoubleOrNull() ?: 0.0

        if (rates.isNotEmpty()) {
            CURRENCIES.filter { it.code != baseCurrency }.forEach { curr ->
                val value = try {
                    if (amountDouble == 0.0) null
                    else convert(amountDouble, baseCurrency, curr.code, rates)
                } catch (e: Exception) {
                    Log.e("EasyChange", "Conversion error for ${curr.code}: ${e.message}")
                    null
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
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${curr.flag} ${curr.code}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (value != null) {
                                String.format(Locale.US, "%.2f", value)
                            } else {
                                "НЕ ВИЗНАЧЕНО"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (value != null) 
                                MaterialTheme.colorScheme.onSurface 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else if (!isLoading) {
            Text("Дані не завантажено", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))

        // BTC курс
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("BTC → USD", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = btc?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Кнопка оновлення
        Button(
            onClick = { refresh() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            Text(if (isLoading) "Завантаження..." else "Оновити ⟳")
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
                                baseCurrency = curr.code
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
