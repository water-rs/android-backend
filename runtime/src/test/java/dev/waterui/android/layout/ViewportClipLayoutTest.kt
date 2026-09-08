package dev.waterui.android.layout

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a scroll container says about the content it has scrolled away.
 *
 * The window root keeps ordinary content clear of the status bar by padding
 * itself, so a scroll container starts below that strip and the rows it scrolls
 * past the top pass behind it. Every WaterUI container lets its children draw
 * outside their own bounds, and Android spells that permission on the parent,
 * so without [ViewportClipLayout] the scroll container is free to paint those
 * rows into the strip and to answer that they are on screen there.
 *
 * The rect it answers with is the visible part of the row clamped one edge at a
 * time, which for a row entirely above the viewport puts the bottom above the
 * top. So the two halves are one defect, and the fix for both is that the
 * viewport clips: a row past the top is off screen, and every row that is on
 * screen is somewhere a rect can describe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ViewportClipLayoutTest {
    private companion object {
        const val WIDTH = 300

        /** Stands in for the status bar the window root pads the content away from. */
        const val INSET = 400
        const val VIEWPORT = 400
        const val ROW_HEIGHT = 100
        const val ROW_COUNT = 10

        /** Leaves rows 0 to 2 above the viewport and row 3 at the top of it. */
        const val SCROLL = 3 * ROW_HEIGHT
        const val FIRST_ROW_IN_VIEWPORT = 3
    }

    private fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        repeat(ROW_COUNT) { index ->
            addView(
                TextView(context).apply { text = "row $index" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW_HEIGHT)
            )
        }
    }

    /**
     * The rows of a scrolled window whose scroll container is hosted by [host].
     *
     * The container around it is a WaterUI container: it lets what it holds draw
     * outside its bounds, the way [RustLayoutViewGroup] and
     * [PassThroughFrameLayout] do.
     */
    private fun scrolledRows(host: (Context, ScrollView) -> View): List<View> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val scroll = ScrollView(activity).apply { addView(column(activity)) }
        val container = FrameLayout(activity).apply {
            clipChildren = false
            clipToPadding = false
            addView(
                host(activity, scroll),
                FrameLayout.LayoutParams(WIDTH, VIEWPORT).apply { topMargin = INSET }
            )
        }
        val height = INSET + VIEWPORT
        activity.setContentView(container, ViewGroup.LayoutParams(WIDTH, height))
        container.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        container.layout(0, 0, WIDTH, height)
        scroll.scrollTo(0, SCROLL)
        val rows = scroll.getChildAt(0) as ViewGroup
        return (0 until rows.childCount).map { rows.getChildAt(it) }
    }

    private fun clipped(): List<View> = scrolledRows { context, scroll ->
        ViewportClipLayout(context, scroll)
    }

    private fun onScreen(row: View): Boolean = row.getGlobalVisibleRect(Rect())

    private fun boundsInScreen(row: View): Rect = Rect().also { bounds ->
        row.createAccessibilityNodeInfo().getBoundsInScreen(bounds)
    }

    @Test
    fun rowScrolledPastTheTopOfTheViewportIsOffScreen() {
        val rows = clipped()

        assertFalse(
            "the first row is scrolled away, behind the strip the root inset",
            onScreen(rows[0])
        )
        assertTrue(
            "the row at the top of the viewport is still on screen",
            onScreen(rows[FIRST_ROW_IN_VIEWPORT])
        )
    }

    @Test
    fun everyRowOnScreenIsSomewhereARectCanDescribe() {
        clipped().filter(::onScreen).forEach { row ->
            val bounds = boundsInScreen(row)
            assertTrue(
                "$row is reported on screen at $bounds, whose bottom is above its top",
                bounds.bottom >= bounds.top && bounds.right >= bounds.left
            )
        }
    }
}
