package dev.waterui.android.runtime

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import dev.waterui.android.components.WebViewManager
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.ColorSlot
import dev.waterui.android.runtime.ReactiveColorSignal

/**
 * Root view that owns the WaterUI environment and inflates the Rust-driven
 * view hierarchy into Android's View system.
 *
 * Layout decisions are made by the Rust layout engine - this view only
 * measures and places children according to Rust's instructions.
 */
class WaterUiRootView @JvmOverloads constructor(
    baseContext: Context,
    attrs: AttributeSet? = null
) : FrameLayout(createMaterialContext(baseContext), attrs) {

    private var registry: RenderRegistry = RenderRegistry.default()
    private var environment: WuiEnvironment? = null
    private var app: AppStruct? = null
    private var backgroundTheme: WuiComputed<ResolvedColorStruct>? = null
    private var colorSchemeSignalPtr: Long = 0L
    private var materialThemeInstalled = false

    fun setRenderRegistry(renderRegistry: RenderRegistry) {
        registry = renderRegistry
        if (environment != null) {
            renderRoot()
        }
    }

    fun getRenderRegistry(): RenderRegistry = registry

    /** Forces the root tree to be rebuilt. Useful for hot reload flows. */
    fun reload() {
        if (environment != null) {
            renderRoot()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (environment == null) {
            WebViewManager.init(context)
            environment = WuiEnvironment.create()
        }
        renderRoot()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Ensure this view reports correct measured size when embedded in native layouts.
        // This mirrors iOS: the hosting view sizes itself based on content unless
        // the parent imposes exact constraints.
        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(
                View.resolveSize(0, widthMeasureSpec),
                View.resolveSize(0, heightMeasureSpec)
            )
            return
        }

        val availableWidth = (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (View.MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)

        val childWidthSpec = when (View.MeasureSpec.getMode(widthMeasureSpec)) {
            View.MeasureSpec.EXACTLY -> View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.EXACTLY)
            View.MeasureSpec.AT_MOST -> View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST)
            else -> View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }

        val childHeightSpec = when (View.MeasureSpec.getMode(heightMeasureSpec)) {
            View.MeasureSpec.EXACTLY -> View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.EXACTLY)
            View.MeasureSpec.AT_MOST -> View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST)
            else -> View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        }

        child.measure(childWidthSpec, childHeightSpec)

        val desiredWidth = child.measuredWidth + paddingLeft + paddingRight
        val desiredHeight = child.measuredHeight + paddingTop + paddingBottom

        setMeasuredDimension(
            View.resolveSize(desiredWidth, widthMeasureSpec),
            View.resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeAllViews()

        // 0. Reset the root theme controller first
        RootThemeController.reset()

        // 1. Close the theme watcher first (depends on environment)
        backgroundTheme?.close()
        backgroundTheme = null

        // 2. Drop the main window content view (depends on environment/runtime)
        app?.let { appStruct ->
            val mainWindow = appStruct.mainWindow()
            if (mainWindow.contentPtr != 0L) {
                NativeBindings.waterui_drop_anyview(mainWindow.contentPtr)
            }
        }
        app = null

        // 3. Drop the color scheme signal
        if (colorSchemeSignalPtr != 0L) {
            NativeBindings.waterui_drop_computed_color_scheme(colorSchemeSignalPtr)
            colorSchemeSignalPtr = 0L
        }

        // 4. Finally, close the environment itself
        environment?.close()
        environment = null

        materialThemeInstalled = false
    }

    private fun renderRoot() {
        val initEnv = checkNotNull(environment) { "renderRoot called without environment" }
        android.util.Log.d("WaterUI.RootView", "renderRoot: ensureTheme start")
        // Install theme BEFORE calling waterui_app so user code sees the theme
        ensureTheme(initEnv)
        android.util.Log.d("WaterUI.RootView", "renderRoot: ensureTheme done")
        // Create the app by calling waterui_app(env).
        // The user's app(env) receives the environment with theme installed,
        // creates App::new(content, env), and returns App { windows, env }
        // Native takes ownership of the environment and gets it back in the App.
        if (app == null) {
            android.util.Log.d("WaterUI.RootView", "renderRoot: calling waterui_app()")
            app = NativeBindings.waterui_app(initEnv.raw())
            android.util.Log.d("WaterUI.RootView", "renderRoot: waterui_app() returned app with ${app?.windows?.size} windows")
        }
        removeAllViews()
        val appStruct = app
        if (appStruct == null || appStruct.windows.isEmpty()) {
            android.util.Log.w("WaterUI.RootView", "renderRoot: app is null or has no windows, skipping")
            return
        }
        // Use the environment returned from the app for rendering
        // (App::new injects FullScreenOverlayManager into it)
        val renderEnv = WuiEnvironment(appStruct.envPtr)
        // Extract main window content
        val mainWindow = appStruct.mainWindow()
        val rootPtr = mainWindow.contentPtr
        if (rootPtr == 0L) {
            android.util.Log.w("WaterUI.RootView", "renderRoot: main window contentPtr is null, skipping")
            return
        }
        android.util.Log.d("WaterUI.RootView", "renderRoot: inflating view")
        val child = inflateAnyView(context, rootPtr, renderEnv, registry)
        android.util.Log.d("WaterUI.RootView", "renderRoot: view inflated, adding to layout")
        // Use WRAP_CONTENT here: WaterUiRootView's onMeasure forwards constraints to
        // the Rust-driven root view, so the hosting Android layout can size correctly.
        val params = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(child, params)

        // Setup RootThemeController after the view hierarchy is created
        // This watches the color scheme from the first non-metadata component's environment
        // and applies it to the Activity's window
        RootThemeController.setup(this)
        android.util.Log.d("WaterUI.RootView", "renderRoot: done")
    }

    private fun ensureTheme(env: WuiEnvironment) {
        if (!materialThemeInstalled) {
            val palette = MaterialThemePalette.from(context)
            // Install color slots using the new reactive signal API
            android.util.Log.d("WaterUI.RootView", "ensureTheme: installing color slots")
            installColorSlot(env, ColorSlot.Background, palette.background)
            installColorSlot(env, ColorSlot.Surface, palette.surface)
            installColorSlot(env, ColorSlot.SurfaceVariant, palette.surfaceVariant)
            installColorSlot(env, ColorSlot.Border, palette.border)
            installColorSlot(env, ColorSlot.Foreground, palette.foreground)
            installColorSlot(env, ColorSlot.MutedForeground, palette.mutedForeground)
            installColorSlot(env, ColorSlot.Accent, palette.accent)
            installColorSlot(env, ColorSlot.AccentForeground, palette.accentForeground)
            android.util.Log.d("WaterUI.RootView", "ensureTheme: color slots installed")

            // Install system color scheme into the environment
            // Rust code can override this via .install(Theme::new().color_scheme(...))
            val systemScheme = getSystemColorScheme()
            android.util.Log.d("WaterUI.RootView", "ensureTheme: installing color scheme $systemScheme (0=Light, 1=Dark)")
            val scheme = if (systemScheme == 1) ColorScheme.Dark else ColorScheme.Light
            colorSchemeSignalPtr = ThemeBridge.createConstantColorSchemeSignal(scheme)
            ThemeBridge.installColorScheme(env, colorSchemeSignalPtr)

            materialThemeInstalled = true

            // Set static background color for now (skip reactive observation to debug hang)
            android.util.Log.d("WaterUI.RootView", "ensureTheme: setting static background to ${palette.background}")
            setBackgroundColor(palette.background)
        }
        android.util.Log.d("WaterUI.RootView", "ensureTheme: done")
    }

    private fun getSystemColorScheme(): Int {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return when (nightModeFlags) {
            Configuration.UI_MODE_NIGHT_YES -> 1 // Dark
            else -> 0 // Light
        }
    }

    private fun installColorSlot(env: WuiEnvironment, slot: ColorSlot, argb: Int) {
        val signal = ReactiveColorSignal(argb)
        ThemeBridge.installColor(env, slot, signal.toComputed())
    }
}

