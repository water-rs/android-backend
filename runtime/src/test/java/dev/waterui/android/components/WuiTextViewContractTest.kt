package dev.waterui.android.components

import android.app.Activity
import android.content.Context
import android.view.View.MeasureSpec
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.SubViewStruct
import dev.waterui.android.runtime.measureForProposal
import dev.waterui.android.runtime.ProposalStruct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The leaf contract a WaterUI text view answers a Rust layout probe with.
 *
 * The height offer never caps text: a stack's `0` minimum query is answered
 * with the same line-count-times-line-box height as an unbounded probe —
 * measured through `MeasureSpec` instead, `TextView.onMeasure` clamps its
 * desired height to the AT_MOST offer and answers zero. And a zero-width
 * offer asks for the narrowest wrap — the widest run between line-break
 * opportunities — not a zero-width column.
 */
// NATIVE graphics: the legacy shadows never wrap text and report one pixel
// per character, which would pass nothing meaningful.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WuiTextViewContractTest {
    private companion object {
        const val WRAP_WIDTH_DP = 120f
        const val WRAP_TEXT =
            "The quick brown fox jumps over the lazy dog, then jumps over it again"
        const val WORDS_TEXT = "aa bbbbb ccc"
    }

    private val density
        get() = context().resources.displayMetrics.density

    private fun context(): Context =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun textView(text: String): WuiTextView =
        WuiTextView(context()).apply { this.text = text }

    private fun measure(view: WuiTextView, width: Float, height: Float) =
        SubViewStruct(view, StretchAxis.NONE, density = density)
            .measureForLayout(width, height).size

    @Test
    fun heightProposalNeverCapsTheLineCount() {
        val underZero = measure(textView(WRAP_TEXT), WRAP_WIDTH_DP, 0f)
        val underUnbounded = measure(textView(WRAP_TEXT), WRAP_WIDTH_DP, Float.POSITIVE_INFINITY)
        val underUnspecified = measure(textView(WRAP_TEXT), WRAP_WIDTH_DP, Float.NaN)
        val singleLine = measure(textView("Ag"), Float.NaN, Float.NaN)

        // The wrap produced more than one line, so the text is taller than a
        // single line box — and the answer is that laid-out height whatever
        // the height probe offered. The old generic path answered 0 for the
        // `(W, 0)` minimum query because TextView clamps to AT_MOST.
        assertTrue(singleLine.height > 0f)
        assertTrue(underZero.height > singleLine.height)
        assertEquals(underUnbounded.height, underZero.height, 0.001f)
        assertEquals(underUnspecified.height, underZero.height, 0.001f)
        assertTrue(underZero.width > 0f)
        assertTrue(underZero.width <= WRAP_WIDTH_DP)
    }

    @Test
    fun zeroWidthAnswersTheWidestUnbreakableRun() {
        val view = textView(WORDS_TEXT)
        val dims = measure(view, 0f, Float.NaN)

        // "bbbbb" is the widest run between break opportunities; the narrowest
        // valid wrap is exactly its laid-out width.
        val widestWordPx = view.paint.measureText("bbbbb")
        assertEquals(widestWordPx / density, dims.width, 0.5f)

        // And the text did wrap — each word gets a line of its own, the same
        // height as the same three words laid out on hard line breaks.
        assertEquals(3, view.layout.lineCount)
        val threeLines = measure(textView("aa\nbbbbb\nccc"), Float.NaN, Float.NaN)
        assertEquals(threeLines.height, dims.height, 0.001f)
    }

    @Test
    fun finiteWidthAnswersTheWidestLaidOutLine() {
        val view = textView(WRAP_TEXT)
        val dims = measure(view, WRAP_WIDTH_DP, Float.NaN)

        // Wrapped under the offer, the answer is the widest line actually
        // laid out — bounded by the offer, wider than zero.
        assertTrue(view.layout.lineCount > 1)
        assertTrue(dims.width > 0f)
        assertTrue(dims.width <= WRAP_WIDTH_DP + 0.001f)
    }

    @Test
    fun unspecifiedWidthAnswersTheUnwrappedLine() {
        val view = textView(WRAP_TEXT)
        val unbounded = measure(view, Float.POSITIVE_INFINITY, 0f)
        val unspecified = measure(view, Float.NaN, 0f)

        assertEquals(1, view.layout.lineCount)
        assertEquals(unbounded.width, unspecified.width, 0.001f)
        // The unwrapped line is wider than a wrap under a narrow offer.
        assertTrue(unbounded.width > measure(textView(WRAP_TEXT), WRAP_WIDTH_DP, 0f).width)
    }

    @Test
    fun lineLimitCapsTheMeasuredLines() {
        val view = textView(WRAP_TEXT).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val dims = measure(view, WRAP_WIDTH_DP, Float.NaN)
        val singleLine = measure(textView("Ag"), Float.NaN, Float.NaN)

        assertEquals(singleLine.height, dims.height, 0.5f)
    }

    @Test
    fun genericSqueezeStillAnswersZeroForPlainViews() {
        // The contract violation this fix removes: a plain TextView probed
        // through MeasureSpec clamps to the AT_MOST offer and reports no
        // height to a stack's minimum query.
        val plain = android.widget.TextView(context()).apply { text = WRAP_TEXT }
        val squeezed = plain.measureForProposal(ProposalStruct(WRAP_WIDTH_DP, 0f), density)
        assertEquals(0f, squeezed.size.height)
    }
}
