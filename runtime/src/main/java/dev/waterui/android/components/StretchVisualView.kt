package dev.waterui.android.components

import android.content.Context
import android.view.View
import dev.waterui.android.runtime.dp

internal abstract class StretchVisualView(context: Context) : View(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val intrinsicSize = INTRINSIC_SIZE_DP.dp(context).toInt()
        setMeasuredDimension(
            resolveDimension(widthMeasureSpec, intrinsicSize),
            resolveDimension(heightMeasureSpec, intrinsicSize)
        )
    }

    private fun resolveDimension(measureSpec: Int, intrinsicSize: Int): Int =
        when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(measureSpec)
            MeasureSpec.UNSPECIFIED -> intrinsicSize
            else -> error("unknown Android measure mode: ${MeasureSpec.getMode(measureSpec)}")
        }

    private companion object {
        const val INTRINSIC_SIZE_DP = 10f
    }
}
