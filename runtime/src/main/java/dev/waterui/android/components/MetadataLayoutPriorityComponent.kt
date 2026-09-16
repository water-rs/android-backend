package dev.waterui.android.components

import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_LAYOUT_PRIORITY
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val layoutPriorityTypeId: WuiTypeId by lazy { NativeBindings.waterui_metadata_layout_priority_id().toTypeId() }

private val layoutPriorityRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_layout_priority(node.rawPtr)
    PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply { setTag(TAG_LAYOUT_PRIORITY, metadata.value) }
}

internal fun RegistryBuilder.registerWuiLayoutPriority() {
    registerMetadata({ layoutPriorityTypeId }, layoutPriorityRenderer)
}
