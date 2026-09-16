package dev.waterui.android.layout

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Space
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.WuiLiveSlotTraits
import dev.waterui.android.runtime.WuiMeasurableLayout
import dev.waterui.android.runtime.WuiProposalAware
import dev.waterui.android.runtime.getWuiLayoutPriority
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.hasWuiSlotIdentity
import dev.waterui.android.runtime.measureForProposal

/**
 * A FrameLayout that implements WaterUI's iOS-like hit-testing behavior.
 *
 * In iOS/UIKit, `hitTest(_:with:)` finds the deepest interactive view at a point.
 * Non-interactive views return `nil`, allowing touches to pass through to views behind.
 *
 * This class replicates that behavior on Android:
 * - First performs hit-testing to find if there's an interactive view at the touch point
 * - If no interactive view is found, returns `false` to let the touch pass through
 * - Only dispatches touches if an interactive target exists
 *
 * @see dev.waterui.android.layout.RustLayoutViewGroup
 */
open class PassThroughFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), WuiProposalAware, WuiMeasurableLayout, WuiLiveSlotTraits {

    private val density: Float = context.resources.displayMetrics.density

    /**
     * The proposal a WaterUI parent selected for this wrapper, held so it can
     * be handed to the content — including content inflated after the
     * selection arrived, as a `Dynamic` swapping its child does. The wrapper
     * is transparent to layout: whatever it was offered is what its content
     * was offered.
     */
    private var selectedProposal: ProposalStruct? = null

