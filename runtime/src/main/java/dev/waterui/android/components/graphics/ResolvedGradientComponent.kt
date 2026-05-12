package dev.waterui.android.components

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedGradientStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.toColorInt
import kotlin.math.PI
import kotlin.math.min

private val resolvedGradientTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_resolved_gradient_id().toTypeId()
}

private val resolvedGradientRenderer = WuiRenderer { context, node, _, _ ->
    val resolved = NativeBindings.waterui_force_as_resolved_gradient(node.rawPtr)
    object : View(context) {
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
            if (shader == null) {
                rebuildShader(width, height)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
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

internal fun RegistryBuilder.registerWuiResolvedGradient() {
    register({ resolvedGradientTypeId }, resolvedGradientRenderer)
}

private fun buildShader(gradient: ResolvedGradientStruct, width: Float, height: Float): Shader {
    val sortedStops = gradient.stops.sortedBy { it.position }
    require(sortedStops.isNotEmpty()) { "resolved gradient must contain at least one stop" }

    val colors = IntArray(sortedStops.size) { index -> sortedStops[index].color.toColorInt() }
    val positions = FloatArray(sortedStops.size) { index -> sortedStops[index].position }

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
            require(endRadius > 0f) { "radial gradient end radius must be > 0" }

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
            require(sweep > 0f) { "angular gradient sweep must be positive" }
            require(sweep <= (2f * PI).toFloat()) { "angular gradient sweep must be <= TAU" }

            val sweepFraction = sweep / (2f * PI).toFloat()
            val mappedStops = sortedStops
                .map { stop ->
                    (stop.position.coerceIn(0f, 1f) * sweepFraction) to stop.color.toColorInt()
                }
                .toMutableList()

            val lastColor = mappedStops.last().second
            mappedStops += sweepFraction to lastColor
            if (sweepFraction < 1f) {
                mappedStops += 1f to lastColor
            }

            val mappedColors = IntArray(mappedStops.size) { index -> mappedStops[index].second }
            val mappedPositions = FloatArray(mappedStops.size) { index -> mappedStops[index].first }
            val shader = SweepGradient(cx, cy, mappedColors, mappedPositions)
            shader.setLocalMatrix(Matrix().apply {
                preRotate(Math.toDegrees(gradient.startValue.toDouble()).toFloat(), cx, cy)
            })
            shader
        }

        else -> error("unsupported gradient type: ${gradient.gradientType}")
    }
}

private const val DEFAULT_SIZE_DP = 10f
