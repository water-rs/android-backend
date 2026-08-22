package dev.waterui.android.runtime

import android.content.Context
import android.content.res.Configuration
import android.view.GestureDetector
import android.view.MotionEvent
import dev.waterui.android.ffi.InspectorJni
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.view.isEmpty
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.waterui.android.components.WebViewFactory
import dev.waterui.android.reactive.WuiComputed
import java.io.Closeable

/** Hosts one Rust-driven WaterUI view tree and owns its native environment. */
class WaterUiRootView @JvmOverloads constructor(
    baseContext: Context,
    attrs: AttributeSet? = null
) : FrameLayout(createMaterialContext(baseContext), attrs), Closeable {
    private var registry: RenderRegistry = RenderRegistry.default()
    private var environment: WuiEnvironment? = null
    private var pendingEnvironment: WuiEnvironment? = null
    private var backgroundTheme: WuiComputed<ResolvedColorStruct>? = null
    private var materialTheme: MaterialThemeSignals? = null
    private var rootThemeController: RootThemeController? = null
    private var lifecycle: Lifecycle? = null
    private var closed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) {
            close()
        }
    }

    init {
        clipChildren = false
        clipToPadding = false
        requireNotNull(context.findWaterUiContext()) {
            "WaterUiRootView is missing its WaterUiContext"
        }.setRootEnvironmentConsumer(::captureRootEnvironment)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
            val safeArea = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(this)
    }

    fun setRenderRegistry(renderRegistry: RenderRegistry) {
        check(environment == null && pendingEnvironment == null && !closed) {
            "RenderRegistry must be configured before WaterUiRootView is attached"
        }
        registry = renderRegistry
    }

    fun getRenderRegistry(): RenderRegistry = registry

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        check(!closed) { "disposed WaterUiRootView cannot be attached" }
        val owner = checkNotNull(findViewTreeLifecycleOwner()) {
            "WaterUiRootView requires a ViewTreeLifecycleOwner"
        }
        val registeredLifecycle = lifecycle
        if (registeredLifecycle == null) {
            lifecycle = owner.lifecycle
            owner.lifecycle.addObserver(lifecycleObserver)
        } else {
            check(registeredLifecycle === owner.lifecycle) {
                "WaterUiRootView cannot move between lifecycle owners"
            }
        }
        if (environment == null && pendingEnvironment == null) {
            check(isEmpty()) { "uninitialized WaterUiRootView has an existing child" }
            beginRenderRoot()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pendingEnvironment?.let { installSystemLocale(it, newConfig) }
        environment?.let { installSystemLocale(it, newConfig) }
        materialTheme?.update(
            palette = MaterialThemePalette.from(context),
            typography = MaterialTypographyPalette.from(context),
            scheme = systemColorScheme(newConfig)
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(
                View.resolveSize(0, widthMeasureSpec),
                View.resolveSize(0, heightMeasureSpec)
            )
            return
        }

        val availableWidth =
            (View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight =
            (View.MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom).coerceAtLeast(0)
        val childWidthSpec = childMeasureSpec(widthMeasureSpec, availableWidth)
        val childHeightSpec = childMeasureSpec(heightMeasureSpec, availableHeight)
        child.measure(childWidthSpec, childHeightSpec)

        setMeasuredDimension(
            View.resolveSize(child.measuredWidth + paddingLeft + paddingRight, widthMeasureSpec),
            View.resolveSize(child.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun close() {
        check(!closed) { "WaterUiRootView was already disposed" }
        closed = true
        lifecycle?.removeObserver(lifecycleObserver)
        lifecycle = null
        disposeAndRemoveAllViews()
        rootThemeController?.close()
        rootThemeController = null
        backgroundTheme?.close()
        backgroundTheme = null
        materialTheme?.close()
        materialTheme = null
        pendingEnvironment?.close()
        pendingEnvironment = null
        environment?.close()
        environment = null
    }

    private fun beginRenderRoot() {
        val runtimeOwner = context.applicationContext as? WaterUiRuntimeOwner
            ?: error("WaterUiRootView requires a WaterUiRuntimeOwner Application")
        val initEnv = runtimeOwner.createWaterUiEnvironment()
        pendingEnvironment = initEnv
        installSystemLocale(initEnv, context.resources.configuration)
        materialTheme = MaterialThemeSignals.install(
            env = initEnv,
            palette = MaterialThemePalette.from(context),
            typography = MaterialTypographyPalette.from(context),
            colorScheme = systemColorScheme(context.resources.configuration)
        )
        NativeBindings.waterui_env_install_webview_controller(
            initEnv.raw(),
            WebViewFactory(context)
        )

        // The environment handed to `waterui_app` must already own the GPU runtime:
        // any `GpuSurface` in the tree resolves it out of the environment while the
        // view body runs, and a missing runtime is a panic there. `GpuRuntime::new`
        // creates the wgpu adapter and device eagerly, so every app currently waits
        // for a device before its first view is inflated. Deferring that cost needs
        // the FFI to install a runtime handle whose device is created on first use;
        // it cannot be deferred from here without breaking the contract above.
        NativeBindings.waterui_gpu_runtime_create { runtimePtr ->
            mainHandler.post { finishGpuRuntimeInitialization(runtimePtr) }
        }
    }

    private fun finishGpuRuntimeInitialization(runtimePtr: Long) {
        if (closed) {
            NativeBindings.waterui_drop_gpu_runtime(runtimePtr)
            return
        }

        val initEnv = checkNotNull(pendingEnvironment) {
            "GPU runtime completed without a pending WaterUI environment"
        }
        NativeBindings.waterui_env_install_gpu_runtime(initEnv.raw(), runtimePtr)
        pendingEnvironment = null

        val app = NativeBindings.waterui_app(initEnv.takeRaw())
        val renderEnv = WuiEnvironment(app.takeEnvironment())
        renderEnv.pxPerSp = context.pxPerSp()
        environment = renderEnv
        bindBackgroundTheme(renderEnv)
        val child = inflateAnyView(context, app.takeContent(), renderEnv, registry)
        addView(
            child,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        check(rootThemeController != null) {
            "WaterUI root content did not produce a renderable native view"
        }
        installInspectGesture(renderEnv)
    }

    /**
     * Offers the inspector on a two-finger long press, in a debug build.
     *
     * A phone has no secondary click, and a two-finger long press is unlikely to
     * be something the application itself claimed. The inspector runs on the
     * developer's computer, so this reports where to attach rather than opening
     * anything here.
     *
     * Does nothing when no endpoint is running, which is every release build.
     */
    private fun installInspectGesture(env: WuiEnvironment) {
        if (!InspectorJni.isAvailable(env.raw())) {
            return
        }
        val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(event: MotionEvent) {
                    if (event.pointerCount >= 2) {
                        InspectorJni.open(env.raw())
                    }
                }
            }
        )
        // Observes only: the touch is passed on so the application still receives
        // everything it would have.
        setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
    }

    private fun captureRootEnvironment(env: WuiEnvironment) {
        if (rootThemeController == null) {
            rootThemeController = RootThemeController(env, this) { scheme ->
                checkNotNull(materialTheme) {
                    "WaterUI root color scheme changed before platform theme installation"
                }.updateColors(MaterialThemePalette.from(context, scheme))
            }
        }
    }

    private fun bindBackgroundTheme(env: WuiEnvironment) {
        backgroundTheme = ThemeBridge.background(env).also { computed ->
            computed.observe { color -> setBackgroundColor(color.toColorInt()) }
        }
    }

    private fun childMeasureSpec(parentSpec: Int, availableSize: Int): Int =
        when (View.MeasureSpec.getMode(parentSpec)) {
            View.MeasureSpec.EXACTLY ->
                View.MeasureSpec.makeMeasureSpec(availableSize, View.MeasureSpec.EXACTLY)
            View.MeasureSpec.AT_MOST ->
                View.MeasureSpec.makeMeasureSpec(availableSize, View.MeasureSpec.AT_MOST)
            View.MeasureSpec.UNSPECIFIED ->
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            else -> error("unknown MeasureSpec mode: ${View.MeasureSpec.getMode(parentSpec)}")
        }
}

private fun installSystemLocale(env: WuiEnvironment, configuration: Configuration) {
    check(!configuration.locales.isEmpty) { "Android configuration has no system locale" }
    NativeBindings.waterui_env_install_locale_tag(
        env.raw(),
        configuration.locales[0].toLanguageTag()
    )
}

private fun createMaterialContext(base: Context): Context {
    val themed = ContextThemeWrapper(
        base,
        com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar
    )
    return WaterUiContext(DynamicColors.wrapContextIfAvailable(themed))
}

private fun systemColorScheme(configuration: Configuration): ColorScheme =
    when (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
        Configuration.UI_MODE_NIGHT_YES -> ColorScheme.Dark
        Configuration.UI_MODE_NIGHT_NO,
        Configuration.UI_MODE_NIGHT_UNDEFINED -> ColorScheme.Light
        else -> error("unknown Android night mode")
    }

private data class MaterialThemePalette(
    val background: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val border: Int,
    val foreground: Int,
    val mutedForeground: Int,
    val accent: Int,
    val accentForeground: Int,
    val accentContainer: Int,
    val tertiary: Int,
    val tertiaryContainer: Int,
    val selectionContainer: Int,
    val selectionForeground: Int
) {
    operator fun get(slot: ColorSlot): Int = when (slot) {
        ColorSlot.Background -> background
        ColorSlot.Surface -> surface
        ColorSlot.SurfaceVariant -> surfaceVariant
        ColorSlot.Border -> border
        ColorSlot.Foreground -> foreground
        ColorSlot.MutedForeground -> mutedForeground
        ColorSlot.Accent -> accent
        ColorSlot.AccentForeground -> accentForeground
        ColorSlot.AccentContainer -> accentContainer
        ColorSlot.Tertiary -> tertiary
        ColorSlot.TertiaryContainer -> tertiaryContainer
        ColorSlot.SelectionContainer -> selectionContainer
        ColorSlot.SelectionForeground -> selectionForeground
    }

    companion object {
        private fun resolve(context: Context, attr: Int, name: String): Int =
            MaterialColors.getColor(context, attr, "WaterUI Material theme is missing $name")

        fun from(context: Context): MaterialThemePalette = MaterialThemePalette(
            background = resolve(context, android.R.attr.colorBackground, "colorBackground"),
            surface = resolve(context, com.google.android.material.R.attr.colorSurface, "colorSurface"),
            surfaceVariant = resolve(
                context,
                com.google.android.material.R.attr.colorSurfaceVariant,
                "colorSurfaceVariant"
            ),
            border = resolve(context, com.google.android.material.R.attr.colorOutline, "colorOutline"),
            foreground = resolve(context, com.google.android.material.R.attr.colorOnSurface, "colorOnSurface"),
            mutedForeground = resolve(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                "colorOnSurfaceVariant"
            ),
            accent = resolve(context, androidx.appcompat.R.attr.colorPrimary, "colorPrimary"),
            accentForeground = resolve(
                context,
                com.google.android.material.R.attr.colorOnPrimary,
                "colorOnPrimary"
            ),
            accentContainer = resolve(
                context,
                com.google.android.material.R.attr.colorPrimaryContainer,
                "colorPrimaryContainer"
            ),
            tertiary = resolve(
                context,
                com.google.android.material.R.attr.colorTertiary,
                "colorTertiary"
            ),
            tertiaryContainer = resolve(
                context,
                com.google.android.material.R.attr.colorTertiaryContainer,
                "colorTertiaryContainer"
            ),
            // Material's selected-item pair: a tonal secondary container with
            // its own on-container content color.
            selectionContainer = resolve(
                context,
                com.google.android.material.R.attr.colorSecondaryContainer,
                "colorSecondaryContainer"
            ),
            selectionForeground = resolve(
                context,
                com.google.android.material.R.attr.colorOnSecondaryContainer,
                "colorOnSecondaryContainer"
            )
        )

        fun from(context: Context, scheme: ColorScheme): MaterialThemePalette {
            val configuration = Configuration(context.resources.configuration)
            configuration.uiMode =
                (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    when (scheme) {
                        ColorScheme.Light -> Configuration.UI_MODE_NIGHT_NO
                        ColorScheme.Dark -> Configuration.UI_MODE_NIGHT_YES
                    }
            val configuredContext = context.createConfigurationContext(configuration)
            return from(createMaterialContext(configuredContext))
        }
    }
}

private data class MaterialTypographyPalette(
    val body: ResolvedFontStruct,
    val title: ResolvedFontStruct,
    val headline: ResolvedFontStruct,
    val subheadline: ResolvedFontStruct,
    val caption: ResolvedFontStruct,
    val footnote: ResolvedFontStruct
) {
    operator fun get(slot: FontSlot): ResolvedFontStruct = when (slot) {
        FontSlot.Body -> body
        FontSlot.Title -> title
        FontSlot.Headline -> headline
        FontSlot.Subheadline -> subheadline
        FontSlot.Caption -> caption
        FontSlot.Footnote -> footnote
    }

    companion object {
        fun from(context: Context): MaterialTypographyPalette = MaterialTypographyPalette(
            body = resolve(
                context,
                com.google.android.material.R.attr.textAppearanceBodyLarge,
                "textAppearanceBodyLarge"
            ),
            title = resolve(
                context,
                com.google.android.material.R.attr.textAppearanceHeadlineMedium,
                "textAppearanceHeadlineMedium"
            ),
            headline = resolve(
                context,
                com.google.android.material.R.attr.textAppearanceTitleLarge,
                "textAppearanceTitleLarge"
            ),
            subheadline = resolve(
                context,
                com.google.android.material.R.attr.textAppearanceTitleMedium,
                "textAppearanceTitleMedium"
            ),
            caption = resolve(
                context,
                com.google.android.material.R.attr.textAppearanceBodySmall,
                "textAppearanceBodySmall"
            ),
            footnote = resolve(
                context,
                com.google.android.material.R.attr.textAppearanceLabelSmall,
                "textAppearanceLabelSmall"
            )
        )

        private fun resolve(context: Context, attribute: Int, name: String): ResolvedFontStruct {
            val attributes = context.obtainStyledAttributes(intArrayOf(attribute))
            val appearance = attributes.getResourceId(0, 0)
            attributes.recycle()
            check(appearance != 0) { "WaterUI Material theme is missing $name" }

            val textView = TextView(context)
            textView.setTextAppearance(appearance)
            val typeface = requireNotNull(textView.typeface) {
                "WaterUI Material theme $name did not resolve a typeface"
            }
            // Theme font sizes travel in sp so the whole UI follows the
            // user's font-scale setting, mirroring Compose's sp typography.
            return ResolvedFontStruct(
                size = textView.textSize / context.pxPerSp(),
                weight = typeface.toWaterUiFontWeight(),
                family = null
            )
        }
    }
}

private fun Typeface.toWaterUiFontWeight(): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return if (isBold) 6 else 3
    }
    return ((weight + 50) / 100).coerceIn(1, 9) - 1
}

private class MaterialThemeSignals private constructor(
    private val colors: Map<ColorSlot, ReactiveColorSignal>,
    private val fonts: Map<FontSlot, ReactiveFontSignal>,
    private val colorScheme: ReactiveColorSchemeSignal
) : Closeable {
    fun updateColors(palette: MaterialThemePalette) {
        colors.forEach { (slot, signal) -> signal.setValue(palette[slot]) }
    }

    fun update(
        palette: MaterialThemePalette,
        typography: MaterialTypographyPalette,
        scheme: ColorScheme
    ) {
        updateColors(palette)
        fonts.forEach { (slot, signal) -> signal.setValue(typography[slot]) }
        colorScheme.setValue(scheme)
    }

    override fun close() {
        colors.values.forEach(ReactiveColorSignal::close)
        fonts.values.forEach(ReactiveFontSignal::close)
        colorScheme.close()
    }

    companion object {
        fun install(
            env: WuiEnvironment,
            palette: MaterialThemePalette,
            typography: MaterialTypographyPalette,
            colorScheme: ColorScheme
        ): MaterialThemeSignals {
            val colors = ColorSlot.entries.associateWith { slot ->
                ReactiveColorSignal(palette[slot]).also { signal ->
                    ThemeBridge.installColor(env, slot, signal.takeComputed())
                }
            }
            val fonts = FontSlot.entries.associateWith { slot ->
                ReactiveFontSignal(typography[slot]).also { signal ->
                    ThemeBridge.installFont(env, slot, signal.takeComputed())
                }
            }
            val schemeSignal = ReactiveColorSchemeSignal(colorScheme).also { signal ->
                ThemeBridge.installColorScheme(env, signal.takeComputed())
            }
            return MaterialThemeSignals(colors, fonts, schemeSignal)
        }
    }
}
