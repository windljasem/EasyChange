package ua.easychange

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

// ---------- MODELS ----------
data class Fx(val base:String,val quote:String,val buy:Double?,val sell:Double?,val mid:Double)

// ---------- API ----------
interface KursApi {
    @GET("api/market/exchange-rates")
    suspend fun load(): KursResponse
}
data class KursResponse(val data:List<KursDto>)
data class KursDto(val base:String,val quote:String,val buy:Double,val sell:Double)

interface MonoApi {
    @GET("bank/currency")
    suspend fun load(): List<MonoDto>
}
data class MonoDto(
    val currencyCodeA:Int,
    val currencyCodeB:Int,
    val rateBuy:Double?,
    val rateSell:Double?,
    val rateCross:Double?
)

interface NbuApi {
    @GET("NBUStatService/v1/statdirectory/exchange?json")
    suspend fun load(): List<NbuDto>
}
data class NbuDto(val cc:String,val rate:Double)

interface BinanceApi {
    @GET("api/v3/ticker/price")
    suspend fun btc(@Query("symbol") s:String="BTCUSDT"): BinanceDto
}
data class BinanceDto(val price:String)

// ---------- UTILS ----------
fun MonoDto.code(i:Int)=when(i){
    840->"USD";978->"EUR";985->"PLN";980->"UAH";else->null
}

fun convert(a:Double,from:String,to:String,r:List<Fx>):Double{
    if(from==to)return a
    r.firstOrNull{it.base==from && it.quote==to}?.let{ return a*it.mid }
    r.firstOrNull{it.base==to && it.quote==from}?.let{ return a/it.mid }
    val usd=convert(a,from,"USD",r)
    return convert(usd,"USD",to,r)
}

// ---------- ACTIVITY ----------
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val kurs = Retrofit.Builder().baseUrl("https://kurs.com.ua/")
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(KursApi::class.java)

        val mono = Retrofit.Builder().baseUrl("https://api.monobank.ua/")
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(MonoApi::class.java)

        val nbu = Retrofit.Builder().baseUrl("https://bank.gov.ua/")
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(NbuApi::class.java)

        val binance = Retrofit.Builder().baseUrl("https://api.binance.com/")
            .addConverterFactory(GsonConverterFactory.create()).build()
            .create(BinanceApi::class.java)

        setContent {
            var source by remember { mutableStateOf("KURS") }
            var amount by remember { mutableStateOf("100") }
            var rates by remember { mutableStateOf<List<Fx>>(emptyList()) }
            var btc by remember { mutableStateOf<Double?>(null) }
            val scope = rememberCoroutineScope()

            fun refresh(){
                scope.launch {
                    rates = when(source){
                        "KURS" -> kurs.load().data.map{
                            Fx(it.base,it.quote,it.buy,it.sell,(it.buy+it.sell)/2)
                        }
                        "MONO" -> mono.load().mapNotNull{
                            val b=it.code(it.currencyCodeA)
                            val q=it.code(it.currencyCodeB)
                            if(b!=null && q=="UAH")
                                Fx(b,q,it.rateBuy,it.rateSell,
                                    it.rateCross?:((it.rateBuy!!+it.rateSell!!)/2))
                            else null
                        }
                        else -> nbu.load().map{
                            Fx(it.cc,"UAH",null,null,it.rate)
                        }
                    }
                    btc = binance.btc().price.toDouble()
                }
            }

            LaunchedEffect(source){ refresh() }

            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {

                Row {
                    listOf("KURS","MONO","NBU").forEach {
                        Button(onClick={source=it}){ Text(it) }
                    }
                }

                OutlinedTextField(amount,{amount=it},label={Text("USD")})

                listOf("EUR","PLN","UAH").forEach {
                    val v=convert(amount.toDoubleOrNull()?:0.0,"USD",it,rates)
                    Text("$it  ${"%.2f".format(v)}")
                }

                Spacer(Modifier.height(8.dp))
                Text("BTC → USD  ${btc ?: "--"}")
                Button(onClick={refresh()}){ Text("⟳") }
            }
        }
    }
}
