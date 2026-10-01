package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

private val metadataBorderTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_border_id().toTypeId()
}

private const val TOP_EDGE = 1
private const val BOTTOM_EDGE = 2
private const val LEADING_EDGE = 4
private const val TRAILING_EDGE = 8
private const val ALL_EDGES = TOP_EDGE or BOTTOM_EDGE or LEADING_EDGE or TRAILING_EDGE

private fun drawBorderEdges(
    canvas: Canvas,
    paint: Paint,
    borderWidth: Float,
    radius: Float,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    edges: Int,
    layoutDirection: Int
) {
    if (borderWidth == 0f) return
    val inset = borderWidth / 2f
    val height = bottom - top
    if (edges == ALL_EDGES) {
        canvas.drawRoundRect(
            left + inset,
            top + inset,
            right - inset,
            bottom - inset,
            radius,
            radius,
            paint
        )
        return
    }
    if (edges and TOP_EDGE != 0) {
        canvas.drawLine(left, top + inset, right, top + inset, paint)
    }
    if (edges and BOTTOM_EDGE != 0) {
        canvas.drawLine(left, bottom - inset, right, bottom - inset, paint)
    }
    val leadingEdge = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        right - inset
    } else {
        left + inset
    }
    val trailingEdge = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        left + inset
    } else {
        right - inset
    }
    if (edges and LEADING_EDGE != 0) {
        canvas.drawLine(leadingEdge, top, leadingEdge, top + height, paint)
    }
    if (edges and TRAILING_EDGE != 0) {
        canvas.drawLine(trailingEdge, top, trailingEdge, top + height, paint)
    }
}

/**
 * Border stroke painted as the content view's foreground drawable — a
 * paint-only modifier claims no ViewGroup of its own. The drawable's bounds
 * are the host view's, which is what the border modifier means.
 */
private class BorderDrawable(
    context: Context,
    width: Float,
    cornerRadius: Float,
    private val edges: Int
) : Drawable() {
    private val borderWidth = width.dp(context)
    private val radius = cornerRadius.dp(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }
    private var layoutDirection = View.LAYOUT_DIRECTION_LTR

    fun setBorderColor(color: Int) {
        paint.color = color
        invalidateSelf()
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        this.layoutDirection = layoutDirection
        return true
    }

    override fun draw(canvas: Canvas) {
        drawBorderEdges(
            canvas, paint, borderWidth, radius,
            bounds.left.toFloat(), bounds.top.toFloat(),
            bounds.right.toFloat(), bounds.bottom.toFloat(),
            edges, layoutDirection
        )
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }
}

// Rust border metadata is required at construction, so this runtime-only view cannot be inflated.
// Kept for a child that already carries a foreground of its own.
@SuppressLint("ViewConstructor")
private class BorderLayout(
    context: Context,
    width: Float,
    cornerRadius: Float,
    private val edges: Int
) : PassThroughFrameLayout(context) {
    private val borderWidth = width.dp(context)
    private val radius = cornerRadius.dp(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }

    fun setBorderColor(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawBorderEdges(
            canvas, paint, borderWidth, radius,
            0f, 0f, width.toFloat(), height.toFloat(),
            edges, layoutDirection
        )
    }
}

private val metadataBorderRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_border(node.rawPtr)
    val resolvedPtr = NativeBindings.waterui_resolve_color(metadata.colorPtr, env.raw())
    NativeBindings.waterui_drop_color(metadata.colorPtr)

    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    val color = WuiComputed.colorFromComputed(resolvedPtr, env)

    // `.border` folds into the child's foreground drawable; a child already
    // carrying one keeps a wrapper so the two draw instead of clobbering.
    if (child.foreground == null) {
        val drawable = BorderDrawable(context, metadata.width, metadata.cornerRadius, metadata.edges)
        child.foreground = drawable
        color.observe { resolved -> drawable.setBorderColor(resolved.toColorInt()) }
        child.disposeWith(color)
        child
    } else {
        val container = BorderLayout(context, metadata.width, metadata.cornerRadius, metadata.edges)
        container.addView(child)
        color.observe { resolved -> container.setBorderColor(resolved.toColorInt()) }
        container.disposeWith(color)
        container
    }
}

internal fun RegistryBuilder.registerWuiBorder() {
    registerMetadata({ metadataBorderTypeId }, metadataBorderRenderer)
}
