package ua.easychange

import android.os.Bundle
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
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ---------------- API ----------------
interface KursApi {
    @GET("api/market/exchange-rates")
    suspend fun load(): KursResponse
}
data class KursResponse(val data: List<KursDto>)
data class KursDto(val base: String, val quote: String, val buy: Double, val sell: Double)

// ---------------- HTTP ----------------
fun http(filesDir: File): OkHttpClient {
    val cache = Cache(File(filesDir, "http"), 10L * 1024 * 1024)
    val headers = Interceptor { c ->
        c.proceed(
            c.request().newBuilder()
                .header("User-Agent", "EasyChange")
                .header("Accept", "application/json")
                .build()
        )
    }
    return OkHttpClient.Builder()
        .cache(cache)
        .addInterceptor(headers)
        .build()
}

// ---------------- ACTIVITY ----------------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val api = Retrofit.Builder()
            .baseUrl("https://kurs.com.ua/")
            .client(http(filesDir))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(KursApi::class.java)

        setContent { MainScreen(api) }
    }
}

// ---------------- UI ----------------
@Composable
fun MainScreen(api: KursApi) {

    var source by remember { mutableStateOf("KURS") }
    var usdRate by remember { mutableStateOf<Double?>(null) }
    var lastUpdated by remember { mutableStateOf<Long?>(null) }
    var amount by remember { mutableStateOf("100") }
    var loading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) {
                try {
                    val data = api.load().data
                    val usd = data.firstOrNull { it.base == "USD" && it.quote == "UAH" }
                    if (usd != null) {
                        usdRate = (usd.buy + usd.sell) / 2
                        lastUpdated = System.currentTimeMillis()
                    }
                } catch (_: Exception) {
                    // не чистимо дані
                } finally {
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(source) { refresh() }

    val uah = (amount.toDoubleOrNull() ?: 0.0) * (usdRate ?: 0.0)

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        // Кнопки джерел
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("KURS","MONO","NBU","INTERBANK").forEach {
                Button(
                    onClick = { source = it },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (source == it)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text(it, fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("USD") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("UAH")
                Text(String.format(Locale.US, "%.2f", uah))
            }
        }

        Spacer(Modifier.height(12.dp))

        lastUpdated?.let {
            val t = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(Date(it))
            Text("Курс оновлено $t", fontSize = 12.sp)
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { refresh() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) {
            Text(if (loading) "Оновлення…" else "Оновити ⟳")
        }
    }
}
