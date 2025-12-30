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
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.*

/* ---------------- MODELS ---------------- */

data class Fx(val base:String,val quote:String,val buy:Double?,val sell:Double?,val mid:Double)

/* ---------------- API ---------------- */

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
data class NbuDto(val cc:String?, val rate:Double?)

interface BinanceApi {
    @GET("api/v3/ticker/price")
    suspend fun btc(@Query("symbol") s:String="BTCUSDT"): BinanceDto
}
data class BinanceDto(val price:String)

/* ---------------- UTILS ---------------- */

fun MonoDto.code(i:Int)=when(i){
    840->"USD";978->"EUR";985->"PLN";980->"UAH";else->null
}

fun convert(a:Double,from:String,to:String,r:List<Fx>):Double{
    if(from==to) return a
    r.firstOrNull{it.base==from && it.quote==to}?.let{ return a*it.mid }
    r.firstOrNull{it.base==to && it.quote==from}?.let{ return a/it.mid }
    val usd = convert(a,from,"USD",r)
    return convert(usd,"USD",to,r)
}

/* ---------------- ACTIVITY ---------------- */

class MainActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)

        val kurs = Retrofit.Builder().baseUrl("https://kurs.com.ua/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(KursApi::class.java)

        val mono = Retrofit.Builder().baseUrl("https://api.monobank.ua/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(MonoApi::class.java)

        val nbu = Retrofit.Builder().baseUrl("https://bank.gov.ua/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(NbuApi::class.java)

        val binance = Retrofit.Builder().baseUrl("https://api.binance.com/")
            .addConverterFactory(GsonConverterFactory.create()).build().create(BinanceApi::class.java)

        setContent {
            MaterialTheme {
                MainScreen(kurs,mono,nbu,binance)
            }
        }
    }
}

@Composable
fun MainScreen(kurs:KursApi, mono:MonoApi, nbu:NbuApi, binance:BinanceApi){

    var source by remember { mutableStateOf("KURS") }
    var amount by remember { mutableStateOf("100") }
    var rates by remember { mutableStateOf<List<Fx>>(emptyList()) }
    var btc by remember { mutableStateOf<Double?>(null) }
    var lastUpdate by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    fun refresh(){
        scope.launch {
            withContext(Dispatchers.IO){
                try{
                    rates = when(source){
                        "KURS","INTERBANK" -> kurs.load().data.map {
                            Fx(it.base,it.quote,it.buy,it.sell,(it.buy+it.sell)/2)
                        }
                        "MONO" -> mono.load().mapNotNull{
                            val b=it.code(it.currencyCodeA)
                            val q=it.code(it.currencyCodeB)
                            if(b!=null && q=="UAH" && it.rateCross!=null)
                                Fx(b,q,null,null,it.rateCross)
                            else null
                        }
                        else -> nbu.load().filter{it.cc!=null && it.rate!=null}.map{
                            Fx(it.cc!!,"UAH",null,null,it.rate!!)
                        }
                    }
                    btc = binance.btc().price.toDouble()
                    lastUpdate = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
                }catch(_:Exception){}
            }
        }
    }

    LaunchedEffect(source){ refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {

        /* ----------- SOURCE BUTTONS ----------- */

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {

            listOf(
                Triple("KURS","KURS","kurs.com.ua"),
                Triple("MONO","MONO","monobank.ua"),
                Triple("NBU","NBU","bank.gov.ua"),
                Triple("INTERBANK","iBank","minfin.com.ua")
            ).forEach { (code,title,sub) ->

                Button(
                    onClick = { source=code },
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(source==code)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.secondary
                    )
                ){
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally){
                        Text(title, fontSize = 14.sp)
                        Text(sub, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it },
            label = { Text("USD") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))
        Text("Оновлено: $lastUpdate", fontSize = 12.sp)

        Spacer(Modifier.height(12.dp))

        listOf("EUR","PLN","UAH").forEach{
            val v = if(rates.isEmpty())0.0 else convert(amount.toDoubleOrNull()?:0.0,"USD",it,rates)
            Text("$it  ${String.format(Locale.US,"%.2f",v)}", fontSize = 18.sp)
        }

        Spacer(Modifier.height(12.dp))
        Text("BTC → USD  ${btc ?: "--"}", fontSize = 16.sp)

        Spacer(Modifier.height(12.dp))

        Button(onClick={refresh()}, modifier = Modifier.fillMaxWidth()){
            Text("Оновити ⟳")
        }
    }
}
