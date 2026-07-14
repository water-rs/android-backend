package dev.waterui.android.components

import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val metadataRetainTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_retain_id().toTypeId()
}

private val metadataRetainRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_retain(node.rawPtr)
    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
    container.disposeWith {
        NativeBindings.waterui_drop_retain(metadata.retainPtr)
    }
    container
}

internal fun RegistryBuilder.registerWuiRetain() {
    registerMetadata({ metadataRetainTypeId }, metadataRetainRenderer)
}
