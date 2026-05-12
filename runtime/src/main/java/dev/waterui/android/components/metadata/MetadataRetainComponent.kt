package dev.waterui.android.components

import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataRetainTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_retain_id().toTypeId()
}

/**
 * Renderer for Metadata<Retain>.
 *
 * This component keeps a retained value alive for the lifetime of the view.
 * The retained value is opaque - we just hold onto it and drop it when disposed.
 */
private val metadataRetainRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_retain(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Cleanup - drop the retained value when the view is disposed
    container.disposeWith {
        if (metadata.retainPtr != 0L) {
            NativeBindings.waterui_drop_retain(metadata.retainPtr)
        }
    }

    container
}

internal fun RegistryBuilder.registerWuiRetain() {
    registerMetadata({ metadataRetainTypeId }, metadataRetainRenderer)
}