    /**
     * The child whose WaterUI slot this wrapper stands in: the one carrying a
     * WaterUI layout identity — a live slot-traits implementation, a Rust
     * layout it can measure, or the stretch tag inflation stamps. Auxiliary
     * children a host adds for itself (media or capture surfaces) carry no
     * WaterUI identity and are never the content; a wrapper holding none —
     * an empty `Dynamic`, say — answers for itself.
     */
    internal val wuiLayoutContent: View?
        get() {
            var content: View? = null
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (!child.hasWuiSlotIdentity()) continue
                check(content == null) {
                    "${javaClass.name} holds more than one WaterUI content child"
                }
                content = child
            }
            return content
        }

    override fun resolveWuiStretchAxis(): StretchAxis {
        return wuiLayoutContent?.getWuiStretchAxis()
            ?: getTag(TAG_STRETCH_AXIS) as? StretchAxis
            ?: error("WaterUI wrapper ${javaClass.name} holds no content and no stretch answer of its own")
    }

    override fun resolveWuiLayoutPriority(): Int {
        return wuiLayoutContent?.getWuiLayoutPriority() ?: 0
    }

    /**
     * Answers a Rust layout probe as the content would.
     *
     * The wrapper is transparent to layout, so the probe belongs to the
     * content: forwarded verbatim it keeps an unbounded axis (infinity)
     * distinct from an unspecified one (NaN) and carries the content's
     * alignment guides back — both lost the moment the probe is squeezed
     * through a MeasureSpec. Content that cannot answer probes itself is
     * measured under the spec the proposal spells — the content, never the
     * wrapper, so an overlay sibling cannot inflate the answer to the whole
     * offer.
     */
    override fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
        val content = wuiLayoutContent ?: return measureForProposal(proposal, density)
        return when (content) {
            is WuiMeasurableLayout -> content.measureForLayout(proposal)
            else -> content.measureForProposal(proposal, density)
        }
    }

    override fun setWuiSelectedProposal(proposalWidth: Float, proposalHeight: Float) {
        selectedProposal = ProposalStruct(proposalWidth, proposalHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // A host re-measure supersedes the proposal selected for the previous
        // pass; the next selection arrives before layout.
        selectedProposal = null
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Forward after every measure and before the content is laid out, so
        // a child that hosts WaterUI content negotiates under the same
        // proposal this wrapper was offered.
        val proposal = selectedProposal
        if (proposal != null) {
            for (index in 0 until childCount) {
                (getChildAt(index) as? WuiProposalAware)
                    ?.setWuiSelectedProposal(proposal.width, proposal.height)
            }
        }
        super.onLayout(changed, left, top, right, bottom)
    }

    init {
        // Match UIKit/SwiftUI default behavior: allow shadows/overlays to draw outside bounds.
        clipChildren = false
        clipToPadding = false
    }

    /**
     * When true, this view itself is interactive and will consume touches.
     * Set this when a gesture handler is attached to this view.
     */
    var consumesTouches: Boolean = false

    /**
     * Decides pass-through once, when a gesture starts, and keeps the rest of it
     * going to the same place. See [GestureRouter] for why that matters.
     */
    private val gestures = GestureRouter(
        capture = { event ->
            val interactive =
                consumesTouches || findInteractiveViewAt(event.x, event.y) != null
            // Only claim the gesture if something under here actually took it.
            if (interactive && dispatchToChildren(event)) this else null
        },
        deliver = { _, event -> dispatchToChildren(event) },
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = gestures.dispatch(ev)

    private fun dispatchToChildren(ev: MotionEvent): Boolean = super.dispatchTouchEvent(ev)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!consumesTouches) {
            return false
        }

        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        return handled
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    /**
     * iOS-like hit-testing: recursively find the deepest interactive view at a point.
     * Returns null if no interactive view is found (touch should pass through).
     */
    private fun findInteractiveViewAt(x: Float, y: Float): View? {
        // Check children in reverse order (top to bottom in Z-order)
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            // Check if point is within child bounds
            if (!isPointInsideView(child, x, y)) continue

            // Transform to child coordinates
            val childX = x - child.left
            val childY = y - child.top

            // Recursively check child
            val target = findInteractiveViewIn(child, childX, childY)
            if (target != null) return target
        }

        return null
    }

    companion object {
        /**
         * Recursively find an interactive view at the given coordinates within a view.
         */
        fun findInteractiveViewIn(view: View, x: Float, y: Float): View? {
            // If view is not visible, no hit
            if (view.visibility != View.VISIBLE) return null

            // If it's a ViewGroup, check children first (deepest interactive view wins)
            if (view is ViewGroup) {
                for (i in view.childCount - 1 downTo 0) {
                    val child = view.getChildAt(i)
                    if (child.visibility != View.VISIBLE) continue

                    // Check if point is within child bounds
                    if (!isPointInsideView(child, x, y)) continue

                    // Transform to child coordinates
                    val childX = x - child.left
                    val childY = y - child.top

                    val target = findInteractiveViewIn(child, childX, childY)
                    if (target != null) return target
                }
            }

            // Check if this view itself is interactive
            if (isViewInteractive(view)) {
                return view
            }

            return null
        }

        /**
         * Check if a view is interactive (should receive touches).
         *
         * A view is interactive if it:
         * - Is clickable or long-clickable
         * - Is focusable (for input fields)
         * - Has click listeners
         * - Is marked as wanting touch events (via tag)
         * - Is not a Space/Spacer (which are explicitly non-interactive)
         */
        fun isViewInteractive(view: View): Boolean {
            // Space/Spacer is never interactive
            if (view is Space) return false

            // Check standard interactive properties
            if (view.isClickable || view.isLongClickable) return true
            if (view.isFocusable) return true

            // Check if it has click listeners
            if (view.hasOnClickListeners()) return true

            // Check for our custom "wants touches" tag
            // Views with touch listeners should set this tag
            if (view.getTag(TAG_WANTS_TOUCHES) == true) return true

            return false
        }

        private fun isPointInsideView(view: View, x: Float, y: Float): Boolean {
            return x >= view.left && x < view.right && y >= view.top && y < view.bottom
        }

        /** Tag key for marking views that want to receive touch events */
        val TAG_WANTS_TOUCHES: Int get() = dev.waterui.android.runtime.R.id.wui_wants_touches
    }
}
