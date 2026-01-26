package dev.waterui.android.components

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.Canvas
import android.graphics.Paint
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

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
        val borderColor = resolvedColorStruct.toColorInt()
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
            container.foreground = EdgeBorderDrawable(
                color = borderColor,
                widthPx = borderWidthPx.coerceAtLeast(0),
                top = metadata.top,
                leading = metadata.leading,
                bottom = metadata.bottom,
                trailing = metadata.trailing
            )
        }
    }

    // Cleanup
    container.disposeWith { }

    container
}

internal fun RegistryBuilder.registerWuiBorder() {
    registerMetadata({ metadataBorderTypeId }, metadataBorderRenderer)
}

/**
 * Draws simple per-edge borders inside the current bounds.
 *
 * Note: For partial-edge borders, this does not attempt to match rounded corner
 * rendering. Full rounded borders are handled by GradientDrawable in the all-edges case.
 */
private class EdgeBorderDrawable(
    private val color: Int,
    private val widthPx: Int,
    private val top: Boolean,
    private val leading: Boolean,
    private val bottom: Boolean,
    private val trailing: Boolean
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = this@EdgeBorderDrawable.color
    }

    override fun draw(canvas: Canvas) {
        if (widthPx <= 0) return
        val b = bounds
        val w = widthPx.toFloat()

        if (top) {
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + w, paint)
        }
        if (bottom) {
            canvas.drawRect(b.left.toFloat(), b.bottom - w, b.right.toFloat(), b.bottom.toFloat(), paint)
        }
        if (leading) {
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.left + w, b.bottom.toFloat(), paint)
        }
        if (trailing) {
            canvas.drawRect(b.right - w, b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android SDK")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
