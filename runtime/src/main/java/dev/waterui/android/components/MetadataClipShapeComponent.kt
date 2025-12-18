package dev.waterui.android.components

import dev.waterui.android.layout.ClipPathFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataClipShapeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_clip_shape_id().toTypeId()
}

/**
 * Renderer for Metadata<ClipShape>.
 *
 * Clips the wrapped view to a shape defined by path commands.
 * Uses a ClipPathFrameLayout to apply the clip path during drawing.
 */
private val metadataClipShapeRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_clip_shape(node.rawPtr)

    val container = ClipPathFrameLayout(context, metadata.commands)

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Cleanup
    container.disposeWith {
        // No additional cleanup needed for clip shape
    }

    container
}

internal fun RegistryBuilder.registerWuiClipShape() {
    registerMetadata({ metadataClipShapeTypeId }, metadataClipShapeRenderer)
}
