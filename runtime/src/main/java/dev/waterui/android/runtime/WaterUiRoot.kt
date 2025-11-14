package dev.waterui.android.runtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Legacy entry point retained for backwards compatibility. Prefer calling [WaterUIApplication]
 * directly so you can supply a custom render registry if needed.
 */
@Composable
fun WaterUiRoot(@Suppress("UNUSED_PARAMETER") modifier: Modifier = Modifier) {
    WaterUIApplication()
}
