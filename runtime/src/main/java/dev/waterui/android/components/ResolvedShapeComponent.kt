package dev.waterui.android.components

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PathCommandStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.toColorLong

private val resolvedShapeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_resolved_shape_id().toTypeId()
}

private val resolvedShapeRenderer = WuiRenderer { context, node, _, _ ->
    val resolved = NativeBindings.waterui_force_as_resolved_shape(node.rawPtr)
    val fill = WuiComputed.colorFromComputed(resolved.fillPtr)
    object : StretchVisualView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private var path: Path? = null

        init {
            fill.observe { color ->
                paint.setColor(color.toColorLong())
                invalidate()
            }
            disposeWith(fill)
        }

        private fun rebuildPath(width: Int, height: Int) {
            path = buildNormalizedPath(resolved.commands, width.toFloat(), height.toFloat())
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildPath(w, h)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(
                checkNotNull(path) { "resolved shape drew before receiving its size" },
                paint
            )
        }
    }
}
internal fun RegistryBuilder.registerWuiResolvedShape() {
    register({ resolvedShapeTypeId }, resolvedShapeRenderer)
}

internal fun buildNormalizedPath(
    commands: Array<PathCommandStruct>,
    width: Float,
    height: Float
): Path {
    val path = Path()
    for (command in commands) {
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
