package dev.waterui.android.components

import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.MetadataDynamicRangeStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.SurfaceDynamicRange
import dev.waterui.android.runtime.TAG_DYNAMIC_RANGE
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val standardDynamicRangeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_standard_dynamic_range_id().toTypeId()
}

private val highDynamicRangeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_high_dynamic_range_id().toTypeId()
}

private fun dynamicRangeRenderer(
    range: SurfaceDynamicRange,
    extract: (Long) -> MetadataDynamicRangeStruct
) = WuiRenderer { context, node, env, registry ->
    val metadata = extract(node.rawPtr)
    PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            setTag(TAG_DYNAMIC_RANGE, range)
        }
}

private val standardDynamicRangeRenderer = dynamicRangeRenderer(
    SurfaceDynamicRange.STANDARD,
    NativeBindings::waterui_force_as_metadata_standard_dynamic_range
)

private val highDynamicRangeRenderer = dynamicRangeRenderer(
    SurfaceDynamicRange.HIGH,
    NativeBindings::waterui_force_as_metadata_high_dynamic_range
)

internal fun RegistryBuilder.registerWuiDynamicRange() {
    registerMetadata({ standardDynamicRangeTypeId }, standardDynamicRangeRenderer)
    registerMetadata({ highDynamicRangeTypeId }, highDynamicRangeRenderer)
}
