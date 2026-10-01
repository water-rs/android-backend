package dev.waterui.android.components

import android.content.Context
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val metadataOpacityTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_opacity_id().toTypeId()
}

/**
 * Host for an `.opacity` subtree.
 *
 * SwiftUI's opacity modifier composites the whole subtree without clipping
 * it, so content a child draws outside its layout frame — translations,
 * shadows, unclipped shapes — keeps drawing. Android's default is different:
 * `hasOverlappingRendering` returning true makes `alpha < 1` promote the
 * subtree to a compositing layer sized to this view's bounds, which clips
 * everything outside them. Declaring the content non-overlapping keeps the
 * alpha on the draw commands themselves, so no bounded layer exists and
 * nothing clips.
 */
internal class OpacityLayout(context: Context) : PassThroughFrameLayout(context) {
    override fun hasOverlappingRendering(): Boolean = false
}

private val metadataOpacityRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_opacity(node.rawPtr)
    val container = OpacityLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)

    val opacityComputed = WuiComputed.float(metadata.valuePtr)
    opacityComputed.observe { alpha ->
        container.alpha = alpha
    }

    container.disposeWith(opacityComputed)

    container
}

internal fun RegistryBuilder.registerWuiOpacity() {
    registerMetadata({ metadataOpacityTypeId }, metadataOpacityRenderer)
}
