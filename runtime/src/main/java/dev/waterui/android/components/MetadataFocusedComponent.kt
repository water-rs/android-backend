package dev.waterui.android.components

import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiFocusedBindingController
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.requireSingleWuiFocusTarget

private val metadataFocusedTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_focused_id().toTypeId()
}

private val metadataFocusedRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_focused(node.rawPtr)
    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)

    val focusBinding = WuiBinding.bool(metadata.bindingPtr)
    val focusTarget = container.requireSingleWuiFocusTarget()
    container.disposeWith(WuiFocusedBindingController(container, focusTarget, focusBinding))
    container
}

internal fun RegistryBuilder.registerWuiFocused() {
    registerMetadata({ metadataFocusedTypeId }, metadataFocusedRenderer)
}
