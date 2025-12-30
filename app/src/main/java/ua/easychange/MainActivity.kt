package ua.easychange

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

interface PrivatBankApi {
    @GET("obmin-valiut")
    suspend fun loadHtml(): String
}

interface PumbApi {
    @GET("")
    suspend fun loadHtml(): String
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
    CurrencyInfo("CHF", "🇨🇭", "Франк"),
    CurrencyInfo("CZK", "🇨🇿", "Крона"),
    CurrencyInfo("CAD", "🇨🇦", "Дол. Канади"),
    CurrencyInfo("CNY", "🇨🇳", "Юань"),
    CurrencyInfo("BTC", "₿", "Bitcoin")
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

fun parsePrivatHtml(html: String): List<Fx> {
    val rates = mutableListOf<Fx>()
    
    try {
        // Шукаємо курси валют у таблиці
        val currencyRegex = """data-currency="([A-Z]{3})"[\s\S]*?<td[^>]*class="[^"]*buy[^"]*"[^>]*>([\d.]+)</td>[\s\S]*?<td[^>]*class="[^"]*sell[^"]*"[^>]*>([\d.]+)</td>""".toRegex()
        
        currencyRegex.findAll(html).forEach { match ->
            val code = match.groupValues[1]
            val buy = match.groupValues[2].toDoubleOrNull()
            val sell = match.groupValues[3].toDoubleOrNull()
            
            if (buy != null && sell != null && buy > 0 && sell > 0) {
                rates.add(Fx(code, "UAH", buy, sell, (buy + sell) / 2))
                Log.d("EasyChange", "Privat: $code -> UAH = $buy/$sell")
            }
        }
        
        // Альтернативний regex якщо перший не спрацював
        if (rates.isEmpty()) {
            val altRegex = """<tr[^>]*data-currency="([A-Z]{3})"[^>]*>[\s\S]*?<td[^>]*>([\d.]+)</td>[\s\S]*?<td[^>]*>([\d.]+)</td>""".toRegex()
            
            altRegex.findAll(html).forEach { match ->
                val code = match.groupValues[1]
                val buy = match.groupValues[2].toDoubleOrNull()
                val sell = match.groupValues[3].toDoubleOrNull()
                
                if (buy != null && sell != null && buy > 0 && sell > 0) {
                    rates.add(Fx(code, "UAH", buy, sell, (buy + sell) / 2))
                    Log.d("EasyChange", "Privat (alt): $code -> UAH = $buy/$sell")
                }
            }
        }
    } catch (e: Exception) {
        Log.e("EasyChange", "Privat parsing error: ${e.message}")
    }
    
    return rates
}

fun parsePumbHtml(html: String): List<Fx> {
    val rates = mutableListOf<Fx>()
    
    try {
        // Шукаємо курси валют на сайті PUMB
        val currencyRegex = """data-code="([A-Z]{3})"[\s\S]*?data-buy="([\d.]+)"[\s\S]*?data-sell="([\d.]+)"""".toRegex()
        
        currencyRegex.findAll(html).forEach { match ->
            val code = match.groupValues[1]
            val buy = match.groupValues[2].toDoubleOrNull()
            val sell = match.groupValues[3].toDoubleOrNull()
            
            if (buy != null && sell != null && buy > 0 && sell > 0) {
                rates.add(Fx(code, "UAH", buy, sell, (buy + sell) / 2))
                Log.d("EasyChange", "PUMB: $code -> UAH = $buy/$sell")
            }
        }
        
        // Альтернативний regex - таблиця
        if (rates.isEmpty()) {
            val tableRegex = """<tr[^>]*>[\s\S]*?<td[^>]*>([A-Z]{3})</td>[\s\S]*?<td[^>]*>([\d.]+)</td>[\s\S]*?<td[^>]*>([\d.]+)</td>""".toRegex()
            
            tableRegex.findAll(html).forEach { match ->
                val code = match.groupValues[1]
                val buy = match.groupValues[2].toDoubleOrNull()
                val sell = match.groupValues[3].toDoubleOrNull()
                
                if (buy != null && sell != null && buy > 0 && sell > 0) {
                    rates.add(Fx(code, "UAH", buy, sell, (buy + sell) / 2))
                    Log.d("EasyChange", "PUMB (table): $code -> UAH = $buy/$sell")
                }
            }
        }
    } catch (e: Exception) {
        Log.e("EasyChange", "PUMB parsing error: ${e.message}")
    }
    
    return rates
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
    val fromRate = rates.firstOrNull { it.base == from && it.quote == "UAH" }
    val toRate = rates.firstOrNull { it.base == to && it.quote == "UAH" }
    
    if (fromRate != null && toRate != null) {
        val uahAmount = amount * fromRate.mid
        return uahAmount / toRate.mid
    }
    
    // Через USD
    val fromToUsd = rates.firstOrNull { it.base == from && it.quote == "USD" }
        ?: rates.firstOrNull { it.base == "USD" && it.quote == from }
    val toFromUsd = rates.firstOrNull { it.base == to && it.quote == "USD" }
        ?: rates.firstOrNull { it.base == "USD" && it.quote == to }
    
    if (fromToUsd != null && toFromUsd != null) {
        val usdAmount = if (fromToUsd.base == from) {
            amount * fromToUsd.mid
        } else {
            amount / fromToUsd.mid
        }
        
        return if (toFromUsd.base == to) {
            usdAmount / toFromUsd.mid
        } else {
            usdAmount * toFromUsd.mid
        }
    }
    
    return null
}

// ------------------ MAIN ACTIVITY ------------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        val privat = Retrofit.Builder()
            .baseUrl("https://privatbank.ua/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PrivatBankApi::class.java)

        val pumb = Retrofit.Builder()
            .baseUrl("https://www.pumb.ua/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PumbApi::class.java)

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
                    MainScreen(mono, nbu, privat, pumb, binance)
                }
            }
        }
    }
}

