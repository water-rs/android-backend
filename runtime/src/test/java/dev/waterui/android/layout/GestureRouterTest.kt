package dev.waterui.android.layout

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.google.android.material.slider.Slider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A gesture belongs to whoever took its `ACTION_DOWN`. WaterUI's containers used
 * to re-run their UIKit-style pass-through hit-test for every event, so a drag
 * that wandered off the control it started on simply stopped being delivered:
 * no release, no cancel. Issue #402 is what that costs — a Material slider left
 * pressed, with its value label painted over the screen indefinitely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GestureRouterTest {
    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 120
        const val INSIDE_X = 200f
        const val INSIDE_Y = 60f

        /** Well outside the child, the way a finger drifts off a slider. */
        const val OUTSIDE_X = 380f
        const val OUTSIDE_Y = 600f
    }

    /** A child that takes touches and remembers every action it was handed. */
    private class RecordingView(context: Context) : View(context) {
        val actions = mutableListOf<Int>()

        init {
            isClickable = true
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            actions.add(event.actionMasked)
            return true
        }
    }

    private fun context(): Context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        com.google.android.material.R.style.Theme_Material3_DayNight,
    )

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    private fun laidOut(container: ViewGroup, child: View): ViewGroup {
        container.addView(child, ViewGroup.LayoutParams(WIDTH, HEIGHT))
        container.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        container.layout(0, 0, WIDTH, HEIGHT)
        child.layout(0, 0, WIDTH, HEIGHT)
        return container
    }

    @Test
    fun aDragThatWandersOffTheControlStillDeliversItsRelease() {
        val child = RecordingView(context())
        val container = laidOut(PassThroughFrameLayout(context()), child)

        assertTrue(container.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, INSIDE_X, INSIDE_Y)))
        container.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, OUTSIDE_X, OUTSIDE_Y))
        container.dispatchTouchEvent(event(MotionEvent.ACTION_UP, OUTSIDE_X, OUTSIDE_Y))

        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            child.actions,
        )
    }

    @Test
    fun aCancelOutsideTheControlIsDeliveredTheSameWay() {
        val child = RecordingView(context())
        val container = laidOut(PassThroughFrameLayout(context()), child)

        container.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, INSIDE_X, INSIDE_Y))
        container.dispatchTouchEvent(event(MotionEvent.ACTION_CANCEL, OUTSIDE_X, OUTSIDE_Y))

        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL),
            child.actions,
        )
    }

    @Test
    fun aTouchThatStartsOnNothingInteractiveStillPassesThrough() {
        val inert = View(context())
        val container = laidOut(PassThroughFrameLayout(context()), inert)

        assertFalse(container.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, INSIDE_X, INSIDE_Y)))
        // The gesture was never ours, so nothing after its start is either.
        assertFalse(container.dispatchTouchEvent(event(MotionEvent.ACTION_UP, INSIDE_X, INSIDE_Y)))
    }

    @Test
    fun theGestureIsReleasedSoTheNextOneHitTestsAfresh() {
        val child = RecordingView(context())
        val container = laidOut(PassThroughFrameLayout(context()), child)

        container.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, INSIDE_X, INSIDE_Y))
        container.dispatchTouchEvent(event(MotionEvent.ACTION_UP, OUTSIDE_X, OUTSIDE_Y))
        child.actions.clear()
        child.visibility = View.GONE

        assertFalse(container.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, INSIDE_X, INSIDE_Y)))
        assertEquals(emptyList<Int>(), child.actions)
    }

    @Test
    fun aTargetTakenAwayMidGestureStopsReceivingIt() {
        val child = RecordingView(context())
        val router = GestureRouter(capture = { child }, deliver = { _, _ -> true })

        router.dispatch(event(MotionEvent.ACTION_DOWN, INSIDE_X, INSIDE_Y))
        assertSame(child, router.current)

        router.forget(child)

        assertNull(router.current)
        assertFalse(router.dispatch(event(MotionEvent.ACTION_UP, INSIDE_X, INSIDE_Y)))
    }

    /**
     * The end of issue #402: a slider drops its pressed state on release, and it
     * is that state change which takes its value label back down.
     */
    @Test
    fun aSliderDraggedOffItselfStillEndsPressedOnRelease() {
        val slider = Slider(context()).apply {
            valueFrom = 0f
            valueTo = 1f
            stepSize = 0f
            value = 0f
        }
        val container = laidOut(PassThroughFrameLayout(context()), slider)

        container.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, INSIDE_X, INSIDE_Y))
        container.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, INSIDE_X + 40f, INSIDE_Y))
        assertTrue(slider.isPressed)

        container.dispatchTouchEvent(event(MotionEvent.ACTION_UP, OUTSIDE_X, OUTSIDE_Y))

        assertFalse(slider.isPressed)
    }
}
