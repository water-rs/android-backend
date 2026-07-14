package dev.waterui.android.runtime

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import dev.waterui.android.reactive.WuiComputed
import java.io.Closeable

/** Applies the root WaterUI color-scheme signal to the hosting Android window. */
internal class RootThemeController(
    env: WuiEnvironment,
    private val view: View
) : Closeable {
    private val colorScheme: WuiComputed<Int> = ThemeBridge.colorScheme(env)

    init {
        colorScheme.observe { value ->
            apply(ColorScheme.fromValue(value))
        }
    }

    private fun apply(scheme: ColorScheme) {
        val activity = view.context.requireActivity()
        val appCompatActivity = activity as? AppCompatActivity
            ?: error("WaterUI color-scheme control requires an AppCompatActivity host")
        val nightMode = when (scheme) {
            ColorScheme.Light -> AppCompatDelegate.MODE_NIGHT_NO
            ColorScheme.Dark -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (appCompatActivity.delegate.localNightMode != nightMode) {
            appCompatActivity.delegate.localNightMode = nightMode
        }

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
