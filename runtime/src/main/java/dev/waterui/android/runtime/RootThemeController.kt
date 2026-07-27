package dev.waterui.android.runtime

import android.view.View
import androidx.core.view.WindowCompat
import dev.waterui.android.reactive.WuiComputed
import java.io.Closeable

/** Applies the root WaterUI color-scheme signal to the hosting Android window. */
internal class RootThemeController(
    env: WuiEnvironment,
    private val view: View,
    private val updatePlatformPalette: (ColorScheme) -> Unit
) : Closeable {
    private val colorScheme: WuiComputed<Int> = ThemeBridge.colorScheme(env)
    var currentScheme: ColorScheme? = null
        private set

    init {
        colorScheme.observe { value ->
            apply(ColorScheme.fromValue(value))
        }
    }

    private fun apply(scheme: ColorScheme) {
        currentScheme = scheme
        updatePlatformPalette(scheme)
        val activity = view.context.requireActivity()
        val lightSystemBars = scheme == ColorScheme.Light
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
    }

    override fun close() {
        colorScheme.close()
    }
}