// ------------------ MAIN SCREEN ------------------
@Composable
fun MainScreen(
    mono: MonoApi,
    nbu: NbuApi,
    privat: PrivatBankApi,
    pumb: PumbApi,
    binance: BinanceApi
) {
    var source by remember { mutableStateOf("MONO") }
    var baseCurrency by remember { mutableStateOf("USD") }
    var amount by remember { mutableStateOf("1") }
    var rates by remember { mutableStateOf<List<Fx>>(emptyList()) }
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
                    Log.d("EasyChange", "Loading from: $source")
                    
                    rates = when (source) {
                        "PUMB" -> {
                            try {
                                val html = pumb.loadHtml()
                                Log.d("EasyChange", "PUMB: ${html.length} chars")
                                val parsed = parsePumbHtml(html)
                                if (parsed.isEmpty()) {
                                    errorMessage = "PUMB: не знайдено курсів"
                                }
                                parsed
                            } catch (e: Exception) {
                                Log.e("EasyChange", "PUMB error: ${e.message}", e)
                                errorMessage = "PUMB: ${e.message}"
                                emptyList()
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
                                emptyList()
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
                                emptyList()
                            }
                        }

                        "PRIVATBANK" -> {
                            try {
                                val html = privat.loadHtml()
                                Log.d("EasyChange", "PrivatBank: ${html.length} chars")
                                val parsed = parsePrivatHtml(html)
                                if (parsed.isEmpty()) {
                                    errorMessage = "PrivatBank: не знайдено курсів"
                                }
                                parsed
                            } catch (e: Exception) {
                                Log.e("EasyChange", "PrivatBank error: ${e.message}", e)
                                errorMessage = "PrivatBank: ${e.message}"
                                emptyList()
                            }
                        }

                        else -> emptyList()
                    }

                    // Додаємо BTC
                    try {
                        val btcPrice = binance.btc().price.toDoubleOrNull()
                        if (btcPrice != null && btcPrice > 0) {
                            rates = rates + Fx("BTC", "USD", null, null, btcPrice)
                            Log.d("EasyChange", "BTC: $btcPrice USD")
                        }
                    } catch (e: Exception) {
                        Log.e("EasyChange", "BTC error: ${e.message}")
                    }

                    if (rates.isNotEmpty()) {
                        val format = SimpleDateFormat("dd.MM.yyyy 'о' HH:mm", Locale("uk"))
                        lastUpdate = "Курс оновлено ${format.format(Date())}"
                    }

                } catch (e: Exception) {
                    Log.e("EasyChange", "Error: ${e.message}", e)
                    errorMessage = "Помилка: ${e.message}"
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
            // Кнопки джерел
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { source = "PUMB" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "PUMB") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("PUMB", fontSize = 13.sp)
                            Text("pumb.ua", fontSize = 8.sp)
                        }
                    }
                    
                    Button(
                        onClick = { source = "MONO" },
                        modifier = Modifier.weight(1f),
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
                        onClick = { source = "PRIVATBANK" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "PRIVATBANK") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("PrivatBank", fontSize = 12.sp)
                            Text("privatbank.ua", fontSize = 8.sp)
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
                                "${curr.flag} ${curr.code}",
                                style = MaterialTheme.typography.titleMedium
                            )
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
                    }
                }
            } else if (!isLoading) {
                Text("Дані не завантажено", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
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
