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
        val usd = if (toUsd.base == from) a * toUsd.mid else a / toUsd.mid
        return if (fromUsd.base == "USD") usd * fromUsd.mid else usd / fromUsd.mid
    }

    return 0.0
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

        val binance = Retrofit.Builder()
            .baseUrl("https://api.binance.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BinanceApi::class.java)

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MainScreen(kurs, mono, nbu, binance)
                }
            }
        }
    }
}

@Composable
fun MainScreen(kurs: KursApi, mono: MonoApi, nbu: NbuApi, binance: BinanceApi) {
    var source by remember { mutableStateOf("KURS") }
    var amount by remember { mutableStateOf("100") }
    var rates by remember { mutableStateOf<List<Fx>>(emptyList()) }
    var btc by remember { mutableStateOf<Double?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastUpdate by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            isLoading = true
            error = null

            withContext(Dispatchers.IO) {
                try {
                    rates = when (source) {
                        "KURS", "INTERBANK" -> kurs.load().data.map {
                            Fx(it.base, it.quote, it.buy, it.sell, (it.buy + it.sell) / 2)
                        }
                        "MONO" -> mono.load().mapNotNull {
                            val b = it.code(it.currencyCodeA)
                            val q = it.code(it.currencyCodeB)
                            if (b != null && q == "UAH") {
                                val m = it.rateCross ?: it.rateBuy ?: it.rateSell
                                if (m != null) Fx(b, q, it.rateBuy, it.rateSell, m) else null
                            } else null
                        }
                        "NBU" -> nbu.load().filter { it.cc != null && it.rate != null }
                            .map { Fx(it.cc!!, "UAH", null, null, it.rate!!) }
                        else -> emptyList()
                    }

                    btc = binance.btc().price.toDoubleOrNull()
                    lastUpdate = System.currentTimeMillis()

                } catch (e: Exception) {
                    if (rates.isEmpty()) error = "Джерело тимчасово недоступне"
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(source) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("KURS","MONO","NBU","INTERBANK").forEach {
                Button(onClick = { source = it }, modifier = Modifier.weight(1f)) {
                    Text(it)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(amount, { amount = it }, label = { Text("USD") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))

        lastUpdate?.let {
            Text("Оновлено: " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(it)), fontSize = 11.sp)
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Spacer(Modifier.height(12.dp))

        listOf("EUR","PLN","UAH").forEach {
            val v = convert(amount.toDoubleOrNull() ?: 0.0, "USD", it, rates)
            Text("$it  ${String.format(Locale.US,"%.2f",v)}")
        }

        Spacer(Modifier.height(12.dp))
        Text("BTC → USD  ${btc ?: "--"}")

        Spacer(Modifier.height(16.dp))

        Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
            Text("Оновити ⟳")
        }
    }
}
