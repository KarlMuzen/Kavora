package io.github.karlmuzen.kavora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.karlmuzen.kavora.ui.KavoraApp
import io.github.karlmuzen.kavora.ui.theme.KavoraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KavoraTheme {
                KavoraApp()
            }
        }
    }
}
