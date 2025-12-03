package dev.waterui.android.runtime

import android.content.Context
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
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
    private var rootPtr: Long = 0L
    private var backgroundTheme: WuiComputed<ResolvedColorStruct>? = null
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
            environment = WuiEnvironment.create()
        }
        renderRoot()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeAllViews()
        
        // 1. Close the theme watcher first (depends on environment)
        backgroundTheme?.close()
        backgroundTheme = null
        
        // 2. Drop the native root view (depends on environment/runtime)
        if (rootPtr != 0L) {
            NativeBindings.waterui_drop_anyview(rootPtr)
            rootPtr = 0L
        }

        // 3. Finally, close the environment itself
        environment?.close()
        environment = null
        
        materialThemeInstalled = false
    }

    private fun renderRoot() {
        val env = checkNotNull(environment) { "renderRoot called without environment" }
        android.util.Log.d("WaterUI.RootView", "renderRoot: ensureTheme start")
        ensureTheme(env)
        android.util.Log.d("WaterUI.RootView", "renderRoot: ensureTheme done")
        // Create the root view AFTER environment and theme are initialized.
        // waterui_main() may create reactive signals that depend on the executor
        // initialized by waterui_init() (called in WuiEnvironment.create()).
        if (rootPtr == 0L) {
            android.util.Log.d("WaterUI.RootView", "renderRoot: calling waterui_main()")
            rootPtr = NativeBindings.waterui_main()
            android.util.Log.d("WaterUI.RootView", "renderRoot: waterui_main() returned $rootPtr")
        }
        removeAllViews()
        if (rootPtr == 0L) {
            android.util.Log.w("WaterUI.RootView", "renderRoot: rootPtr is null, skipping")
            return
        }
        android.util.Log.d("WaterUI.RootView", "renderRoot: inflating view")
        val child = inflateAnyView(context, rootPtr, env, registry)
        android.util.Log.d("WaterUI.RootView", "renderRoot: view inflated, adding to layout")
        // Use MATCH_PARENT to fill the available space, matching iOS Auto Layout constraints
        // that pin root view to all edges of the parent
        val params = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(child, params)
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
            materialThemeInstalled = true
            
            // Set static background color for now (skip reactive observation to debug hang)
            android.util.Log.d("WaterUI.RootView", "ensureTheme: setting static background to ${palette.background}")
            setBackgroundColor(palette.background)
        }
        android.util.Log.d("WaterUI.RootView", "ensureTheme: done")
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
