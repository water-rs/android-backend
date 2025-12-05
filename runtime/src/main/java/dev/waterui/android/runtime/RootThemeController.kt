package dev.waterui.android.runtime

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import androidx.appcompat.app.AppCompatDelegate
import dev.waterui.android.reactive.WatcherCallback
import dev.waterui.android.reactive.WatcherGuard
import dev.waterui.android.reactive.WatcherStructFactory

/**
 * Controls the Activity's appearance based on the root component's environment theme.
 *
 * This mirrors the Apple `RootThemeController` behavior:
 * 1. System theme is injected into WaterUI root environment
 * 2. Rust code can override via `.install(Theme::new().color_scheme(...))`
 * 3. This controller reads from the first non-metadata component's env
 * 4. The color scheme is applied back to the Android Activity/Window
 *
 * This enables Rust code to control whether the app appears in light/dark mode,
 * independent of the system setting.
 */
class RootThemeController private constructor(
    private val env: WuiEnvironment,
    private val view: View
) {
    companion object {
        private const val TAG = "WaterUI.RootTheme"

        /** The singleton controller (one per app) */
        private var instance: RootThemeController? = null

        /** The pending root environment (captured from first non-metadata component) */
        private var pendingRootEnv: WuiEnvironment? = null

        /**
         * Marks an environment as the root content's env (for theme setup).
         * Only captures the first one.
         */
        fun markAsRootContentEnv(env: WuiEnvironment) {
            if (pendingRootEnv == null) {
                pendingRootEnv = env
                // Debug: check what color scheme this env has
                val signalPtr = NativeBindings.waterui_theme_color_scheme(env.raw())
                if (signalPtr != 0L) {
                    val scheme = NativeBindings.waterui_read_computed_color_scheme(signalPtr)
                    Log.d(TAG, "Captured env with color scheme: $scheme (0=Light, 1=Dark)")
                    // Don't drop - we'll use it later
                }
            }
        }

        /**
         * Sets up the root theme controller when the view is added to window.
         */
        fun setup(view: View) {
            if (instance != null) return
            val env = pendingRootEnv ?: return
            instance = RootThemeController(env, view)
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
            pendingRootEnv = null
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
        val signalPtr = NativeBindings.waterui_theme_color_scheme(env.raw())
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
        if (findWindow() == null) {
            Log.d(TAG, "applyToWindow: no window found, view.context=${view.context}")
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
        (view.context as? Activity)?.let { activity ->
            // Use AppCompatDelegate for proper theme switching
            if (activity is androidx.appcompat.app.AppCompatActivity) {
                activity.delegate.localNightMode = nightMode
            } else {
                // Fallback: set system-wide (not ideal but works)
                AppCompatDelegate.setDefaultNightMode(nightMode)
            }
        }
    }

    private fun findWindow(): Window? {
        return (view.context as? Activity)?.window
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
