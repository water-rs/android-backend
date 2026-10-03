package dev.waterui.android.components

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.waterui.android.runtime.EdgeInsetsStruct
import dev.waterui.android.runtime.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The row metrics water-rs/waterui#1249 carries on `ListItem` and `List`:
 * a per-row inset between the card's edges and the content, and a height
 * floor replacing the theme's one-line height. A row without either keeps
 * today's metrics — no padding and the 48 dp floor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListRowMetricsTest {
    private val context = ContextThemeWrapper(
        RuntimeEnvironment.getApplication(),
        com.google.android.material.R.style.Theme_Material3_DayNight
    )

    private fun holder(): ListItemHolder = createListRowViews(context, horizontalMargin = 0)

    private fun px(dp: Float): Int = dp.dp(context).toInt()

    private fun contentOf(heightPx: Int): View = View(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            heightPx
        )
    }

    /** Measures the card's content at a fixed width with an unbounded height. */
    private fun measuredHeight(holder: ListItemHolder): Int {
        holder.content.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return holder.content.measuredHeight
    }

    @Test
    fun defaultMetricsKeepTheThemeRow() {
        val bound = holder()
        bindListRowMetrics(bound, insets = null, minRowHeightPx = null, context)

        assertEquals(0, bound.content.paddingTop)
        assertEquals(0, bound.content.paddingBottom)
        assertEquals(0, bound.content.paddingStart)
        assertEquals(0, bound.content.paddingEnd)
        assertEquals(px(48f), bound.content.minimumHeight)
    }

    @Test
    fun rowInsetsPadTheContentInsideTheCard() {
        val bound = holder()
        bindListRowMetrics(
            bound,
            EdgeInsetsStruct(top = 8f, leading = 16f, bottom = 4f, trailing = 20f),
            minRowHeightPx = null,
            context
        )

        assertEquals(px(8f), bound.content.paddingTop)
        assertEquals(px(4f), bound.content.paddingBottom)
        assertEquals(px(16f), bound.content.paddingStart)
        assertEquals(px(20f), bound.content.paddingEnd)
    }

    @Test
    fun zeroMinRowHeightSizesTheRowToContentPlusInsets() {
        val bound = holder()
        bindListRowMetrics(
            bound,
            EdgeInsetsStruct(top = 4f, leading = 0f, bottom = 6f, trailing = 0f),
            minRowHeightPx = 0,
            context
        )
        bound.content.addView(contentOf(40))

        assertEquals(40 + px(4f) + px(6f), measuredHeight(bound))
    }

    @Test
    fun minRowHeightAboveContentFloorsTheRow() {
        val bound = holder()
        bindListRowMetrics(
            bound,
            insets = null,
            minRowHeightPx = px(96f),
            context
        )
        bound.content.addView(contentOf(40))

        assertEquals(px(96f), measuredHeight(bound))
    }

    @Test
    fun minRowHeightBelowContentLeavesTheContentHeight() {
        val bound = holder()
        bindListRowMetrics(
            bound,
            insets = null,
            minRowHeightPx = px(20f),
            context
        )
        bound.content.addView(contentOf(40))

        assertEquals(40, measuredHeight(bound))
    }

    @Test
    fun minRowHeightCoversTheRowInsets() {
        val bound = holder()
        bindListRowMetrics(
            bound,
            EdgeInsetsStruct(top = 4f, leading = 0f, bottom = 6f, trailing = 0f),
            minRowHeightPx = px(96f),
            context
        )
        bound.content.addView(contentOf(40))

        // The floor measures the whole row — content and its insets — not the
        // content alone, matching `content + insets` vs the floor.
        assertEquals(px(96f), measuredHeight(bound))
    }
}
