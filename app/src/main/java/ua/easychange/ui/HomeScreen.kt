package ua.easychange.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
fun HomeScreen() {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {}) {
                Text("⟳")
            }
        }
    ) {
        Text("EasyChange")
    }
}
