package dev.waterui.android.layout

import android.view.MotionEvent
import android.view.View

/**
 * Routes a whole gesture to whoever accepted its `ACTION_DOWN`.
 *
 * WaterUI's containers hit-test the way UIKit does: a touch that lands on
 * nothing interactive passes through to whatever is behind. That question is
 * about where a gesture *starts*, and asking it again for every event is what
 * this type exists to prevent. Android delivers a gesture to the view that took
 * its `ACTION_DOWN` however far the finger then wanders, and a control relies on
 * it: a Material slider ends its drag, drops its pressed state and takes down
 * its value label only when it sees the release. A container that re-decides per
 * event stops delivering the moment the finger drifts off the control — no
 * release, no cancel, and the slider stays pressed with its value label painted
 * over the screen long after the finger is gone.
 *
 * [capture] hit-tests an `ACTION_DOWN`, hands it to whichever target accepts it
 * and returns that target, or `null` to let the gesture pass through.
 * [deliver] hands one later event of the gesture to the target it captured.
 */
internal class GestureRouter(
    private val capture: (MotionEvent) -> View?,
    private val deliver: (View, MotionEvent) -> Boolean,
) {
    private var target: View? = null

    /** The target holding the gesture in flight, if one is. */
    val current: View? get() = target

    fun dispatch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            target = capture(event)
            return target != null
        }

        // Not ours: this gesture passed through at its start.
        val holder = target ?: return false
        val handled = deliver(holder, event)
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            target = null
        }
        return handled
    }

    /**
     * Drops [candidate] if it is holding the gesture, for when a reactive
     * rebuild takes the target away mid-gesture: the rest of that gesture then
     * has nowhere to go, rather than a view that has left the hierarchy.
     */
    fun forget(candidate: View) {
        if (target === candidate) {
            target = null
        }
    }
}
