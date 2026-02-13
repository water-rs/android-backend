package dev.waterui.android.components

import android.content.Context
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiDynamicRangeMode
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyWindowDynamicRangePolicyOnAttach
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.setWuiDynamicRangeMode

private val metadataStandardDynamicRangeTypeId: WuiTypeId by lazy {
    WatcherJni.metadataStandardDynamicRangeId().toTypeId()
}

private val metadataHighDynamicRangeTypeId: WuiTypeId by lazy {
    WatcherJni.metadataHighDynamicRangeId().toTypeId()
}

private fun renderDynamicRange(
    context: Context,
    env: WuiEnvironment,
    registry: RenderRegistry,
    contentPtr: Long,
    mode: WuiDynamicRangeMode
): PassThroughFrameLayout {
    val container = PassThroughFrameLayout(context)
    container.setWuiDynamicRangeMode(mode)
    applyWindowDynamicRangePolicyOnAttach(container, context, mode)

    if (contentPtr != 0L) {
        val child = inflateAnyView(context, contentPtr, env, registry)
        child.setWuiDynamicRangeMode(mode)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    return container
}

private val metadataStandardDynamicRangeRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataStandardDynamicRange(node.rawPtr)
    renderDynamicRange(context, env, registry, metadata.contentPtr, WuiDynamicRangeMode.STANDARD)
}

private val metadataHighDynamicRangeRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataHighDynamicRange(node.rawPtr)
    renderDynamicRange(context, env, registry, metadata.contentPtr, WuiDynamicRangeMode.HIGH)
}

internal fun RegistryBuilder.registerWuiStandardDynamicRange() {
    registerMetadata({ metadataStandardDynamicRangeTypeId }, metadataStandardDynamicRangeRenderer)
}

internal fun RegistryBuilder.registerWuiHighDynamicRange() {
    registerMetadata({ metadataHighDynamicRangeTypeId }, metadataHighDynamicRangeRenderer)
}
