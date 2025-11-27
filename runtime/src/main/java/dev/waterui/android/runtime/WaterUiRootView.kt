package dev.waterui.android.runtime

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import dev.waterui.android.reactive.WuiComputed

/**
 * Root view that owns the WaterUI environment and inflates the Rust-driven
 * view hierarchy into Android's View system.
 */
class WaterUiRootView @JvmOverloads constructor(
    baseContext: Context,
    attrs: AttributeSet? = null
) : CenteringHostLayout(createMaterialContext(baseContext), attrs) {

    private var registry: RenderRegistry = RenderRegistry.default()
    private var environment: WuiEnvironment? = null
    private val rootPtr: Long = NativeBindings.waterui_main()
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
        environment?.close()
        environment = null
        backgroundTheme?.close()
        backgroundTheme = null
        materialThemeInstalled = false
    }

    private fun renderRoot() {
        val env = environment ?: return
        ensureTheme(env)
        removeAllViews()
        if (rootPtr == 0L) {
            return
        }
        val child = inflateAnyView(context, rootPtr, env, registry)
        val params = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(child, params)
    }

    private fun ensureTheme(env: WuiEnvironment) {
        if (!materialThemeInstalled) {
            val palette = MaterialThemePalette.from(context)
            NativeBindings.waterui_install_static_theme(
                env.raw(),
                palette.background,
                palette.surface,
                palette.surfaceVariant,
                palette.border,
                palette.foreground,
                palette.mutedForeground,
                palette.accent,
                palette.accentForeground
            )
            materialThemeInstalled = true
        }
        if (backgroundTheme != null) return
        val theme = ThemeBridge.background(env)
        theme.observeWithAnimation { color, animation ->
            val colorInt = color.toColorInt()
            this@WaterUiRootView.applyRustAnimation(animation) {
                setBackgroundColor(colorInt)
            }
        }
        theme.attachTo(this)
        backgroundTheme = theme
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
        fun from(context: Context): MaterialThemePalette {
            val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE)
            val background = MaterialColors.getColor(context, android.R.attr.colorBackground, surface)
            val surfaceVariant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, surface)
            val border = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutline, surfaceVariant)
            val foreground = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            val mutedForeground = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, foreground)
            val accent = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, foreground)
            val accentForeground = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimary, background)
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
