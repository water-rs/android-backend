package dev.waterui.android.layout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.widget.FrameLayout
import dev.waterui.android.runtime.PathCommandStruct
import dev.waterui.android.runtime.PathCommandType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * A FrameLayout that clips its children to a path defined by normalized path commands.
 *
 * Path commands use normalized coordinates (0.0-1.0) which are scaled to the view's bounds.
 */
class ClipPathFrameLayout(
    context: Context,
    private val commands: Array<PathCommandStruct>
) : FrameLayout(context) {

    private val clipPath = Path()

    init {
        // Enable hardware layer for better clipping performance
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildPath(w.toFloat(), h.toFloat())
    }

    private fun rebuildPath(width: Float, height: Float) {
        clipPath.reset()

        for (cmd in commands) {
            when (cmd.type) {
                PathCommandType.MOVE_TO -> {
                    clipPath.moveTo(cmd.x * width, cmd.y * height)
                }
                PathCommandType.LINE_TO -> {
                    clipPath.lineTo(cmd.x * width, cmd.y * height)
                }
                PathCommandType.QUAD_TO -> {
                    clipPath.quadTo(
                        cmd.cx * width, cmd.cy * height,
                        cmd.x * width, cmd.y * height
                    )
                }
                PathCommandType.CUBIC_TO -> {
                    clipPath.cubicTo(
                        cmd.c1x * width, cmd.c1y * height,
                        cmd.c2x * width, cmd.c2y * height,
                        cmd.x * width, cmd.y * height
                    )
                }
                PathCommandType.ARC -> {
                    addArc(
                        clipPath,
                        cmd.cx * width, cmd.cy * height,
                        cmd.rx * width, cmd.ry * height,
                        cmd.start, cmd.sweep
                    )
                }
                PathCommandType.CLOSE -> {
                    clipPath.close()
                }
            }
        }
    }

    /**
     * Adds an arc to the path.
     * For elliptical arcs, approximates with cubic bezier curves.
     */
    private fun addArc(
        path: Path,
        cx: Float, cy: Float,
        rx: Float, ry: Float,
        startAngle: Float, sweepAngle: Float
    ) {
        if (abs(rx - ry) < 0.001f) {
            // Circular arc - can use Android's arcTo directly
            // arcTo expects a bounding rect, start angle in degrees, and sweep in degrees
            val left = cx - rx
            val top = cy - ry
            val right = cx + rx
            val bottom = cy + ry
            val startDegrees = Math.toDegrees(startAngle.toDouble()).toFloat()
            val sweepDegrees = Math.toDegrees(sweepAngle.toDouble()).toFloat()
            path.arcTo(left, top, right, bottom, startDegrees, sweepDegrees, false)
        } else {
            // Elliptical arc - approximate with bezier curves
            addEllipticalArc(path, cx, cy, rx, ry, startAngle, sweepAngle)
        }
    }

    /**
     * Approximates an elliptical arc with cubic bezier curves.
     * Splits the arc into 90-degree segments for better approximation.
     */
    private fun addEllipticalArc(
        path: Path,
        cx: Float, cy: Float,
        rx: Float, ry: Float,
        startAngle: Float, sweepAngle: Float
    ) {
        val segments = maxOf(1, ceil(abs(sweepAngle) / (Math.PI / 2)).toInt())
        val segmentAngle = sweepAngle / segments

        var currentAngle = startAngle

        for (i in 0 until segments) {
            val endAngle = currentAngle + segmentAngle
            addEllipticalArcSegment(path, cx, cy, rx, ry, currentAngle, endAngle)
            currentAngle = endAngle
        }
    }

    /**
     * Adds a single segment of an elliptical arc using cubic bezier approximation.
     */
    private fun addEllipticalArcSegment(
        path: Path,
        cx: Float, cy: Float,
        rx: Float, ry: Float,
        startAngle: Float, endAngle: Float
    ) {
        val alpha = (endAngle - startAngle) / 2
        val cosAlpha = cos(alpha)
        val sinAlpha = sin(alpha)
        val cotAlpha = if (sinAlpha != 0f) cosAlpha / sinAlpha else 0f

        val phi = (startAngle + endAngle) / 2
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val lambda = (4f - cosAlpha) / 3f

        val p1x = cx + rx * cos(startAngle)
        val p1y = cy + ry * sin(startAngle)
        val p2x = cx + rx * cos(endAngle)
        val p2y = cy + ry * sin(endAngle)

        val c1x = p1x + lambda * rx * sinAlpha * (-sinPhi - cotAlpha * cosPhi)
        val c1y = p1y + lambda * ry * sinAlpha * (cosPhi - cotAlpha * sinPhi)
        val c2x = p2x + lambda * rx * sinAlpha * (sinPhi - cotAlpha * cosPhi)
        val c2y = p2y + lambda * ry * sinAlpha * (-cosPhi - cotAlpha * sinPhi)

        path.cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!clipPath.isEmpty) {
            canvas.save()
            canvas.clipPath(clipPath)
            super.dispatchDraw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
