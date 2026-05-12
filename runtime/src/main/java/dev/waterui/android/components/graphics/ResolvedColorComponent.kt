package dev.waterui.android.components

import android.view.View
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.toColorInt

private val resolvedColorTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_resolved_color_id().toTypeId()
}

private val resolvedColorRenderer = WuiRenderer { context, node, _, _ ->
    val resolved = NativeBindings.waterui_force_as_resolved_color(node.rawPtr)
    object : View(context) {
        init {
            setBackgroundColor(resolved.toColorInt())
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

internal fun RegistryBuilder.registerWuiResolvedColor() {
    register({ resolvedColorTypeId }, resolvedColorRenderer)
}

private const val DEFAULT_SIZE_DP = 10f
