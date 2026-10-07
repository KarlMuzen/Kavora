package io.github.karlmuzen.phonemanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.karlmuzen.phonemanager.ui.ShizukuPhoneManagerApp
import io.github.karlmuzen.phonemanager.ui.theme.ShizukuPhoneManagerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShizukuPhoneManagerTheme {
                ShizukuPhoneManagerApp()
            }
        }
    }
}
