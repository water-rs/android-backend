package dev.waterui.android.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import dev.waterui.android.runtime.FilledShapeStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PathCommandType
import dev.waterui.android.runtime.PathCommandStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.setResolvedColor
import kotlin.math.cos
import kotlin.math.sin

private val filledShapeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_filled_shape_id().toTypeId()
}

/**
 * A custom View that renders a filled shape.
 * Like SwiftUI's Shape views, this fills all available space.
 */
private class FilledShapeView(
    context: Context,
    private val commands: Array<PathCommandStruct>,
    fillColor: ResolvedColorStruct
) : View(context) {

    private val shapePath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        setResolvedColor(fillColor)
    }

    init {
        // FilledShape fills available space like Color
        setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath(w.toFloat(), h.toFloat())
    }

    private fun rebuildPath(width: Float, height: Float) {
        shapePath.reset()

        for (cmd in commands) {
            when (cmd.type) {
                PathCommandType.MOVE_TO -> {
                    shapePath.moveTo(cmd.x * width, cmd.y * height)
                }
                PathCommandType.LINE_TO -> {
                    shapePath.lineTo(cmd.x * width, cmd.y * height)
                }
                PathCommandType.QUAD_TO -> {
                    shapePath.quadTo(
                        cmd.cx * width, cmd.cy * height,
                        cmd.x * width, cmd.y * height
                    )
                }
                PathCommandType.CUBIC_TO -> {
                    shapePath.cubicTo(
                        cmd.c1x * width, cmd.c1y * height,
                        cmd.c2x * width, cmd.c2y * height,
                        cmd.x * width, cmd.y * height
                    )
                }
                PathCommandType.ARC -> {
                    addArc(shapePath, cmd, width, height)
                }
                PathCommandType.CLOSE -> {
                    shapePath.close()
                }
            }
        }
    }

    /**
     * Add an arc to the path.
     * Converts from center/radius/angles to Android's arcTo which uses bounds.
     */
    private fun addArc(path: Path, cmd: PathCommandStruct, width: Float, height: Float) {
        val cx = cmd.cx * width
        val cy = cmd.cy * height
        val rx = cmd.rx * width
        val ry = cmd.ry * height
        val startAngle = Math.toDegrees(cmd.start.toDouble()).toFloat()
        val sweepAngle = Math.toDegrees(cmd.sweep.toDouble()).toFloat()

        // Android arcTo uses bounding rectangle
        val left = cx - rx
        val top = cy - ry
        val right = cx + rx
        val bottom = cy + ry

        // For a full circle (sweep >= 360), we need to handle specially
        // because arcTo with 360 degrees may not work correctly
        if (kotlin.math.abs(sweepAngle) >= 360f) {
            // Draw a full oval
            path.addOval(left, top, right, bottom, Path.Direction.CW)
        } else {
            // Move to start point if path is empty
            if (path.isEmpty) {
                val startX = cx + rx * cos(cmd.start)
                val startY = cy + ry * sin(cmd.start)
                path.moveTo(startX, startY)
            }
            path.arcTo(left, top, right, bottom, startAngle, sweepAngle, false)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(shapePath, paint)
    }
}

/**
 * Renderer for FilledShape.
 * Creates a view that renders a shape filled with a color.
 */
private val filledShapeRenderer = WuiRenderer { context, node, env, _ ->
    val filled = NativeBindings.waterui_force_as_filled_shape(node.rawPtr)

    // Resolve the fill color
    val resolvedColorPtr = NativeBindings.waterui_resolve_color(filled.fillPtr, env.raw())
    val resolvedColor = NativeBindings.waterui_read_computed_resolved_color(resolvedColorPtr)
    NativeBindings.waterui_drop_computed_resolved_color(resolvedColorPtr)

    val view = FilledShapeView(context, filled.commands, resolvedColor)

    view.disposeWith {
        // No additional cleanup needed
    }

    view
}

internal fun RegistryBuilder.registerWuiFilledShape() {
    register({ filledShapeTypeId }, filledShapeRenderer)
}
