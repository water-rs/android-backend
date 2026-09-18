package dev.waterui.android.runtime

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View.MeasureSpec
import dev.waterui.android.components.WuiTextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The line box a resolved `line_height` pins a styled run to.
 *
 * The pin loosens the box — the resolved value is an absolute extent the run
 * grows into, never a squeeze: a resolved line height below the run's own
 * ascent-to-descent arrives only when the resolver kept a smaller base
 * face's metrics across a size override, and honouring it would measure the
 * view at a box its ink cannot fit — `TextView` clips its own layout to the
 * view bounds when it draws, so a collapsed box is ink destroyed, not a
 * tighter line. The span keeps the natural metrics below the floor, the
 * same "the reported extent covers what is rendered" rule the leaf contract
 * keeps for size answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExactLineHeightSpanTest {
    private companion object {
        const val BODY_SP = 14f
        const val BIG_SP = 48f
        const val TEXT = "huge 48"
    }

    private fun context(): Context =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun spannedTextView(lineHeightPx: Int?): WuiTextView {
        val ss = SpannableString(TEXT)
        ss.setSpan(AbsoluteSizeSpan(BIG_SP.toInt(), true), 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (lineHeightPx != null) {
            ss.setSpan(ExactLineHeightSpan(lineHeightPx), 0, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return WuiTextView(context()).apply {
            text = ss
            setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
            includeFontPadding = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
    }

    private fun measureUnbounded(view: WuiTextView) {
        view.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    /** Rows of the view's rendered frame carrying ink, in view coordinates. */
    private fun inkRows(view: WuiTextView): Pair<Int, Int> {
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val margin = view.measuredHeight
        val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(0f, margin.toFloat())
        view.draw(canvas)
        var first = -1
        var last = -1
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (bmp.getPixel(x, y) ushr 24 != 0) {
                    if (first < 0) first = y
                    last = y
                }
            }
        }
        return first - margin to last - margin
    }

    @Test
    fun pinBelowTheRunsOwnMetricsKeepsTheNaturalBox() {
        val natural = spannedTextView(lineHeightPx = null)
        measureUnbounded(natural)
        val naturalHeight = natural.measuredHeight
        val naturalInk = inkRows(natural)

        // The resolved value that broke edge_text: a 48sp run carrying the
        // body face's 24px line height.
        val pinned = spannedTextView(lineHeightPx = 24)
        measureUnbounded(pinned)

        assertEquals(
            "a pin under the run's metrics must not collapse the measured box",
            naturalHeight,
            pinned.measuredHeight
        )
        val (first, last) = inkRows(pinned)
        assertEquals("glyph tops render inside the view", naturalInk.first, first)
        assertEquals("glyph bottoms render inside the view", naturalInk.second, last)
        assertTrue("the baseline stays inside the box", pinned.layout.getLineBaseline(0) < pinned.measuredHeight)
    }

    @Test
    fun pinAboveTheRunsOwnMetricsCentresTheRunInThePinnedBox() {
        val natural = spannedTextView(lineHeightPx = null)
        measureUnbounded(natural)
        val naturalHeight = natural.measuredHeight
        val naturalInk = inkRows(natural)

        val pinnedHeight = naturalHeight + 40
        val pinned = spannedTextView(lineHeightPx = pinnedHeight)
        measureUnbounded(pinned)

        assertEquals(pinnedHeight, pinned.measuredHeight)
        // The run stays centred in the box: the slack splits evenly around it.
        val (first, last) = inkRows(pinned)
        assertEquals(naturalInk.first + 20, first)
        assertEquals(naturalInk.second + 20, last)
    }
}
