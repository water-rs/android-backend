package dev.waterui.android.runtime

import android.content.Context
import android.view.View
import dev.waterui.android.components.*

/**
 * Registry of known view renderers. Each renderer creates a concrete Android [View]
 * for a WaterUI node.
 */
class RenderRegistry private constructor(
    private val entries: Map<WuiTypeId, WuiRenderer>
) {
    fun resolve(typeId: WuiTypeId): WuiRenderer? = entries[typeId]

    fun with(typeId: WuiTypeId, renderer: WuiRenderer): RenderRegistry =
        RenderRegistry(entries + (typeId to renderer))

    companion object {
        fun default(): RenderRegistry = RenderRegistry(defaultComponents)
    }
}

/**
 * Functional renderer for a WaterUI node. Implementations must return a fully
 * configured [View] hierarchy rooted at a platform widget.
 */
fun interface WuiRenderer {
    fun createView(context: Context, node: WuiNode, env: WuiEnvironment, registry: RenderRegistry): View
}

/**
 * Simple node descriptor that wraps the native pointer and metadata received via JNI.
 */
data class WuiNode(
    val rawPtr: Long,
    val typeId: WuiTypeId
)

/**
 * Populated lazily to avoid referencing components before they are defined.
 */
private val defaultComponents: Map<WuiTypeId, WuiRenderer> by lazy {
    buildMap {
        registerWuiEmptyView()
        registerWuiText()
        registerWuiPlain()
        registerWuiButton()
        registerWuiColor()
        registerWuiTextField()
        registerWuiStepper()
        registerWuiProgress()
        registerWuiDynamic()
        registerWuiScroll()
        registerWuiContainers()
        registerWuiToggle()
        registerWuiSpacer()
        registerWuiSlider()
        registerWuiRendererView()
        registerWuiPicker()
    }
}

/**
 * Convenience extension for populating a registry map.
 */
fun MutableMap<WuiTypeId, WuiRenderer>.register(
    idProvider: () -> WuiTypeId,
    renderer: WuiRenderer
) {
    put(idProvider(), renderer)
}
