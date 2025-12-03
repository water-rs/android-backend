package dev.waterui.android.layout

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.RectStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.SubViewStruct
import dev.waterui.android.runtime.WuiTypeId
import kotlin.math.roundToInt

/**
 * Android [ViewGroup] that mirrors the Swift/Compose Rust layout bridge.
 *
 * Measurement and placement are delegated to the Rust layout engine via JNI.
 * Uses the new 2-phase layout system:
 * 1. `size_that_fits` - Rust calls back to measure children as needed
 * 2. `place` - Returns final positions for all children
 */
class RustLayoutViewGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val layoutPtr: Long = 0L,
    private val descriptors: List<ChildDescriptor> = emptyList()
) : ViewGroup(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(layoutPtr != 0L) { "onMeasure called with null layout pointer" }

        // Empty containers should report zero size
        if (childCount == 0) {
            setMeasuredDimension(0, 0)
            return
        }

        val constraints = LayoutConstraints.fromMeasureSpecs(widthMeasureSpec, heightMeasureSpec)
        val parentProposal = constraints.toProposalStruct()

        // Create SubViewStruct array - Rust will call back to measure each child
        val subviews = Array(childCount) { index ->
            val child = getChildAt(index)
            val descriptor = descriptors.getOrNull(index)
            SubViewStruct(
                view = child,
                stretchAxis = descriptor?.stretchAxis ?: StretchAxis.NONE,
                priority = descriptor?.priority ?: 0
            )
        }

        // Let Rust calculate the size - it will call back to measure children
        val requestedSize = NativeBindings.waterui_layout_size_that_fits(layoutPtr, parentProposal, subviews)
        val measuredWidth = requestedSize.width.resolveDimension(constraints.minWidth, constraints.maxWidth)
        val measuredHeight = requestedSize.height.resolveDimension(constraints.minHeight, constraints.maxHeight)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        require(layoutPtr != 0L) { "onLayout called with null layout pointer" }

        // Nothing to layout for empty containers
        if (childCount == 0) {
            return
        }

        val bounds = RectStruct(
            x = 0f,
            y = 0f,
            width = (right - left).toFloat(),
            height = (bottom - top).toFloat()
        )

        // Create SubViewStruct array for placement
        val subviews = Array(childCount) { index ->
            val child = getChildAt(index)
            val descriptor = descriptors.getOrNull(index)
            SubViewStruct(
                view = child,
                stretchAxis = descriptor?.stretchAxis ?: StretchAxis.NONE,
                priority = descriptor?.priority ?: 0
            )
        }

        // Get placement rects from Rust
        val placements = NativeBindings.waterui_layout_place(layoutPtr, bounds, subviews)

        for (index in 0 until childCount) {
            val rect = placements[index]
            val child = getChildAt(index)

            val allocatedWidth = rect.width.roundToInt()
            val allocatedHeight = rect.height.roundToInt()

            // Re-measure child at allocated size if different from measured size.
            // This is critical for StretchAxis::Horizontal components (TextField, Slider, etc.)
            // which report minimum width during size_that_fits but expand during place.
            if (child.measuredWidth != allocatedWidth || child.measuredHeight != allocatedHeight) {
                child.measure(
                    View.MeasureSpec.makeMeasureSpec(allocatedWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(allocatedHeight, View.MeasureSpec.EXACTLY)
                )
            }

            val childLeft = rect.x.roundToInt()
            val childTop = rect.y.roundToInt()
            val childRight = childLeft + allocatedWidth
            val childBottom = childTop + allocatedHeight
            child.layout(childLeft, childTop, childRight, childBottom)
        }
    }
}

data class ChildDescriptor(
    val typeId: WuiTypeId,
    val stretchAxis: StretchAxis,
    val priority: Int = 0
)

private data class LayoutConstraints(
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int
) {
    companion object {
        fun fromMeasureSpecs(widthSpec: Int, heightSpec: Int): LayoutConstraints {
            val widthMode = View.MeasureSpec.getMode(widthSpec)
            val widthSize = View.MeasureSpec.getSize(widthSpec)
            val heightMode = View.MeasureSpec.getMode(heightSpec)
            val heightSize = View.MeasureSpec.getSize(heightSpec)

            val maxWidth = when (widthMode) {
                View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST -> widthSize
                else -> Int.MAX_VALUE
            }
            val minWidth = if (widthMode == View.MeasureSpec.EXACTLY) widthSize else 0

            val maxHeight = when (heightMode) {
                View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST -> heightSize
                else -> Int.MAX_VALUE
            }
            val minHeight = if (heightMode == View.MeasureSpec.EXACTLY) heightSize else 0

            return LayoutConstraints(minWidth, maxWidth, minHeight, maxHeight)
        }
    }
}

private fun LayoutConstraints.toProposalStruct(): ProposalStruct = ProposalStruct(
    width = if (maxWidth != Int.MAX_VALUE) maxWidth.toFloat() else Float.NaN,
    height = if (maxHeight != Int.MAX_VALUE) maxHeight.toFloat() else Float.NaN
)

private fun Float.resolveDimension(min: Int, max: Int): Int {
    if (isNaN()) {
        return if (max == Int.MAX_VALUE) min else max
    }
    val rounded = roundToInt().coerceAtLeast(0)
    if (max == Int.MAX_VALUE) return rounded.coerceAtLeast(min)
    return rounded.coerceIn(min, max)
}
