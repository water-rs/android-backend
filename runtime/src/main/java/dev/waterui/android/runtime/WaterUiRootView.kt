package dev.waterui.android.runtime

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import dev.waterui.android.reactive.WuiComputed

/**
 * Root view that owns the WaterUI environment and inflates the Rust-driven
 * view hierarchy into Android's View system.
 */
class WaterUiRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : CenteringHostLayout(context, attrs) {

    private var registry: RenderRegistry = RenderRegistry.default()
    private var environment: WuiEnvironment? = null
    private val rootPtr: Long = NativeBindings.waterui_main()
    private var backgroundTheme: WuiComputed<ResolvedColorStruct>? = null

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
