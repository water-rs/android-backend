package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.toColorInt

private val metadataBorderTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_border_id().toTypeId()
}

// Rust border metadata is required at construction, so this runtime-only view cannot be inflated.
@SuppressLint("ViewConstructor")
private class BorderLayout(
    context: Context,
    width: Float,
    private val cornerRadius: Float,
    private val edges: Int
) : PassThroughFrameLayout(context) {
    private val borderWidth = width.dp(context)
    private val radius = cornerRadius.dp(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }
    private val bounds = RectF()

    fun setBorderColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (borderWidth == 0f) {
            return
        }
        val inset = borderWidth / 2f
        if (edges == ALL_EDGES) {
            bounds.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(bounds, radius, radius, paint)
            return
        }

        if (edges and TOP_EDGE != 0) {
            canvas.drawLine(0f, inset, width.toFloat(), inset, paint)
        }
        if (edges and BOTTOM_EDGE != 0) {
            canvas.drawLine(0f, height - inset, width.toFloat(), height - inset, paint)
        }
        val leadingEdge = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            width - inset
        } else {
            inset
        }
        val trailingEdge = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            inset
        } else {
            width - inset
        }
        if (edges and LEADING_EDGE != 0) {
            canvas.drawLine(leadingEdge, 0f, leadingEdge, height.toFloat(), paint)
        }
        if (edges and TRAILING_EDGE != 0) {
            canvas.drawLine(trailingEdge, 0f, trailingEdge, height.toFloat(), paint)
        }
    }

    private companion object {
        const val TOP_EDGE = 1
        const val BOTTOM_EDGE = 2
        const val LEADING_EDGE = 4
        const val TRAILING_EDGE = 8
        const val ALL_EDGES = TOP_EDGE or BOTTOM_EDGE or LEADING_EDGE or TRAILING_EDGE
    }
}

private val metadataBorderRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_border(node.rawPtr)
    val resolvedPtr = NativeBindings.waterui_resolve_color(metadata.colorPtr, env.raw())
    NativeBindings.waterui_drop_color(metadata.colorPtr)
    BorderLayout(context, metadata.width, metadata.cornerRadius, metadata.edges)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            val color = WuiComputed.colorFromComputed(resolvedPtr)
            color.observe { resolved -> setBorderColor(resolved.toColorInt()) }
            disposeWith(color)
        }
}

internal fun RegistryBuilder.registerWuiBorder() {
    registerMetadata({ metadataBorderTypeId }, metadataBorderRenderer)
}
