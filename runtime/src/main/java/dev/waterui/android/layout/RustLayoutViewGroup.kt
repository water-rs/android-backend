package dev.waterui.android.layout

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.isEmpty
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiLiveSlotTraits
import dev.waterui.android.runtime.WuiMeasurableLayout
import dev.waterui.android.runtime.WuiProposalAware
import dev.waterui.android.runtime.WuiSafeAreaManaging
import dev.waterui.android.runtime.applyRemainingInsets
import dev.waterui.android.runtime.getWuiLayoutPriority
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.handlesSafeArea
import dev.waterui.android.runtime.measureSpecToProposalPx
import dev.waterui.android.runtime.mayFillHorizontal
import dev.waterui.android.runtime.mayFillVertical
import dev.waterui.android.runtime.proposalToMeasureSpec
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.RectStruct
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.SubViewStruct
import dev.waterui.android.runtime.SubviewPlacementStruct
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.disposeAndRemoveView
import dev.waterui.android.runtime.disposeWith
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Android [ViewGroup] that mirrors the Swift/Compose Rust layout bridge.
 *
 * Measurement and placement are delegated to the Rust layout engine via JNI.
 * Uses the new 2-phase layout system:
 * 1. `size_that_fits` - Rust calls back to measure children as needed
 * 2. `place` - Returns each child's frame together with the proposal the
 *    layout selected for it; the selected proposal is delivered to any child
 *    that hosts WaterUI content of its own so nested layouts negotiate under
 *    the same offer rather than one reconstructed from the frame.
 */
