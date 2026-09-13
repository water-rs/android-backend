package dev.waterui.android.reference

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier

/**
 * Compose + Material 3 reference host for the Android parity E2E.
 *
 * Renders the twin registered for the example named by the `E2EExample`
 * intent extra — the same contract the SwiftUI reference host uses on the
 * Apple side. The shard driver launches this activity per example, captures
 * the settled frame, and compares it against the WaterUI render.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val example = intent.getStringExtra(EXTRA_EXAMPLE).orEmpty()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val twin = twinFor(example)
                    if (twin != null) twin() else Text("No twin registered for example '$example'")
                }
            }
        }
    }

    companion object {
        const val EXTRA_EXAMPLE = "E2EExample"
        const val PACKAGE = "dev.waterui.android.reference"
    }
}
