package dev.waterui.android.components

import android.os.Build
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

private val metadataShadowTypeId: WuiTypeId by lazy {
    WatcherJni.metadataShadowId().toTypeId()
}

/**
 * Renderer for Metadata<Shadow>.
 *
 * Applies a shadow effect to the wrapped view.
 * On Android, this uses elevation and outlineAmbientShadowColor/outlineSpotShadowColor
 * for API 28+, or falls back to elevation-only for older versions.
 */
private val metadataShadowRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataShadow(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Apply geometry-related shadow parameters.
    val density = context.resources.displayMetrics.density
    container.elevation = metadata.radius * density
    container.translationX = metadata.offsetX * density
    container.translationY = metadata.offsetY * density

    var colorComputed: WuiComputed<dev.waterui.android.runtime.ResolvedColorStruct>? = null
    if (metadata.colorPtr != 0L) {
        colorComputed = WuiComputed.colorFromComputed(metadata.colorPtr, env)
        colorComputed.observe { resolvedColor ->
            val shadowColor = resolvedColor.toColorInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                container.outlineAmbientShadowColor = shadowColor
                container.outlineSpotShadowColor = shadowColor
            }
        }
    }

    // Cleanup
    container.disposeWith {
        colorComputed?.close()
    }

    container
}

internal fun RegistryBuilder.registerWuiShadow() {
    registerMetadata({ metadataShadowTypeId }, metadataShadowRenderer)
}
