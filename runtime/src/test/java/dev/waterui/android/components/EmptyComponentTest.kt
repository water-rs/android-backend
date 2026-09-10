package dev.waterui.android.components

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import dev.waterui.android.layout.AxisExpandingLinearLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How much room WaterUI's empty view takes, and what it costs when it takes
 * more than none.
 *
 * A control renders its label above itself and hides that label by rendering
 * the empty view in its place. Android offers a child a size two ways: AT_MOST
 * says "up to this much" and EXACTLY says "be this". `View`'s own measurement
 * answers both with the size in the spec, so a hidden label took the whole
 * height its row offered the control and left the control itself below the
 * bottom of it — painted, since a WaterUI container never clips what it holds,
 * but out of reach of both touch and accessibility.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmptyComponentTest {
    private companion object {
        const val OFFERED = 2000
        const val ALLOCATED = 120
    }

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        }

    private fun atMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)

    private fun unspecified(): Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    /** The row a label-less [SeekBarSlider] renders as: hidden label above the track. */
    private fun labellessSliderRow(context: Context): AxisExpandingLinearLayout =
        AxisExpandingLinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(WuiEmptyView(context))
            addView(
                SeekBarSlider(context).apply {
                    valueFrom = 0f
                    valueTo = 10f
                    stepSize = 0f
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

    @Test
    fun emptyViewDeclinesRoomItIsMerelyOffered() {
        val empty = WuiEmptyView(activity())

        empty.measure(atMost(OFFERED), atMost(OFFERED))

        assertEquals("an empty view offered room takes none of it", 0, empty.measuredWidth)
        assertEquals("an empty view offered room takes none of it", 0, empty.measuredHeight)
    }

    @Test
    fun emptyViewAsksForNothingWhenNothingIsProposed() {
        val empty = WuiEmptyView(activity())

        empty.measure(unspecified(), unspecified())

        assertEquals("an empty view asked its ideal size names none", 0, empty.measuredWidth)
        assertEquals("an empty view asked its ideal size names none", 0, empty.measuredHeight)
    }

    @Test
    fun emptyViewTakesTheSizeItIsAllocated() {
        val empty = WuiEmptyView(activity())

        empty.measure(exactly(ALLOCATED), exactly(ALLOCATED))

        assertEquals("an allocation is not an offer", ALLOCATED, empty.measuredWidth)
        assertEquals("an allocation is not an offer", ALLOCATED, empty.measuredHeight)
    }

    @Test
    fun labellessSliderRowIsAsTallAsItsSlider() {
        val context = activity()
        val row = labellessSliderRow(context)
        val slider = row.getChildAt(1)

        row.measure(atMost(OFFERED), atMost(OFFERED))

        assertEquals(
            "the row is its slider, not the height a stack happened to offer it",
            slider.measuredHeight,
            row.measuredHeight
        )
    }
}
