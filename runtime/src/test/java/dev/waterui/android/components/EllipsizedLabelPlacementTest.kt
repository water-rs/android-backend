package dev.waterui.android.components

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import dev.waterui.android.runtime.ProbeMemos
import dev.waterui.android.runtime.ProposalStruct
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ellipsised-label placement defect: a squeezed single-line text answers
 * a Rust probe with the widest line's ink, but `TextView.onMeasure` occupies
 * the whole wrap cap under the same offer. A control that negotiates on the
 * answer and positions by `measuredWidth` — a centred label inside the M3
 * content padding — lays the label out offset into the padding box, where
 * `clipToPadding` severs the first glyph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EllipsizedLabelPlacementTest {
    private companion object {
        const val TEXT = "The quick brown fox jumps over the lazy dog"
        const val OFFER_DP = 108f
        const val CHROME_DP = 48f
    }

    private val density
        get() = context().resources.displayMetrics.density

    private fun context(): Context =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun ellipsizedLabel(ctx: Context): WuiTextView =
        WuiTextView(ctx).apply {
            text = TEXT
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    /**
     * The probe answer under a narrowing offer is the extent the platform
     * measure produces under the same spec — the width the parent will lay
     * the view out at — never a narrower ink width.
     */
    @Test
    fun ellipsizedAnswerIsTheMeasuredExtent() {
        val view = ellipsizedLabel(context())
        val offerPx = ceil((OFFER_DP - CHROME_DP) * density).toInt()

        val answer = view.measureForLayout(
            ProposalStruct(OFFER_DP - CHROME_DP, Float.NaN),
            ProbeMemos()
        ).size
        view.measure(
            MeasureSpec.makeMeasureSpec(offerPx, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        assertEquals(1, view.layout.lineCount)
        assertTrue(view.layout.getEllipsisCount(0) > 0)
        assertEquals(view.measuredWidth / density, answer.width, 0.001f)
        assertTrue(answer.width <= OFFER_DP - CHROME_DP)
    }

    /**
     * The full negotiation a squeezed button runs: the label hears the offer
     * minus the content padding, the control answers label plus padding and
     * is framed at that answer, while the placement measure runs under the
     * share the parent layout allocated — which may exceed the answer. The
     * label must still land inside the padding box with its layout drawn
     * from x=0.
     */
    @Test
    fun ellipsisedLabelStaysInsideTheContentBox() {
        val ctx = context()
        val padPx = (24f * density).toInt()
        val label = ellipsizedLabel(ctx)
        val button = WuiButtonLayout(ctx, label).apply {
            setPadding(padPx, 0, padPx, 0)
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        // Negotiate, then place: the frame is the control's answer, the
        // placement measure runs under the allocated share — the offer.
        val answer = button.measureForLayout(
            ProposalStruct(OFFER_DP, Float.NaN),
            ProbeMemos()
        ).size
        val labelAnswer = answer.width - CHROME_DP
        assertTrue(label.layout.getEllipsisCount(0) > 0)

        button.measure(
            MeasureSpec.makeMeasureSpec(ceil(OFFER_DP * density).toInt(), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        button.layout(0, 0, ceil(answer.width * density).toInt(), button.measuredHeight)

        // The measured label must fit the content box the answer produced,
        // sit inside it without a negative offset, and draw from x=0.
        val contentBox = answer.width - CHROME_DP
        assertTrue(label.measuredWidth / density <= contentBox)
        assertTrue(label.left >= padPx)
        assertEquals(0, label.scrollX)
        assertTrue(label.layout.getLineLeft(0) >= 0f)
    }
}
