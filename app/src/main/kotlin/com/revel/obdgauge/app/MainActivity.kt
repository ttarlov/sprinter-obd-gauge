package com.revel.obdgauge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host, landscape-locked (see the manifest) for the dash mount.
 *
 * Sprint 0 renders a placeholder; the real gauge dashboard (OBD-10/11) replaces
 * [GaugePlaceholder] in Sprint 1 without touching this shell.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var scaffoldGreeting: ScaffoldGreeting

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ObdGaugeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GaugePlaceholder(message = scaffoldGreeting.message)
                }
            }
        }
    }
}

@Composable
private fun GaugePlaceholder(message: String) {
    Text(text = message, style = MaterialTheme.typography.headlineSmall)
}

@Preview(showBackground = true)
@Composable
private fun GaugePlaceholderPreview() {
    ObdGaugeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GaugePlaceholder(message = "Sprinter OBD Gauge — scaffold online")
        }
    }
}
