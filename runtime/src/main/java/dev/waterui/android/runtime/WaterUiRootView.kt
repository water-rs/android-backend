package dev.waterui.android.runtime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import dev.waterui.android.components.WebViewManager
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.ColorSlot
import dev.waterui.android.runtime.ReactiveColorSignal
import java.util.EnumMap

/**
 * Root view that owns the WaterUI environment and inflates the Rust-driven
 * view hierarchy into Android's View system.
 *
 * ## Ownership Model
 *
 * The environment ownership follows Apple's pattern:
 * 1. `waterui_init()` creates an environment (we own it via raw pointer)
 * 2. Install theme signals into the environment
 * 3. `waterui_app(env)` transfers ownership to Rust
 * 4. Rust's `app(env)` can override theme via `env.install(theme)`
 * 5. `waterui_app` returns `App { windows, env }` - Rust gives back ownership
 * 6. We use the returned `env` for rendering and theme control
 *
 * **IMPORTANT**: After calling `waterui_app()`, the original init env pointer
 * is invalid. We must use `app.envPtr` for all subsequent operations.
 *
 * Layout decisions are made by the Rust layout engine - this view only
 * measures and places children according to Rust's instructions.
 */
class WaterUiRootView @JvmOverloads constructor(
    baseContext: Context,
    attrs: AttributeSet? = null
) : FrameLayout(createMaterialContext(baseContext), attrs) {

    companion object {
        private const val TAG = "WaterUI.RootView"
    }

    private var registry: RenderRegistry = RenderRegistry.default()

    /**
     * The app struct returned from waterui_app().
     * Contains the windows and the environment pointer we should use.
     * This owns the environment after waterui_app() returns.
     */
    private var app: AppStruct? = null

    /**
     * The rendering environment. This wraps app.envPtr and is the environment
     * we use for all rendering and theme operations after waterui_app() returns.
     * This is a "borrowed" view - it should NOT drop the pointer on close.
     */
    private var renderEnv: WuiEnvironment? = null

    /**
     * Theme bridge controller that holds the reactive theme signals.
     * These signals are installed into the init env and survive the waterui_app() call.
     */
    private var themeBridge: ThemeBridgeController? = null

    /**
     * Background color watcher that updates view background reactively.
     */
    private var backgroundTheme: WuiComputed<ResolvedColorStruct>? = null

    fun setRenderRegistry(renderRegistry: RenderRegistry) {
        registry = renderRegistry
        if (app != null) {
            renderRoot()
        }
    }

    fun getRenderRegistry(): RenderRegistry = registry

    /** Forces the root tree to be rebuilt. Useful for hot reload flows. */
    fun reload() {
        if (app != null) {
            renderRoot()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (app == null) {
            initializeApp()
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

    /**
     * Initializes the WaterUI app:
     * 1. Create environment via waterui_init()
     * 2. Install system theme into the environment
     * 3. Call waterui_app() to create the app (transfers ownership)
     * 4. Store the returned app and use its environment
     */
    private fun initializeApp() {
        WebViewManager.init(context)

        android.util.Log.d(TAG, "initializeApp: creating environment")

        // Step 1: Create the init environment
        // NOTE: We use the raw pointer and do NOT wrap it in WuiEnvironment
        // because waterui_app() will take ownership of it.
        val initEnvPtr = NativeBindings.waterui_init()
        android.util.Log.d(TAG, "initializeApp: environment created, ptr=$initEnvPtr")

        // Step 2: Install system theme into the environment
        val palette = MaterialThemePalette.from(context)
        val fonts = MaterialThemeFonts.from(context)
        val systemScheme = getSystemColorScheme()
        val scheme = if (systemScheme == 1) ColorScheme.Dark else ColorScheme.Light

        android.util.Log.d(TAG, "initializeApp: installing theme (scheme=$scheme)")
        themeBridge = ThemeBridgeController(initEnvPtr, palette, fonts, scheme)
        android.util.Log.d(TAG, "initializeApp: theme installed")

        // Also install media picker manager and webview controller
        NativeBindings.waterui_env_install_media_picker_manager(initEnvPtr)
        NativeBindings.waterui_env_install_webview_controller(initEnvPtr)

        // Step 3: Call waterui_app() - this TAKES OWNERSHIP of the init env
        // After this call, initEnvPtr is invalid and we must use app.envPtr
        android.util.Log.d(TAG, "initializeApp: calling waterui_app()")
        val appStruct = NativeBindings.waterui_app(initEnvPtr)
        android.util.Log.d(TAG, "initializeApp: waterui_app() returned app with ${appStruct.windows.size} windows, envPtr=${appStruct.envPtr}")

        // Step 4: Store the app and create a borrowed view of the returned environment
        app = appStruct
        renderEnv = WuiEnvironment.borrowed(appStruct.envPtr)

        setBackgroundColor(palette.background)
        android.util.Log.d(TAG, "initializeApp: done")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeAllViews()

        android.util.Log.d(TAG, "onDetachedFromWindow: cleaning up")

        // 1. Reset the root theme controller first (it holds a reference to renderEnv)
        RootThemeController.reset()

        // 2. Close the background watcher
        backgroundTheme?.close()
        backgroundTheme = null

        // 3. Clear theme bridge (signals are owned by Rust after install)
        themeBridge = null

        // 4. Clear the borrowed env view (does NOT drop the native pointer)
        renderEnv?.close()
        renderEnv = null

        // 5. Drop the app which owns the environment
        app?.let { appStruct ->
            android.util.Log.d(TAG, "onDetachedFromWindow: dropping app env")
            NativeBindings.waterui_env_drop(appStruct.envPtr)
        }
        app = null

        android.util.Log.d(TAG, "onDetachedFromWindow: cleanup done")
    }

    private fun renderRoot() {
        val appStruct = app ?: run {
            android.util.Log.w(TAG, "renderRoot: app is null, skipping")
            return
        }
        val env = renderEnv ?: run {
            android.util.Log.w(TAG, "renderRoot: renderEnv is null, skipping")
            return
        }

        if (appStruct.windows.isEmpty()) {
            android.util.Log.w(TAG, "renderRoot: app has no windows, skipping")
            return
        }

        removeAllViews()

        // Watch background color from the render environment
        ensureBackground(env)

        // Extract main window content
        val mainWindow = appStruct.mainWindow()
        val rootPtr = mainWindow.contentPtr
        if (rootPtr == 0L) {
            android.util.Log.w(TAG, "renderRoot: main window contentPtr is null, skipping")
            return
        }

        android.util.Log.d(TAG, "renderRoot: inflating view")
        val child = inflateAnyView(context, rootPtr, env, registry)
        android.util.Log.d(TAG, "renderRoot: view inflated, adding to layout")

        // Use WRAP_CONTENT here: WaterUiRootView's onMeasure forwards constraints to
        // the Rust-driven root view, so the hosting Android layout can size correctly.
        val params = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(child, params)

        // Setup RootThemeController after the view hierarchy is created.
        // This watches the App environment's color scheme and applies it to the Activity window.
        // The color scheme may have been overridden by user's Rust code via env.install(theme).
        RootThemeController.setup(this, appStruct.envPtr)
        android.util.Log.d(TAG, "renderRoot: done")
    }

    private fun getSystemColorScheme(): Int {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return when (nightModeFlags) {
            Configuration.UI_MODE_NIGHT_YES -> 1 // Dark
            else -> 0 // Light
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Update theme signals when system appearance changes
        app?.let {
            val palette = MaterialThemePalette.from(context)
            val fonts = MaterialThemeFonts.from(context)
            val systemScheme = getSystemColorScheme()
            val scheme = if (systemScheme == 1) ColorScheme.Dark else ColorScheme.Light
            themeBridge?.update(palette, fonts, scheme)
        }
    }

    private fun ensureBackground(env: WuiEnvironment) {
        backgroundTheme?.close()
        backgroundTheme = ThemeBridge.background(env).also { computed ->
            computed.observe { color ->
                setBackgroundColor(color.toColorInt())
            }
            computed.attachTo(this)
        }
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

private data class MaterialThemeFont(
    val sizeSp: Float,
    val weight: Int
)

private data class MaterialThemeFonts(
    val body: MaterialThemeFont,
    val title: MaterialThemeFont,
    val headline: MaterialThemeFont,
    val subheadline: MaterialThemeFont,
    val caption: MaterialThemeFont,
    val footnote: MaterialThemeFont
) {
    companion object {
        fun from(context: Context): MaterialThemeFonts {
            return MaterialThemeFonts(
                body = resolveFont(context, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge),
                title = resolveFont(context, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge),
                headline = resolveFont(context, com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall),
                subheadline = resolveFont(context, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium),
                caption = resolveFont(context, com.google.android.material.R.style.TextAppearance_Material3_BodySmall),
                footnote = resolveFont(context, com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            )
        }

        private fun resolveFont(context: Context, textAppearance: Int): MaterialThemeFont {
            val textView = TextView(context)
            TextViewCompat.setTextAppearance(textView, textAppearance)
            val sizeSp = textView.textSize / context.resources.displayMetrics.scaledDensity
            val weight = resolveWeight(textView.typeface)
            return MaterialThemeFont(sizeSp = sizeSp, weight = weight)
        }

        private fun resolveWeight(typeface: Typeface?): Int {
            val weightValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                typeface?.weight ?: 400
            } else {
                val style = typeface?.style ?: Typeface.NORMAL
                if (style and Typeface.BOLD != 0) 700 else 400
            }
            return wuiWeightFromAndroid(weightValue)
        }

        private fun wuiWeightFromAndroid(weight: Int): Int {
            val clamped = weight.coerceIn(100, 900)
            return (clamped / 100 - 1).coerceIn(0, 8)
        }
    }
}

/**
 * Controller that manages theme signal installation.
 *
 * This class:
 * 1. Creates reactive signals for system theme values
 * 2. Installs them into the init environment before waterui_app()
 * 3. Updates signal values when system appearance changes
 *
 * The signals survive the waterui_app() call because they're installed into
 * the environment. User code can override via env.install(Theme::new()...),
 * in which case the native signals are replaced.
 */
private class ThemeBridgeController(
    envPtr: Long,
    palette: MaterialThemePalette,
    fonts: MaterialThemeFonts,
    scheme: ColorScheme
) {
    private val colorSchemeSignal = ReactiveColorSchemeSignal(scheme)
    private val colorSignals = EnumMap<ColorSlot, ReactiveColorSignal>(ColorSlot::class.java)
    private val fontSignals = EnumMap<FontSlot, ReactiveFontSignal>(FontSlot::class.java)

    init {
        install(envPtr, palette, fonts)
    }

    fun update(palette: MaterialThemePalette, fonts: MaterialThemeFonts, scheme: ColorScheme) {
        colorSchemeSignal.setValue(scheme)
        colorSignals[ColorSlot.Background]?.setValue(palette.background)
        colorSignals[ColorSlot.Surface]?.setValue(palette.surface)
        colorSignals[ColorSlot.SurfaceVariant]?.setValue(palette.surfaceVariant)
        colorSignals[ColorSlot.Border]?.setValue(palette.border)
        colorSignals[ColorSlot.Foreground]?.setValue(palette.foreground)
        colorSignals[ColorSlot.MutedForeground]?.setValue(palette.mutedForeground)
        colorSignals[ColorSlot.Accent]?.setValue(palette.accent)
        colorSignals[ColorSlot.AccentForeground]?.setValue(palette.accentForeground)

        fontSignals[FontSlot.Body]?.setValue(fonts.body.sizeSp, fonts.body.weight)
        fontSignals[FontSlot.Title]?.setValue(fonts.title.sizeSp, fonts.title.weight)
        fontSignals[FontSlot.Headline]?.setValue(fonts.headline.sizeSp, fonts.headline.weight)
        fontSignals[FontSlot.Subheadline]?.setValue(fonts.subheadline.sizeSp, fonts.subheadline.weight)
        fontSignals[FontSlot.Caption]?.setValue(fonts.caption.sizeSp, fonts.caption.weight)
        fontSignals[FontSlot.Footnote]?.setValue(fonts.footnote.sizeSp, fonts.footnote.weight)
    }

    private fun install(envPtr: Long, palette: MaterialThemePalette, fonts: MaterialThemeFonts) {
        // Install color scheme
        NativeBindings.waterui_theme_install_color_scheme(envPtr, colorSchemeSignal.toComputed())

        // Install colors
        installColorSlot(envPtr, ColorSlot.Background, palette.background)
        installColorSlot(envPtr, ColorSlot.Surface, palette.surface)
        installColorSlot(envPtr, ColorSlot.SurfaceVariant, palette.surfaceVariant)
        installColorSlot(envPtr, ColorSlot.Border, palette.border)
        installColorSlot(envPtr, ColorSlot.Foreground, palette.foreground)
        installColorSlot(envPtr, ColorSlot.MutedForeground, palette.mutedForeground)
        installColorSlot(envPtr, ColorSlot.Accent, palette.accent)
        installColorSlot(envPtr, ColorSlot.AccentForeground, palette.accentForeground)

        // Install fonts
        installFontSlot(envPtr, FontSlot.Body, fonts.body)
        installFontSlot(envPtr, FontSlot.Title, fonts.title)
        installFontSlot(envPtr, FontSlot.Headline, fonts.headline)
        installFontSlot(envPtr, FontSlot.Subheadline, fonts.subheadline)
        installFontSlot(envPtr, FontSlot.Caption, fonts.caption)
        installFontSlot(envPtr, FontSlot.Footnote, fonts.footnote)
    }

    private fun installColorSlot(envPtr: Long, slot: ColorSlot, argb: Int) {
        val signal = ReactiveColorSignal(argb)
        NativeBindings.waterui_theme_install_color(envPtr, slot.value, signal.toComputed())
        colorSignals[slot] = signal
    }

    private fun installFontSlot(envPtr: Long, slot: FontSlot, font: MaterialThemeFont) {
        val signal = ReactiveFontSignal(font.sizeSp, font.weight)
        NativeBindings.waterui_theme_install_font(envPtr, slot.value, signal.toComputed())
        fontSignals[slot] = signal
    }
}
