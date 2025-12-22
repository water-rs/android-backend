package dev.waterui.android.components

import android.content.Context
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataStandardDynamicRangeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_standard_dynamic_range_id().toTypeId()
}

private val metadataHighDynamicRangeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_high_dynamic_range_id().toTypeId()
}

private fun renderDynamicRange(
    context: Context,
    env: WuiEnvironment,
    registry: RenderRegistry,
    contentPtr: Long
): PassThroughFrameLayout {
    val container = PassThroughFrameLayout(context)

    if (contentPtr != 0L) {
        val child = inflateAnyView(context, contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    container.disposeWith {
        if (contentPtr != 0L) {
            NativeBindings.waterui_drop_anyview(contentPtr)
        }
    }

    return container
}

private val metadataStandardDynamicRangeRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_standard_dynamic_range(node.rawPtr)
    renderDynamicRange(context, env, registry, metadata.contentPtr)
}

private val metadataHighDynamicRangeRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_high_dynamic_range(node.rawPtr)
    renderDynamicRange(context, env, registry, metadata.contentPtr)
}

internal fun RegistryBuilder.registerWuiStandardDynamicRange() {
    registerMetadata({ metadataStandardDynamicRangeTypeId }, metadataStandardDynamicRangeRenderer)
}

internal fun RegistryBuilder.registerWuiHighDynamicRange() {
    registerMetadata({ metadataHighDynamicRangeTypeId }, metadataHighDynamicRangeRenderer)
}
