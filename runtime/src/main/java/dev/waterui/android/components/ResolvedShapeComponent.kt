package dev.waterui.android.components

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PathCommandStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedShapeStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.toColorInt

private val resolvedShapeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_resolved_shape_id().toTypeId()
}

private val resolvedShapeRenderer = WuiRenderer { context, node, _, _ ->
    val resolved = NativeBindings.waterui_force_as_resolved_shape(node.rawPtr)
    object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = resolved.fill.toColorInt()
        }
        private val emptyPath = Path()
        private var path: Path? = null

        private fun rebuildPath(width: Int, height: Int) {
            path = buildPath(resolved, width.toFloat(), height.toFloat())
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildPath(w, h)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            if (path == null) {
                rebuildPath(width, height)
            }
            canvas.drawPath(path ?: emptyPath, paint)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = MeasureSpec.getSize(heightMeasureSpec)

            val fallback = DEFAULT_SIZE_DP.dp(context).toInt()
            val measuredWidth = when (widthMode) {
                MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
                else -> fallback
            }
            val measuredHeight = when (heightMode) {
                MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> heightSize
                else -> fallback
            }
            setMeasuredDimension(measuredWidth, measuredHeight)
        }
    }
}

internal fun RegistryBuilder.registerWuiResolvedShape() {
    register({ resolvedShapeTypeId }, resolvedShapeRenderer)
}

private fun buildPath(shape: ResolvedShapeStruct, width: Float, height: Float): Path {
    val path = Path()
    for (command in shape.commands) {
        applyCommand(path, command, width, height)
    }
    return path
}

private fun applyCommand(path: Path, cmd: PathCommandStruct, width: Float, height: Float) {
    when (cmd.tag) {
        0 -> path.moveTo(cmd.x * width, cmd.y * height)
        1 -> path.lineTo(cmd.x * width, cmd.y * height)
        2 -> path.quadTo(
            cmd.cx * width,
            cmd.cy * height,
            cmd.x * width,
            cmd.y * height
        )

        3 -> path.cubicTo(
            cmd.c1x * width,
            cmd.c1y * height,
            cmd.c2x * width,
            cmd.c2y * height,
            cmd.x * width,
            cmd.y * height
        )

        4 -> {
            val oval = RectF(
                (cmd.cx - cmd.rx) * width,
                (cmd.cy - cmd.ry) * height,
                (cmd.cx + cmd.rx) * width,
                (cmd.cy + cmd.ry) * height
            )
            path.addArc(
                oval,
                Math.toDegrees(cmd.start.toDouble()).toFloat(),
                Math.toDegrees(cmd.sweep.toDouble()).toFloat()
            )
        }

        5 -> path.close()
        else -> error("unknown path command tag: ${cmd.tag}")
    }
}

private const val DEFAULT_SIZE_DP = 10f
