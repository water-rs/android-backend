package dev.waterui.android.runtime

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

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
    }

    private fun renderRoot() {
        val env = environment ?: return
        removeAllViews()
        if (rootPtr == 0L) {
            return
        }
        val child = inflateAnyView(context, rootPtr, env, registry)
        val params = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(child, params)
    }
}
