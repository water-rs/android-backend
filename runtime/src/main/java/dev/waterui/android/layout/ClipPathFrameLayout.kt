package dev.waterui.android.layout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.widget.FrameLayout
import dev.waterui.android.runtime.PathCommandStruct
import dev.waterui.android.runtime.PathCommandType
import kotlin.math.abs

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
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

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
     */
    private fun addArc(
        path: Path,
        cx: Float, cy: Float,
        rx: Float, ry: Float,
        startAngle: Float, sweepAngle: Float
    ) {
        if (rx <= 0f || ry <= 0f) return

        val left = cx - rx
        val top = cy - ry
        val right = cx + rx
        val bottom = cy + ry
        val startDegrees = Math.toDegrees(startAngle.toDouble()).toFloat()
        val sweepDegrees = Math.toDegrees(sweepAngle.toDouble()).toFloat()

        if (abs(sweepDegrees) >= 360f) {
            path.addOval(left, top, right, bottom, Path.Direction.CW)
            return
        }

        path.arcTo(left, top, right, bottom, startDegrees, sweepDegrees, path.isEmpty)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (!clipPath.isEmpty) {
            val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            super.dispatchDraw(canvas)
            canvas.drawPath(clipPath, maskPaint)
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
