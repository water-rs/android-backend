package dev.waterui.android.layout

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Space

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
) : FrameLayout(context, attrs, defStyleAttr) {

    /**
     * When true, this view itself is interactive and will consume touches.
     * Set this when a gesture handler is attached to this view.
     */
    var consumesTouches: Boolean = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // iOS-like hit-testing: first check if there's an interactive view at this point
        val hasInteractiveTarget = consumesTouches || findInteractiveViewAt(ev.x, ev.y) != null

        if (!hasInteractiveTarget) {
            // No interactive view found - let touch pass through to views behind us
            return false
        }

        // There's an interactive target, dispatch normally
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return consumesTouches && super.onTouchEvent(event)
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
            if (x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue

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
                    if (x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue

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

        /** Tag key for marking views that want to receive touch events */
        const val TAG_WANTS_TOUCHES = 0x57554902 // "WUI\x02"
    }
}
