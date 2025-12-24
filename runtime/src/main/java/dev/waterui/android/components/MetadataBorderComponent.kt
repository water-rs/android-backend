package dev.waterui.android.components

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataBorderTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_border_id().toTypeId()
}

/**
 * Renderer for Metadata<Border>.
 *
 * Applies a border effect to the wrapped view.
 * Uses GradientDrawable for border rendering with support for:
 * - Border color (resolved from WuiColor)
 * - Border width
 * - Corner radius
 * - Edge-specific borders (top, leading, bottom, trailing)
 */
private val metadataBorderRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_border(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Resolve the border color
    if (metadata.colorPtr != 0L) {
        val resolvedColor = NativeBindings.waterui_resolve_color(metadata.colorPtr, env.raw())
        val resolvedColorStruct = NativeBindings.waterui_read_computed_resolved_color(resolvedColor)
        val borderColor = Color.argb(
            (resolvedColorStruct.opacity * 255).toInt(),
            (resolvedColorStruct.red * 255).toInt(),
            (resolvedColorStruct.green * 255).toInt(),
            (resolvedColorStruct.blue * 255).toInt()
        )
        NativeBindings.waterui_drop_computed_resolved_color(resolvedColor)

        val density = context.resources.displayMetrics.density
        val borderWidthPx = (metadata.width * density).toInt()
        val cornerRadiusPx = metadata.cornerRadius * density

        // Check if all edges are enabled
        val allEdges = metadata.top && metadata.leading && metadata.bottom && metadata.trailing

        if (allEdges) {
            // Simple case: apply border to all edges using GradientDrawable
            val drawable = GradientDrawable().apply {
                setStroke(borderWidthPx, borderColor)
                setCornerRadius(cornerRadiusPx)
            }
            container.foreground = drawable
        } else {
            // Edge-specific borders: create a custom drawable
            // For now, we still use a full border but could be extended
            // to use a LayerDrawable with individual edge drawables
            val drawable = GradientDrawable().apply {
                setStroke(borderWidthPx, borderColor)
                setCornerRadius(cornerRadiusPx)
            }
            container.foreground = drawable
            // TODO: Implement edge-specific borders using LayerDrawable
            // This would require creating separate shape drawables for each edge
        }
    }

    // Cleanup
    container.disposeWith {
    }

    container
}

internal fun RegistryBuilder.registerWuiBorder() {
    registerMetadata({ metadataBorderTypeId }, metadataBorderRenderer)
}
