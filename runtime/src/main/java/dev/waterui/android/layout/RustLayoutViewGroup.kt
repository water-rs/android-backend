package dev.waterui.android.layout

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Space
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

    /** Screen density for converting between dp (Rust) and pixels (Android) */
    private val density: Float = context.resources.displayMetrics.density

    /**
     * Convert dp (density-independent pixels) to physical pixels.
     * Rust layout uses dp; Android Views use pixels.
     */
    private fun Float.dpToPx(): Float = this * density

    /**
     * Convert physical pixels to dp.
     */
    private fun Float.pxToDp(): Float = this / density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(layoutPtr != 0L) { "onMeasure called with null layout pointer" }

        // Empty containers should report zero size
        if (childCount == 0) {
            setMeasuredDimension(0, 0)
            return
        }

        val constraints = LayoutConstraints.fromMeasureSpecs(widthMeasureSpec, heightMeasureSpec)
        // Convert pixel constraints to dp for Rust layout engine
        val parentProposal = constraints.toProposalStruct(density)

        // Create SubViewStruct array - Rust will call back to measure each child
        // Pass density so child measurements can convert between dp and pixels
        val subviews = Array(childCount) { index ->
            val child = getChildAt(index)
            val descriptor = descriptors.getOrNull(index)
            SubViewStruct(
                view = child,
                stretchAxis = descriptor?.stretchAxis ?: StretchAxis.NONE,
                priority = descriptor?.priority ?: 0,
                density = density
            )
        }

        // Rust computes layout in dp, convert result to pixels for Android
        val requestedSize = NativeBindings.waterui_layout_size_that_fits(layoutPtr, parentProposal, subviews)
        val measuredWidth = requestedSize.width.dpToPx().resolveDimension(constraints.minWidth, constraints.maxWidth)
        val measuredHeight = requestedSize.height.dpToPx().resolveDimension(constraints.minHeight, constraints.maxHeight)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        require(layoutPtr != 0L) { "onLayout called with null layout pointer" }

        // Nothing to layout for empty containers
        if (childCount == 0) {
            return
        }

        // Convert pixel bounds to dp for Rust layout engine
        val bounds = RectStruct(
            x = 0f,
            y = 0f,
            width = (right - left).toFloat().pxToDp(),
            height = (bottom - top).toFloat().pxToDp()
        )

        // Create SubViewStruct array for placement
        // Pass density so child measurements can convert between dp and pixels
        val subviews = Array(childCount) { index ->
            val child = getChildAt(index)
            val descriptor = descriptors.getOrNull(index)
            SubViewStruct(
                view = child,
                stretchAxis = descriptor?.stretchAxis ?: StretchAxis.NONE,
                priority = descriptor?.priority ?: 0,
                density = density
            )
        }

        // Rust returns placements in dp, convert to pixels for Android layout
        val placements = NativeBindings.waterui_layout_place(layoutPtr, bounds, subviews)

        for (index in 0 until childCount) {
            val rect = placements[index]
            val child = getChildAt(index)

            // Convert dp to pixels
            val allocatedWidth = rect.width.dpToPx().roundToInt()
            val allocatedHeight = rect.height.dpToPx().roundToInt()

            // Re-measure child at allocated size if different from measured size.
            // This is critical for StretchAxis::Horizontal components (TextField, Slider, etc.)
            // which report minimum width during size_that_fits but expand during place.
            if (child.measuredWidth != allocatedWidth || child.measuredHeight != allocatedHeight) {
                child.measure(
                    View.MeasureSpec.makeMeasureSpec(allocatedWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(allocatedHeight, View.MeasureSpec.EXACTLY)
                )
            }

            // Convert dp positions to pixels
            val childLeft = rect.x.dpToPx().roundToInt()
            val childTop = rect.y.dpToPx().roundToInt()
            val childRight = childLeft + allocatedWidth
            val childBottom = childTop + allocatedHeight
            child.layout(childLeft, childTop, childRight, childBottom)
        }
    }

    /**
     * WaterUI iOS-like hit-testing for ZStack behavior.
     *
     * Performs hit-testing to find the deepest interactive view at a touch point,
     * checking children from top to bottom (last to first in child order).
     * If no interactive view is found in any child, the touch passes through.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            android.util.Log.d("WaterUI", "Touch.RustLayoutViewGroup: childCount=$childCount, point=(${ev.x.toInt()}, ${ev.y.toInt()})")
        }

        // iOS-like hit-testing: find the child containing an interactive view
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            // Check if point is within child bounds
            if (!isPointInView(child, ev.x, ev.y)) {
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    android.util.Log.d("WaterUI", "  [$i] ${child.javaClass.simpleName}: not in bounds")
                }
                continue
            }

            // Transform to child coordinates
            val childX = ev.x - child.left
            val childY = ev.y - child.top

            // Check if this child has an interactive view at this point
            val interactiveView = PassThroughFrameLayout.findInteractiveViewIn(child, childX, childY)

            if (ev.action == MotionEvent.ACTION_DOWN) {
                android.util.Log.d("WaterUI.Touch", "  [$i] ${child.javaClass.simpleName}: interactive=${interactiveView?.javaClass?.simpleName ?: "null"}")
            }

            if (interactiveView != null) {
                // This child has an interactive target - dispatch to it
                val childEvent = MotionEvent.obtain(ev)
                childEvent.offsetLocation(-child.left.toFloat(), -child.top.toFloat())
                val handled = child.dispatchTouchEvent(childEvent)
                childEvent.recycle()

                if (ev.action == MotionEvent.ACTION_DOWN) {
                    android.util.Log.d("WaterUI.Touch", "  [$i] dispatched, handled=$handled")
                }

                if (handled) return true
            }
            // No interactive view in this child, continue to next child (pass-through)
        }

        // No interactive view found in any child
        if (ev.action == MotionEvent.ACTION_DOWN) {
            android.util.Log.d("WaterUI.Touch", "  No interactive view found")
        }
        return false
    }

    private fun isPointInView(view: View, x: Float, y: Float): Boolean {
        return x >= view.left && x < view.right && y >= view.top && y < view.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
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

/** Convert pixel constraints to dp for Rust layout engine */
private fun LayoutConstraints.toProposalStruct(density: Float): ProposalStruct = ProposalStruct(
    width = if (maxWidth != Int.MAX_VALUE) maxWidth.toFloat() / density else Float.NaN,
    height = if (maxHeight != Int.MAX_VALUE) maxHeight.toFloat() / density else Float.NaN
)

private fun Float.resolveDimension(min: Int, max: Int): Int {
    if (isNaN()) {
        return if (max == Int.MAX_VALUE) min else max
    }
    val rounded = roundToInt().coerceAtLeast(0)
    if (max == Int.MAX_VALUE) return rounded.coerceAtLeast(min)
    return rounded.coerceIn(min, max)
}
