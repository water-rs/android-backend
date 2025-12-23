package dev.waterui.android.runtime

import android.app.Activity
import android.app.UiModeManager
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatDelegate
import dev.waterui.android.reactive.WatcherGuard
import dev.waterui.android.reactive.WatcherStructFactory

/**
 * Controls the Activity's appearance based on the root component's environment theme.
 *
 * This mirrors the Apple `RootThemeController` behavior:
 * 1. System theme is injected into WaterUI root environment
 * 2. Rust code can override via `.install(Theme::new().color_scheme(...))`
 * 3. This controller reads from the App's environment
 * 4. The color scheme is applied back to the Android Activity/Window
 *
 * This enables Rust code to control whether the app appears in light/dark mode,
 * independent of the system setting.
 */
class RootThemeController private constructor(
    private val envPtr: Long,
    private val view: View
) {
    companion object {
        private const val TAG = "WaterUI.RootTheme"

        /** The singleton controller (one per app) */
        private var instance: RootThemeController? = null

        /**
         * Sets up the root theme controller when the view is added to window.
         * Uses raw envPtr to avoid ownership issues with WuiEnvironment wrapper.
         */
        fun setup(view: View, envPtr: Long) {
            if (instance != null) return
            instance = RootThemeController(envPtr, view)
        }

        /**
         * Called when window becomes available to apply pending theme.
         */
        fun applyPendingTheme() {
            instance?.applyToWindow()
        }

        /**
         * Resets the controller (for hot reload).
         */
        fun reset() {
            instance?.close()
            instance = null
        }
    }

    private var colorSchemeSignalPtr: Long = 0L
    private var watcherGuard: WatcherGuard? = null
    private var currentScheme: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        setupColorSchemeWatcher()
    }

    private fun setupColorSchemeWatcher() {
        val signalPtr = NativeBindings.waterui_theme_color_scheme(envPtr)
        if (signalPtr == 0L) {
            Log.w(TAG, "No color scheme signal found in environment")
            return
        }

        colorSchemeSignalPtr = signalPtr

        // Apply initial value
        val initial = NativeBindings.waterui_read_computed_color_scheme(signalPtr)
        Log.d(TAG, "Initial color scheme: $initial (0=Light, 1=Dark)")
        applyColorScheme(initial)

        // Watch for changes using int watcher (WuiColorScheme is an enum = int)
        val watcher = WatcherStructFactory.int { value, _ ->
            mainHandler.post {
                Log.d(TAG, "Color scheme changed to: $value (0=Light, 1=Dark)")
                applyColorScheme(value)
            }
        }

        val guardHandle = NativeBindings.waterui_watch_computed_color_scheme(signalPtr, watcher)
        if (guardHandle != 0L) {
            watcherGuard = WatcherGuard(guardHandle)
            Log.d(TAG, "Watcher guard created successfully")
        } else {
            Log.w(TAG, "Failed to create watcher guard")
        }
    }

    private fun applyColorScheme(scheme: Int) {
        currentScheme = scheme
        applyToWindow()
    }

    fun applyToWindow() {
        val scheme = currentScheme ?: run {
            Log.d(TAG, "applyToWindow: no current scheme")
            return
        }
        val activity = findActivity()
        if (activity == null) {
            Log.d(TAG, "applyToWindow: no activity found, view.context=${view.context}")
            return
        }

        // Map WuiColorScheme to Android night mode
        // WuiColorScheme_Light = 0, WuiColorScheme_Dark = 1
        val nightMode = when (scheme) {
            0 -> AppCompatDelegate.MODE_NIGHT_NO      // Light
            1 -> AppCompatDelegate.MODE_NIGHT_YES     // Dark
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        Log.d(TAG, "Applying night mode: $nightMode (scheme=$scheme)")

        // Apply to the local activity only, not globally
        // Use AppCompatDelegate for proper theme switching
        if (activity is androidx.appcompat.app.AppCompatActivity) {
            activity.delegate.localNightMode = nightMode
            activity.delegate.applyDayNight()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val uiModeManager = activity.getSystemService(UiModeManager::class.java)
                if (uiModeManager != null) {
                    val appNightMode = when (scheme) {
                        0 -> UiModeManager.MODE_NIGHT_NO
                        1 -> UiModeManager.MODE_NIGHT_YES
                        else -> UiModeManager.MODE_NIGHT_AUTO
                    }
                    uiModeManager.setApplicationNightMode(appNightMode)
                } else {
                    AppCompatDelegate.setDefaultNightMode(nightMode)
                }
            } else {
                AppCompatDelegate.setDefaultNightMode(nightMode)
                activity.recreate()
            }
        }
    }

    private fun findWindow(): Window? {
        return findActivity()?.window
    }

    private fun findActivity(): Activity? {
        var ctx = view.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) {
                return ctx
            }
            ctx = ctx.baseContext
        }
        return null
    }

    private fun close() {
        watcherGuard?.close()
        watcherGuard = null
        if (colorSchemeSignalPtr != 0L) {
            NativeBindings.waterui_drop_computed_color_scheme(colorSchemeSignalPtr)
            colorSchemeSignalPtr = 0L
        }
    }
}