private fun createMaterialContext(base: Context): Context {
    val themed = ContextThemeWrapper(base, com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar)
    return DynamicColors.wrapContextIfAvailable(themed)
}

private data class MaterialThemePalette(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val border: Int,
    val foreground: Int,
    val mutedForeground: Int,
    val accent: Int,
    val accentForeground: Int
) {
    companion object {
        private const val DEFAULT_BACKGROUND = 0xFFFEF7FF.toInt()
        private const val DEFAULT_SURFACE = 0xFFFEF7FF.toInt()
        private const val DEFAULT_SURFACE_VARIANT = 0xFFE7E0EC.toInt()
        private const val DEFAULT_OUTLINE = 0xFF79747E.toInt()
        private const val DEFAULT_ON_SURFACE = 0xFF1D1B20.toInt()
        private const val DEFAULT_ON_SURFACE_VARIANT = 0xFF49454F.toInt()
        private const val DEFAULT_PRIMARY = 0xFF6750A4.toInt()
        private const val DEFAULT_ON_PRIMARY = 0xFFFFFFFF.toInt()

        private fun resolveColor(context: Context, attr: Int, fallback: Int): Int {
            return MaterialColors.getColor(context, attr, fallback)
        }

        fun from(context: Context): MaterialThemePalette {
            val surface = resolveColor(context, com.google.android.material.R.attr.colorSurface, DEFAULT_SURFACE)
            val background = resolveColor(context, android.R.attr.colorBackground, DEFAULT_BACKGROUND)
            val surfaceVariant = resolveColor(context, com.google.android.material.R.attr.colorSurfaceVariant, DEFAULT_SURFACE_VARIANT)
            val border = resolveColor(context, com.google.android.material.R.attr.colorOutline, DEFAULT_OUTLINE)
            val foreground = resolveColor(context, com.google.android.material.R.attr.colorOnSurface, DEFAULT_ON_SURFACE)
            val mutedForeground = resolveColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, DEFAULT_ON_SURFACE_VARIANT)
            val accent = resolveColor(context, com.google.android.material.R.attr.colorPrimary, DEFAULT_PRIMARY)
            val accentForeground = resolveColor(context, com.google.android.material.R.attr.colorOnPrimary, DEFAULT_ON_PRIMARY)
            android.util.Log.d(
                "WaterUI.Theme",
                "palette bg=%08X surface=%08X surfaceVar=%08X border=%08X fg=%08X muted=%08X accent=%08X accentFg=%08X".format(
                    background,
                    surface,
                    surfaceVariant,
                    border,
                    foreground,
                    mutedForeground,
                    accent,
                    accentForeground
                )
            )
            return MaterialThemePalette(
                background = background,
                surface = surface,
                surfaceVariant = surfaceVariant,
                border = border,
                foreground = foreground,
                mutedForeground = mutedForeground,
                accent = accent,
                accentForeground = accentForeground
            )
        }
    }
}