@SuppressLint("ViewConstructor")
class RustLayoutViewGroup(
    context: Context,
    private val layoutPtr: Long
) : ViewGroup(context), WuiSafeAreaManaging, WuiProposalAware, WuiMeasurableLayout, WuiLiveSlotTraits {
    /// The insets no ancestor has consumed. The children are laid out inside
    /// them; a child that handles the safe area itself and touches an edge of
    /// that area is extended to the bounds on that edge and handed the inset
    /// it now covers, so a scroll surface beside a backdrop reaches the chrome
    /// and pads its own content while the backdrop stays inside.
    private var safeArea = Insets.NONE

    override fun applySafeArea(insets: Insets) {
        if (safeArea == insets) return
        safeArea = insets
        requestLayout()
    }

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

    /**
     * Drops the SubView bridge objects — and every memoized probe answer they
     * carry. Called when the content behind this layout's children may have
     * changed; the next [resolveSubviews] rebuilds the array and negotiates
     * from scratch.
     */
    internal fun dropMeasurementMemos() {
        cachedSubviews = emptyArray()
    }

    /**
     * A memoized probe answer is only valid while the content behind it is
     * unchanged, and a descendant's content change invalidates the answers of
     * every ancestor holding this subtree in a slot. `View.requestLayout`
     * stops its upward walk at the first ancestor already flagged for layout,
     * so waiting for each ancestor's own `requestLayout` would leave stale
     * memos behind the moment propagation is absorbed — drop them all the way
     * up unconditionally, the same contract Apple's `invalidateLayoutHierarchy`
     * gives `cachedSubViews` in `WuiContainer`.
     */
    override fun requestLayout() {
        dropMeasurementMemos()
        dropAncestorMeasurementMemos()
        super.requestLayout()
    }

    /**
     * The offer the host environment measured this group under, in dp. It is
     * the proposal this group passes to its Rust layout when no WaterUI parent
     * selected one — the only place a bounded offer may originate. Every
     * proposal a WaterUI parent selects arrives through
     * [setWuiSelectedProposal] instead.
     */
    private val measuredProposal = ProposalStruct(width = Float.NaN, height = Float.NaN)

    /**
     * The proposal the enclosing Rust layout selected for this group, or null
     * when no WaterUI parent has selected one for the current pass. Cleared on
     * every measure so a stale selection can never outlive the offer that
     * produced it.
     */
    private var selectedProposal: ProposalStruct? = null

    private val scratchBounds = RectStruct(x = 0f, y = 0f, width = 0f, height = 0f)

    override fun setWuiSelectedProposal(proposalWidth: Float, proposalHeight: Float) {
        selectedProposal = ProposalStruct(proposalWidth, proposalHeight)
    }

    /**
     * The stretch answer this layout gives a WaterUI parent, computed from the
     * children it holds right now. `Layout::stretch_axis` takes the children
     * as an argument precisely so this is never a copy: the answer recorded at
     * inflation was read over a child set that did not exist yet, and one
     * refreshed only during this group's own layout pass reaches its parent a
     * pass late. Every read asks the live membership, so neither staleness is
     * possible.
     */
    override fun resolveWuiStretchAxis(): StretchAxis = StretchAxis.fromInt(
        NativeBindings.waterui_layout_stretch_axis(
            layoutPtr,
            IntArray(childCount) { getChildAt(it).getWuiStretchAxis().value }
        )
    )

    private fun resolveSubviews(): Array<SubViewStruct> {
        if (cachedSubviews.size != childCount || subviewsOutdated()) {
            cachedSubviews = Array(childCount) { index ->
                val child = getChildAt(index)
                SubViewStruct(
                    view = child,
                    stretchAxis = child.getWuiStretchAxis(),
                    priority = child.getWuiLayoutPriority(),
                    density = density
                )
            }
        }
        return cachedSubviews
    }

    private fun subviewsOutdated(): Boolean {
        for (index in 0 until cachedSubviews.size) {
            val child = getChildAt(index)
            if (cachedSubviews[index].view !== child ||
                cachedSubviews[index].stretchAxis != child.getWuiStretchAxis() ||
                cachedSubviews[index].priority != child.getWuiLayoutPriority()
            ) {
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
        gestures.forget(child)
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
    fun reconcileChildren(ordered: List<View>) {
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

    override fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
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

        // A flagged pass negotiates from scratch: the flag may have been
        // raised while this group was already flagged — absorbed before the
        // requestLayout override ran — leaving memos repopulated between the
        // two invalidations stale.
        if (isLayoutRequested) {
            dropMeasurementMemos()
        }

        val constraints = LayoutConstraints.fromMeasureSpecs(widthMeasureSpec, heightMeasureSpec)
        // Convert pixel specs to the dp offer Rust sees. Only a spec naming a
        // bound is an offer, and only this host-derived proposal may create
        // one — a WaterUI parent's selection arrives through
        // setWuiSelectedProposal instead.
        measuredProposal.width = measureSpecToProposalPx(widthMeasureSpec).pxToDp()
        measuredProposal.height = measureSpecToProposalPx(heightMeasureSpec).pxToDp()
        // A host re-measure supersedes the proposal a WaterUI parent selected
        // for the previous pass; placement falls back to the measured offer
        // until the next selection arrives.
        selectedProposal = null

        // Create SubViewStruct array - Rust will call back to measure each child
        // Pass density so child measurements can convert between dp and pixels
        val subviews = resolveSubviews()

        // Rust computes layout in dp, convert result to pixels for Android
        val requestedSize = NativeBindings.waterui_layout_size_that_fits(layoutPtr, measuredProposal, subviews)
        val measuredWidth = requestedSize.width.dpToPx().resolveDimension(constraints.minWidth, constraints.maxWidth)
        val measuredHeight = requestedSize.height.dpToPx().resolveDimension(constraints.minHeight, constraints.maxHeight)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Nothing to layout for empty containers
        if (isEmpty()) {
            return
        }

        // A request-during-layout lands flagged here: the answers measured
        // earlier in this pass may predate it, so placement re-negotiates.
        if (isLayoutRequested) {
            dropMeasurementMemos()
        }

        // Convert pixel bounds to dp for Rust layout engine; the children are
        // laid out inside the safe area.
        val safeLeft = safeArea.left
        val safeTop = safeArea.top
        val safeRight = maxOf(safeLeft, right - left - safeArea.right)
        val safeBottom = maxOf(safeTop, bottom - top - safeArea.bottom)
        scratchBounds.x = safeLeft.toFloat().pxToDp()
        scratchBounds.y = safeTop.toFloat().pxToDp()
        scratchBounds.width = (safeRight - safeLeft).toFloat().pxToDp()
        scratchBounds.height = (safeBottom - safeTop).toFloat().pxToDp()

        // Create SubViewStruct array for placement
        // Pass density so child measurements can convert between dp and pixels
        val subviews = resolveSubviews()

        // The proposal this layout negotiated under: the one a WaterUI parent
        // selected for this group if there is one, else the offer the host
        // measured us with. It is passed to place unchanged — re-deriving it
        // from our bounds would invent a finite offer the layout never saw.
        val proposal = selectedProposal ?: measuredProposal
        val placements = NativeBindings.waterui_layout_place_subviews(layoutPtr, scratchBounds, proposal, subviews)
        check(placements.size == childCount) {
            "Rust layout placed ${placements.size} subviews for $childCount children"
        }

        for (index in 0 until childCount) {
            val placement = placements[index]
            val child = getChildAt(index)

            // Convert dp to pixels. Sizes round up: every dp<->px hop through a
            // nested container can shed a fraction of a pixel, and a child
            // allocated less than it measured wraps or clips its content — the
            // "Tap Me!" label that laid out 1px short and dropped "Me!" to a
            // clipped second line.
            val allocatedWidth = ceil(placement.width.dpToPx()).toInt()
            val allocatedHeight = ceil(placement.height.dpToPx()).toInt()

            // Measure the child under the proposal the layout selected for
            // it — the negotiation, not the frame.
            child.measureForPlacement(placement, density)

            // Convert dp positions to pixels
            var childLeft = placement.x.dpToPx().roundToInt()
            var childTop = placement.y.dpToPx().roundToInt()
            var childRight = childLeft + allocatedWidth
            var childBottom = childTop + allocatedHeight
            if (safeArea != Insets.NONE && handlesSafeArea(child)) {
                // Extend to the bounds on every edge the child touches, and hand
                // it the insets it now covers.
                val extendLeft = childLeft == safeLeft
                val extendTop = childTop == safeTop
                val extendRight = childRight == safeRight
                val extendBottom = childBottom == safeBottom
                if (extendLeft) childLeft = 0
                if (extendTop) childTop = 0
                if (extendRight) childRight = right - left
                if (extendBottom) childBottom = bottom - top
                applyRemainingInsets(
                    child,
                    Insets.of(
                        if (extendLeft) safeArea.left else 0,
                        if (extendTop) safeArea.top else 0,
                        if (extendRight) safeArea.right else 0,
                        if (extendBottom) safeArea.bottom else 0
                    )
                )
                if (childRight - childLeft != allocatedWidth || childBottom - childTop != allocatedHeight) {
                    child.measure(
                        View.MeasureSpec.makeMeasureSpec(childRight - childLeft, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(childBottom - childTop, View.MeasureSpec.EXACTLY)
                    )
                }
            }
            // Deliver the selected proposal after every re-measure, just
            // before the frame is applied: measuring resets a nested
            // container's selection.
            child.deliverSelectedProposal(placement)
            child.layout(childLeft, childTop, childRight, childBottom)
        }
    }

    /**
     * WaterUI iOS-like hit-testing for ZStack behavior.
     *
     * Picks the topmost child holding an interactive view at the touch point,
     * checking children from top to bottom (last to first in child order). If no
     * child has one, the touch passes through. That question is asked once, when
     * the gesture starts; see [GestureRouter] for why the rest of the gesture
     * must keep going to the same child.
     */
    private val gestures = GestureRouter(
        capture = { event ->
            var chosen: View? = null
            for (i in childCount - 1 downTo 0) {
                val child = getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                if (!isPointInView(child, event.x, event.y)) continue

                // Transform to child coordinates
                val childX = event.x - child.left
                val childY = event.y - child.top
                if (PassThroughFrameLayout.findInteractiveViewIn(child, childX, childY) == null) {
                    // No interactive view in this child, try the next (pass-through)
                    continue
                }
                if (dispatchToChild(child, event)) {
                    chosen = child
                    break
                }
            }
            chosen
        },
        deliver = ::dispatchToChild,
    )

    private fun dispatchToChild(child: View, event: MotionEvent): Boolean {
        val childEvent = MotionEvent.obtain(event)
        childEvent.offsetLocation(-child.left.toFloat(), -child.top.toFloat())
        val handled = child.dispatchTouchEvent(childEvent)
        childEvent.recycle()
        return handled
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gestures.dispatch(ev)

    private fun isPointInView(view: View, x: Float, y: Float): Boolean {
        return x >= view.left && x < view.right && y >= view.top && y < view.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return false
    }
}

/**
 * Measures a placed child under the proposal the Rust layout selected for it.
 *
 * The selected proposal is the contract placement negotiated: a finite offer
 * arrives as AT_MOST so the child answers with the size it actually takes — a
 * child whose minimum exceeds the offer keeps its size and the frame
 * overflows — while an axis the layout left unspecified (NaN) or probed
 * without bound (infinity) stays free. The resolved frame is never fed back
 * as the offer: a ZStack hands a child the frame it stretched to under a far
 * larger selected proposal, and re-measuring at the frame would pin the child
 * to it.
 *
 * The exception is an axis the child stretches to fill: there the frame is
 * the negotiated extent, and the measured size must agree with it. Android
 * resolves a view's internal layout at measure time — LinearLayout packs
 * weighted children at their measured extents and FrameLayout positions
 * content at its measured size — so a stretcher measured under AT_MOST keeps
 * its intrinsic answer and renders packed at the leading edge of a frame it
 * was allocated in full. Measuring the axis EXACTLY at the allocation makes
 * the measured size the extent `layout` is about to apply.
 */
internal fun View.measureForPlacement(placement: SubviewPlacementStruct, density: Float) {
    val stretchAxis = getWuiStretchAxis()
    measure(
        placementMeasureSpec(placement.proposalWidth, placement.width, density, stretchAxis.mayFillHorizontal()),
        placementMeasureSpec(placement.proposalHeight, placement.height, density, stretchAxis.mayFillVertical())
    )
}

/**
 * The spec one axis of a placed child is measured under: EXACTLY the
 * allocated frame extent on an axis the child fills, the selected proposal's
 * spec on an axis it does not.
 */
private fun placementMeasureSpec(
    proposalDp: Float,
    allocatedDp: Float,
    density: Float,
    fillsAxis: Boolean
): Int {
    if (!fillsAxis) {
        return proposalToMeasureSpec(proposalDp * density)
    }
    val allocated = ceil(allocatedDp * density).toInt().coerceAtLeast(0)
    return View.MeasureSpec.makeMeasureSpec(allocated, View.MeasureSpec.EXACTLY)
}

/**
 * Hands a placed child the proposal the Rust layout selected for it. The
 * selected proposal is part of the placement contract; it is delivered after
 * every re-measure and before the frame is applied, so a nested Rust layout
 * places its own children under the same offer the parent negotiated for it.
 */
internal fun View.deliverSelectedProposal(placement: SubviewPlacementStruct) {
    (this as? WuiProposalAware)?.setWuiSelectedProposal(placement.proposalWidth, placement.proposalHeight)
}

/**
 * Drops memoized probe answers on every [RustLayoutViewGroup] above this view.
 *
 * `View.requestLayout` stops propagating at the first ancestor already flagged
 * for layout — fine for scheduling a traversal, wrong for cache invalidation:
 * an ancestor's memoized answers about the slot holding this subtree are stale
 * either way. The walk is unconditional, matching Apple's
 * `invalidateLayoutHierarchy` which nils `cachedSubViews` on every ancestor.
 */
internal fun View.dropAncestorMeasurementMemos() {
    var ancestor = parent
    while (ancestor != null) {
        (ancestor as? RustLayoutViewGroup)?.dropMeasurementMemos()
        ancestor = ancestor.parent
    }
}

/**
 * The invalidation a view whose measured content changed must issue: flag the
 * layout pass through [View.requestLayout], and drop ancestor probe memos even
 * where propagation would have been absorbed by an already-flagged ancestor.
 */
internal fun View.invalidateWuiLayoutHierarchy() {
    dropAncestorMeasurementMemos()
    requestLayout()
}

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
    // Never under-report: a parent that allocates exactly what we report must
    // leave room for every pixel the children measured.
    val rounded = ceil(this).toInt().coerceAtLeast(0)
    if (max == Int.MAX_VALUE) return rounded.coerceAtLeast(min)
    return rounded.coerceIn(min, max)
}
