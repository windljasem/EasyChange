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
import java.util.*

// ------------------ MODELS ------------------
data class Fx(val base: String, val quote: String, val buy: Double?, val sell: Double?, val mid: Double)

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

// ---- Minfin (INTERBANK) ----
interface MinfinApi {
    @GET("api/currency/rates/")
    suspend fun load(): List<MinfinDto>
}
data class MinfinDto(
    val currency: String?,
    val bid: Double?,
    val ask: Double?
)

interface BinanceApi {
    @GET("api/v3/ticker/price")
    suspend fun btc(@Query("symbol") s: String = "BTCUSDT"): BinanceDto
}
data class BinanceDto(val price: String)

// ------------------ UTILS ------------------
fun MonoDto.code(i: Int) = when (i) {
    840 -> "USD"
    978 -> "EUR"
    985 -> "PLN"
    980 -> "UAH"
    else -> null
}

fun convert(a: Double, from: String, to: String, r: List<Fx>): Double {
    if (from == to) return a

    r.firstOrNull { it.base == from && it.quote == to }?.let { return a * it.mid }
    r.firstOrNull { it.base == to && it.quote == from }?.let { return a / it.mid }

    val toUsd = r.firstOrNull { it.base == from && it.quote == "USD" }
        ?: r.firstOrNull { it.quote == from && it.base == "USD" }
    val fromUsd = r.firstOrNull { it.base == "USD" && it.quote == to }
        ?: r.firstOrNull { it.quote == "USD" && it.base == to }

    if (toUsd != null && fromUsd != null) {
        val usdAmount = if (toUsd.base == from) a * toUsd.mid else a / toUsd.mid
        return if (fromUsd.base == "USD") usdAmount * fromUsd.mid else usdAmount / fromUsd.mid
    }
    return 0.0
}

fun nowStr(): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())

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
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
    var amount by remember { mutableStateOf("100") }
    var rates by remember { mutableStateOf<List<Fx>>(emptyList()) }
    var btc by remember { mutableStateOf<Double?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var lastUpdate by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            isLoading = true
            withContext(Dispatchers.IO) {
                try {
                    val newRates = when (source) {
                        "KURS" -> {
                            kurs.load().data.map {
                                Fx(it.base, it.quote, it.buy, it.sell, (it.buy + it.sell) / 2)
                            }
                        }
                        "MONO" -> {
                            mono.load().mapNotNull {
                                val b = it.code(it.currencyCodeA)
                                val q = it.code(it.currencyCodeB)
                                if (b != null && q == "UAH") {
                                    val mid = when {
                                        it.rateCross != null && it.rateCross > 0 -> it.rateCross
                                        it.rateBuy != null && it.rateSell != null -> (it.rateBuy + it.rateSell) / 2
                                        it.rateBuy != null -> it.rateBuy
                                        it.rateSell != null -> it.rateSell
                                        else -> null
                                    }
                                    mid?.let { Fx(b, q, it.rateBuy, it.rateSell, it) }
                                } else null
                            }
                        }
                        "NBU" -> {
                            nbu.load()
                                .filter { it.cc != null && it.rate != null }
                                .map { Fx(it.cc!!, "UAH", null, null, it.rate!!) }
                        }
                        "INTERBANK" -> {
                            // Minfin: міжбанк
                            minfin.load()
                                .filter { it.currency in listOf("USD", "EUR", "PLN") && it.bid != null && it.ask != null }
                                .map {
                                    val mid = (it.bid!! + it.ask!!) / 2
                                    Fx(it.currency!!, "UAH", it.bid, it.ask, mid)
                                }
                        }
                        else -> emptyList()
                    }

                    if (newRates.isNotEmpty()) {
                        rates = newRates
                        lastUpdate = nowStr()
                    }

                    try {
                        btc = binance.btc().price.toDoubleOrNull()
                    } catch (_: Exception) { /* тихо */ }

                } catch (e: Exception) {
                    Log.e("EasyChange", "Source error", e)
                    // НІЯКИХ повідомлень у UI — просто залишаємо старі дані та lastUpdate
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(source) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        // ---- SOURCE BUTTONS (4 рівні) ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "KURS" to "kurs.com.ua",
                "MONO" to "monobank.ua",
                "NBU" to "bank.gov.ua",
                "INTERBANK" to "minfin.com.ua"
            ).forEach { (code, url) ->
                Button(
                    onClick = { source = code },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (source == code)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Column {
                        Text(if (code == "INTERBANK") "iBank" else code, fontSize = 14.sp)
                        Text(url, fontSize = 8.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        lastUpdate?.let {
            Text("Курс оновлено $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = amount,
            onValueChange = { if (it.isEmpty() || it.matches(Regex("^\\d*\\.?\\d*$"))) amount = it },
            label = { Text("USD") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !isLoading
        )

        Spacer(Modifier.height(12.dp))

        val amountDouble = amount.toDoubleOrNull() ?: 0.0

        if (rates.isNotEmpty()) {
            listOf("EUR", "PLN", "UAH").forEach { code ->
                val v = if (amountDouble == 0.0) 0.0 else convert(amountDouble, "USD", code, rates)
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.SpaceBetween) {
                        Text(code, style = MaterialTheme.typography.titleMedium)
                        Text(String.format(Locale.US, "%.2f", v), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } else if (!isLoading) {
            Text("Немає даних", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.SpaceBetween) {
                Text("BTC → USD", style = MaterialTheme.typography.titleMedium)
                Text(btc?.let { String.format(Locale.US, "%.2f", it) } ?: "--", style = MaterialTheme.typography.bodyLarge)
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
            Text(if (isLoading) "Завантаження..." else "Оновити ⟳")
        }
    }
}
