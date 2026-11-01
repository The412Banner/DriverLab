package com.driverlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driverlab.ui.DriverLabRoot
import com.driverlab.ui.theme.DriverLabTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
            DriverLabTheme(themeMode = settings.theme, dynamicColor = settings.dynamicColor) {
                DriverLabRoot()
            }
        }
    }
}
