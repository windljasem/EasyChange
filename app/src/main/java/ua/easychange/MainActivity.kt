package ua.easychange

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ua.easychange.ui.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HomeScreen()
        }
    }
}
