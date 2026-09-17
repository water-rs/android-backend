package dev.waterui.android.components

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.layout.measureForPlacement
import dev.waterui.android.runtime.ProbeMemos
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.SubViewStruct
import dev.waterui.android.runtime.SubviewPlacementStruct
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The size a scroll reports to a Rust layout probe, and the frame its content
 * ends up in.
 *
 * The contract — written down in the Apple bridge's `scrollMinSize` — is that
 * a scroll claims the whole offer: a finite axis is answered with the
 * proposal itself, because the viewport spans it regardless of how wide the
 * content measures. Squeezing the probe through `MeasureSpec` loses that: a
 * `MATCH_PARENT` child under an AT_MOST offer is itself measured AT_MOST and
 * reports its natural size, so a parent that allocates `min(measured,
 * bounds)` — an overlay's base child, a stack cell — parks the whole viewport
 * at the content's natural width and its centred column hugs the leading
 * edge. Only a min-size (zero) query measures the content, reporting its
 * intrinsic extent on the cross axis so a parent can still learn the column's
 * natural width.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrollPlacementTest {
    private companion object {
        const val DENSITY = 2f
        const val CONTENT_WIDTH_PX = 200
        const val VIEWPORT_WIDTH_DP = 360f
        const val VIEWPORT_HEIGHT_DP = 800f
        const val VIEWPORT_WIDTH_PX = 720
        const val VIEWPORT_HEIGHT_PX = 1600
    }

    private fun context(): Context =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    /**
     * Mounts a vertical scroll over content that measures [CONTENT_WIDTH_PX]
     * naturally — a column of non-stretching rows — with the column wrapped
     * in the metadata pass-through every WaterUI container hands its slot to.
     * [marker] is centred inside whatever frame the column is given, the way
     * the stack centres its rows. Returns the marker, whose horizontal centre
     * is what a correct bridge must land on the viewport's.
     */
    private fun mountNarrowContent(context: Context): Pair<SafeAreaScrollViewport, View> {
        val marker = View(context)
        val column = FrameLayout(context)
        column.setTag(TAG_STRETCH_AXIS, StretchAxis.VERTICAL)
        column.addView(
            marker,
            FrameLayout.LayoutParams(CONTENT_WIDTH_PX, 100, Gravity.CENTER_HORIZONTAL)
        )
        val wrapper = PassThroughFrameLayout(context)
        wrapper.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val host = ScrollView(context)
        host.addView(
            wrapper,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        val viewport = SafeAreaScrollViewport(context, host, host, null)
        viewport.setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH)
        return viewport to marker
    }

    @Test
    fun narrowScrollContentCentresAcrossTheViewport() {
        val (viewport, marker) = mountNarrowContent(context())

        // The overlay above the scroll measures its base through the probe
        // channel and allocates min(measured, bounds): the scroll has to
        // answer the full offer or the allocation shrinks to the content.
        val subview = SubViewStruct(viewport, StretchAxis.BOTH, density = DENSITY, memos = ProbeMemos())
        val measured = subview.measureForLayout(VIEWPORT_WIDTH_DP, VIEWPORT_HEIGHT_DP)
        assertEquals(VIEWPORT_WIDTH_DP, measured.size.width)

        // Placement: the allocated frame is the offer, and a stretch-BOTH
        // child is measured exactly at it.
        viewport.measureForPlacement(
            SubviewPlacementStruct(
                0f, 0f,
                VIEWPORT_WIDTH_DP, VIEWPORT_HEIGHT_DP,
                VIEWPORT_WIDTH_DP, VIEWPORT_HEIGHT_DP
            ),
            DENSITY
        )
        viewport.layout(0, 0, VIEWPORT_WIDTH_PX, VIEWPORT_HEIGHT_PX)

        // Sum the marker's offsets up the chain: it sits inside the column,
        // which the wrapper lays over the slot the scroll was allocated.
        var markerCentre = marker.width / 2
        var node: View? = marker
        while (node != null && node !== viewport) {
            markerCentre += node.left
            node = node.parent as? View
        }
        assertEquals(VIEWPORT_WIDTH_PX / 2, markerCentre)
    }

    @Test
    fun scrollMinSizeQueryReportsTheContentsIntrinsicCrossAxis() {
        val context = context()
        val contextDensity = context.resources.displayMetrics.density
        val (viewport, _) = mountNarrowContent(context)
        val subview = SubViewStruct(viewport, StretchAxis.BOTH, density = DENSITY, memos = ProbeMemos())

        // A zero-width probe is a parent's "how small can you get" question:
        // the cross axis answers with the content's intrinsic extent, the
        // scroll axis — here height, which is not queried — the offer.
        val measured = subview.measureForLayout(0f, VIEWPORT_HEIGHT_DP)
        assertEquals(CONTENT_WIDTH_PX / contextDensity, measured.size.width)
        assertEquals(VIEWPORT_HEIGHT_DP, measured.size.height)

        // A zero-height probe asks the same on the scroll axis: the content
        // can compress, so the answer is zero, while the offered width still
        // comes back whole.
        val minHeight = subview.measureForLayout(VIEWPORT_WIDTH_DP, 0f)
        assertEquals(VIEWPORT_WIDTH_DP, minHeight.size.width)
        assertEquals(0f, minHeight.size.height)
    }
}
