package dev.waterui.android.components

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedGradientStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toColorLong
import kotlin.math.PI
import kotlin.math.min

private val resolvedGradientTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_resolved_gradient_id().toTypeId()
}

private val resolvedGradientRenderer = WuiRenderer { context, node, _, _ ->
    val resolved = NativeBindings.waterui_force_as_resolved_gradient(node.rawPtr)
    object : StretchVisualView(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: Shader? = null

        private fun rebuildShader(width: Int, height: Int) {
            shader = buildShader(resolved, width.toFloat(), height.toFloat())
            paint.shader = shader
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuildShader(w, h)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            checkNotNull(shader) { "resolved gradient drew before receiving its size" }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
internal fun RegistryBuilder.registerWuiResolvedGradient() {
    register({ resolvedGradientTypeId }, resolvedGradientRenderer)
}

private fun buildShader(gradient: ResolvedGradientStruct, width: Float, height: Float): Shader {
    val colors = LongArray(gradient.stops.size) { index -> gradient.stops[index].color.toColorLong() }
    val positions = FloatArray(gradient.stops.size) { index -> gradient.stops[index].position }

    return when (gradient.gradientType) {
        0 -> LinearGradient(
            gradient.startX * width,
            gradient.startY * height,
            gradient.endX * width,
            gradient.endY * height,
            colors,
            positions,
            Shader.TileMode.CLAMP
        )

        1 -> {
            val cx = gradient.startX * width
            val cy = gradient.startY * height
            val scale = min(width, height)
            val startRadius = gradient.startValue * scale
            val endRadius = gradient.endValue * scale

            val radialPositions = FloatArray(positions.size) { i ->
                (startRadius + (endRadius - startRadius) * positions[i]) / endRadius
            }

            RadialGradient(
                cx,
                cy,
                endRadius,
                colors,
                radialPositions,
                Shader.TileMode.CLAMP
            )
        }

        2 -> {
            val cx = gradient.startX * width
            val cy = gradient.startY * height
            val sweep = gradient.endValue - gradient.startValue

            val sweepFraction = sweep / (2f * PI).toFloat()
            val mappedSize = colors.size + if (sweepFraction < 1f) 2 else 1
            val mappedColors = LongArray(mappedSize)
            val mappedPositions = FloatArray(mappedSize)
            colors.copyInto(mappedColors)
            positions.forEachIndexed { index, position ->
                mappedPositions[index] = position * sweepFraction
            }
            val lastColor = colors.last()
            mappedColors[colors.size] = lastColor
            mappedPositions[colors.size] = sweepFraction
            if (sweepFraction < 1f) {
                mappedColors[mappedSize - 1] = lastColor
                mappedPositions[mappedSize - 1] = 1f
            }
            val shader = SweepGradient(cx, cy, mappedColors, mappedPositions)
            shader.setLocalMatrix(Matrix().apply {
                preRotate(Math.toDegrees(gradient.startValue.toDouble()).toFloat(), cx, cy)
            })
            shader
        }

        else -> error("unsupported gradient type: ${gradient.gradientType}")
    }
}
