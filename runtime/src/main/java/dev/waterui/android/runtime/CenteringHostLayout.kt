package dev.waterui.android.runtime

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Simple [ViewGroup] that hosts a single child and ensures it is centred inside the
 * available bounds, mirroring the SwiftUI root behaviour.
 */
open class CenteringHostLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    var centerHorizontally: Boolean = true,
    var centerVertically: Boolean = true
) : ViewGroup(context, attrs) {

    fun setCentering(horizontal: Boolean, vertical: Boolean) {
        centerHorizontally = horizontal
        centerVertically = vertical
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(
                View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
                View.getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
            )
            return
        }

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val childWidthSpec = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.AT_MOST)
            else -> MeasureSpec.UNSPECIFIED
        }
        val childHeightSpec = when (heightMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.AT_MOST)
            else -> MeasureSpec.UNSPECIFIED
        }

        child.measure(childWidthSpec, childHeightSpec)

        val hasBoundedWidth = widthMode != MeasureSpec.UNSPECIFIED
        val hasBoundedHeight = heightMode != MeasureSpec.UNSPECIFIED

        val measuredWidth = resolveDimension(centerHorizontally, hasBoundedWidth, widthSize, child.measuredWidth)
        val measuredHeight = resolveDimension(centerVertically, hasBoundedHeight, heightSize, child.measuredHeight)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val child = getChildAt(0) ?: return
        val width = right - left
        val height = bottom - top
        val childWidth = child.measuredWidth
        val childHeight = child.measuredHeight

        val offsetX = if (centerHorizontally) ((width - childWidth) / 2).coerceAtLeast(0) else 0
        val offsetY = if (centerVertically) ((height - childHeight) / 2).coerceAtLeast(0) else 0

        child.layout(
            offsetX,
            offsetY,
            offsetX + childWidth,
            offsetY + childHeight
        )
    }
}

private fun resolveDimension(center: Boolean, bounded: Boolean, boundSize: Int, child: Int): Int {
    if (!bounded) return child
    return if (center) {
        maxOf(boundSize, child)
    } else {
        minOf(boundSize, child)
    }
}
