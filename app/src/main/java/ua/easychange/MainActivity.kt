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

interface CnbApi {
    @GET("en/financial-markets/foreign-exchange-market/central-bank-exchange-rate-fixing/central-bank-exchange-rate-fixing/daily.txt")
    @retrofit2.http.Streaming
    suspend fun load(): okhttp3.ResponseBody
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

fun parseCnbTxt(txt: String): List<Fx> {
    val rates = mutableListOf<Fx>()
    
    try {
        val lines = txt.split("\n")
        
        // Пропускаємо перші 2 рядки (дата і заголовок)
        lines.drop(2).forEach { line ->
            val parts = line.split("|")
            if (parts.size >= 5) {
                val code = parts[3].trim()
                val amount = parts[2].trim().toDoubleOrNull() ?: 1.0
                val rate = parts[4].trim().toDoubleOrNull()
                
                if (rate != null && rate > 0) {
                    // CNB дає курс CZK до валюти, тому перераховуємо
                    val actualRate = rate / amount
                    rates.add(Fx(code, "CZK", null, null, actualRate))
                    Log.d("EasyChange", "CNB: $code -> CZK = $actualRate")
                }
            }
        }
    } catch (e: Exception) {
        Log.e("EasyChange", "CNB parsing error: ${e.message}")
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
    val fromUah = rates.firstOrNull { it.base == from && it.quote == "UAH" }
    val toUah = rates.firstOrNull { it.base == to && it.quote == "UAH" }
    
    if (fromUah != null && toUah != null) {
        val uahAmount = amount * fromUah.mid
        return uahAmount / toUah.mid
    }
    
    // Через PLN (для NBP)
    val fromPln = rates.firstOrNull { it.base == from && it.quote == "PLN" }
    val toPln = rates.firstOrNull { it.base == to && it.quote == "PLN" }
    
    if (fromPln != null && toPln != null) {
        val plnAmount = amount * fromPln.mid
        return plnAmount / toPln.mid
    }
    
    // Через CZK (для CNB)
    val fromCzk = rates.firstOrNull { it.base == from && it.quote == "CZK" }
    val toCzk = rates.firstOrNull { it.base == to && it.quote == "CZK" }
    
    if (fromCzk != null && toCzk != null) {
        val czkAmount = amount * fromCzk.mid
        return czkAmount / toCzk.mid
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

        val cnb = Retrofit.Builder()
            .baseUrl("https://www.cnb.cz/")
            .build()
            .create(CnbApi::class.java)

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
                    MainScreen(mono, nbu, nbp, cnb, binance)
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
    nbp: NbpApi,
    cnb: CnbApi,
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
                                    emptyList()
                                }
                            } catch (e: Exception) {
                                Log.e("EasyChange", "NBP error: ${e.message}", e)
                                errorMessage = "NBP: ${e.message}"
                                emptyList()
                            }
                        }

                        "CNB" -> {
                            try {
                                val response = cnb.load()
                                val txt = response.string()
                                Log.d("EasyChange", "CNB: ${txt.length} chars")
                                val parsed = parseCnbTxt(txt)
                                if (parsed.isEmpty()) {
                                    errorMessage = "CNB: не знайдено курсів"
                                }
                                parsed
                            } catch (e: Exception) {
                                Log.e("EasyChange", "CNB error: ${e.message}", e)
                                errorMessage = "CNB: ${e.message}"
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
                        onClick = { source = "MONO" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                    
                    Button(
                        onClick = { source = "NBU" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { source = "NBP" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                    
                    Button(
                        onClick = { source = "CNB" },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (source == "CNB") 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("CNB", fontSize = 13.sp)
                            Text("cnb.cz", fontSize = 8.sp)
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
            // Кроскурси
            if (rates.isNotEmpty()) {
                Text(
                    "Кроскурси:",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                val crossPairs = listOf(
                    "PLN" to "UAH",
                    "UAH" to "USD",
                    "USD" to "EUR"
                )
                
                crossPairs.forEach { (from, to) ->
                    val direct = convert(1.0, from, to, rates)
                    val reverse = convert(1.0, to, from, rates)
                    
                    if (direct != null || reverse != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            ) {
                                if (direct != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("1 $from =", fontSize = 13.sp)
                                        Text(
                                            String.format(Locale.US, "%.4f", direct) + " $to",
                                            fontSize = 13.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                        )
                                    }
                                }
                                if (reverse != null) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("1 $to =", fontSize = 13.sp)
                                        Text(
                                            String.format(Locale.US, "%.4f", reverse) + " $from",
                                            fontSize = 13.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                // Кнопка оновлення
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Button(
                        onClick = { refresh() },
                        enabled = !isLoading,
                        modifier = Modifier.width(200.dp)
                    ) {
                        Text(if (isLoading) "Завантаження..." else "Оновити ⟳", fontSize = 13.sp)
                    }
                }
                
                Spacer(Modifier.height(12.dp))
            }

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
