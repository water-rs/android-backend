package dev.waterui.android.components

import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataOpacityTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_opacity_id().toTypeId()
}

/**
 * Renderer for Metadata<Opacity>.
 *
 * Applies alpha blending to the wrapped view hierarchy.
 */
private val metadataOpacityRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_opacity(node.rawPtr)
    require(metadata.contentPtr != 0L) {
        "Metadata<Opacity> content pointer is null"
    }
    require(metadata.valuePtr != 0L) {
        "Metadata<Opacity> value pointer is null"
    }

    val container = PassThroughFrameLayout(context)

    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    container.addView(child)
    container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())

    val opacityComputed = WuiComputed.float(metadata.valuePtr, env)
    opacityComputed.observe { alpha ->
        check(alpha in 0f..1f) {
            "Metadata<Opacity> value out of range: $alpha"
        }
        container.alpha = alpha
    }

    container.disposeWith {
        opacityComputed.close()
        NativeBindings.waterui_drop_anyview(metadata.contentPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiOpacity() {
    registerMetadata({ metadataOpacityTypeId }, metadataOpacityRenderer)
}
