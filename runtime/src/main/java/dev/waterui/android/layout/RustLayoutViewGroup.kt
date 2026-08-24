package dev.waterui.android.layout

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Space
import androidx.core.view.isEmpty
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiPrimaryContentProviding
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.RectStruct
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.SubViewStruct
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.disposeAndRemoveView
import dev.waterui.android.runtime.disposeWith
import kotlin.math.roundToInt

/**
 * Android [ViewGroup] that mirrors the Swift/Compose Rust layout bridge.
 *
 * Measurement and placement are delegated to the Rust layout engine via JNI.
 * Uses the new 2-phase layout system:
 * 1. `size_that_fits` - Rust calls back to measure children as needed
 * 2. `place` - Returns final positions for all children
 */
@SuppressLint("ViewConstructor")
class RustLayoutViewGroup(
    context: Context,
    private val layoutPtr: Long,
    private var descriptors: List<ChildDescriptor> = emptyList()
) : ViewGroup(context), WuiPrimaryContentProviding {
    /// A stack answers window-root questions with its base layer: the window
    /// composes overlay layers above the content, and a layer stacked above the
    /// content never changes how the window insets it.
    override val wuiPrimaryContent: View?
        get() = if (childCount == 0) null else getChildAt(0)

    private val layoutWatcher = NativeBindings.waterui_layout_watch_invalidation(layoutPtr, this)

    init {
        // Match UIKit/SwiftUI default behavior: allow shadows/overlays to draw outside bounds.
        clipChildren = false
        clipToPadding = false
        disposeWith {
            NativeBindings.waterui_layout_watcher_drop(layoutWatcher)
            NativeBindings.waterui_drop_layout(layoutPtr)
        }
    }

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

    private var cachedSubviews: Array<SubViewStruct> = emptyArray()
    private val scratchProposal = ProposalStruct(width = Float.NaN, height = Float.NaN)
    private val scratchBounds = RectStruct(x = 0f, y = 0f, width = 0f, height = 0f)

    private fun resolveSubviews(): Array<SubViewStruct> {
        check(descriptors.size == childCount) {
            "Rust layout descriptor count ${descriptors.size} does not match child count $childCount"
        }
        if (cachedSubviews.size != childCount || subviewsOutdated()) {
            cachedSubviews = Array(childCount) { index ->
                val descriptor = descriptors[index]
                SubViewStruct(
                    view = getChildAt(index),
                    stretchAxis = descriptor.stretchAxis,
                    priority = descriptor.priority,
                    density = density
                )
            }
        }
        return cachedSubviews
    }

    private fun subviewsOutdated(): Boolean {
        for (index in 0 until cachedSubviews.size) {
            if (cachedSubviews[index].view !== getChildAt(index)) {
                return true
            }
        }
        return false
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        cachedSubviews = emptyArray()
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        cachedSubviews = emptyArray()
    }

    /**
     * Reconciles the children to exactly [ordered] (in that order), reusing the
     * existing [View] instances already attached for unchanged entries instead of
     * recreating them. Views attached but not in [ordered] are removed; new views
     * are inserted at their target index; surviving views are moved into order.
     *
     * A reused child's identity is preserved, so its in-flight animations,
     * focus, and accessibility node survive a membership change of the
     * surrounding collection (`ForEach`/`List` reconcile).
     */
    fun reconcileChildren(ordered: List<View>, newDescriptors: List<ChildDescriptor>) {
        descriptors = newDescriptors
        // 1. Detach any currently-attached child that is no longer wanted.
        for (index in childCount - 1 downTo 0) {
            val existing = getChildAt(index)
            if (ordered.none { it === existing }) {
                disposeAndRemoveView(existing)
            }
        }
        // 2. Place each wanted child at its target index, reusing instances.
        ordered.forEachIndexed { index, child ->
            if (index < childCount && getChildAt(index) === child) {
                return@forEachIndexed
            }
            if (child.parent === this) {
                removeView(child)
            }
            addView(child, index)
        }
        cachedSubviews = emptyArray()
        requestLayout()
    }

    internal fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
        if (isEmpty()) {
            return ViewDimensionsStruct(SizeStruct(0f, 0f), emptyArray(), emptyArray())
        }
        val subviews = resolveSubviews()
        return NativeBindings.waterui_layout_measure(layoutPtr, proposal, subviews)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Empty containers should report zero size
        if (isEmpty()) {
            setMeasuredDimension(0, 0)
            return
        }

        val constraints = LayoutConstraints.fromMeasureSpecs(widthMeasureSpec, heightMeasureSpec)
        // Convert pixel constraints to dp for Rust layout engine
        scratchProposal.width = if (constraints.maxWidth != Int.MAX_VALUE) constraints.maxWidth.toFloat() / density else Float.NaN
        scratchProposal.height = if (constraints.maxHeight != Int.MAX_VALUE) constraints.maxHeight.toFloat() / density else Float.NaN

        // Create SubViewStruct array - Rust will call back to measure each child
        // Pass density so child measurements can convert between dp and pixels
        val subviews = resolveSubviews()

        // Rust computes layout in dp, convert result to pixels for Android
        val requestedSize = NativeBindings.waterui_layout_size_that_fits(layoutPtr, scratchProposal, subviews)
        val measuredWidth = requestedSize.width.dpToPx().resolveDimension(constraints.minWidth, constraints.maxWidth)
        val measuredHeight = requestedSize.height.dpToPx().resolveDimension(constraints.minHeight, constraints.maxHeight)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Nothing to layout for empty containers
        if (isEmpty()) {
            return
        }

        // Convert pixel bounds to dp for Rust layout engine
        scratchBounds.x = 0f
        scratchBounds.y = 0f
        scratchBounds.width = (right - left).toFloat().pxToDp()
        scratchBounds.height = (bottom - top).toFloat().pxToDp()

        // Create SubViewStruct array for placement
        // Pass density so child measurements can convert between dp and pixels
        val subviews = resolveSubviews()

        // Rust returns placements in dp, convert to pixels for Android layout
        val placements = NativeBindings.waterui_layout_place(layoutPtr, scratchBounds, subviews)

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
        // iOS-like hit-testing: find the child containing an interactive view
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            // Check if point is within child bounds
            if (!isPointInView(child, ev.x, ev.y)) {
                continue
            }

            // Transform to child coordinates
            val childX = ev.x - child.left
            val childY = ev.y - child.top

            // Check if this child has an interactive view at this point
            val interactiveView = PassThroughFrameLayout.findInteractiveViewIn(child, childX, childY)

            if (interactiveView != null) {
                // This child has an interactive target - dispatch to it
                val childEvent = MotionEvent.obtain(ev)
                childEvent.offsetLocation(-child.left.toFloat(), -child.top.toFloat())
                val handled = child.dispatchTouchEvent(childEvent)
                childEvent.recycle()

                if (handled) return true
            }
            // No interactive view in this child, continue to next child (pass-through)
        }

        return false
    }

    private fun isPointInView(view: View, x: Float, y: Float): Boolean {
        return x >= view.left && x < view.right && y >= view.top && y < view.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return false
    }
}

data class ChildDescriptor(
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

private fun Float.resolveDimension(min: Int, max: Int): Int {
    if (isNaN()) {
        return if (max == Int.MAX_VALUE) min else max
    }
    val rounded = roundToInt().coerceAtLeast(0)
    if (max == Int.MAX_VALUE) return rounded.coerceAtLeast(min)
    return rounded.coerceIn(min, max)
}
