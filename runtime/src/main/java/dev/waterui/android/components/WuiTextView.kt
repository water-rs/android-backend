package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.text.Layout
import android.view.View.MeasureSpec
import android.widget.TextView
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.VerticalAlignment
import dev.waterui.android.runtime.VerticalGuideStruct
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.WuiMeasurableLayout
import dev.waterui.android.runtime.leafAxisAnswer
import dev.waterui.android.runtime.mayFillHorizontal
import dev.waterui.android.runtime.mayFillVertical
import java.text.BreakIterator
import kotlin.math.ceil

/**
 * The TextView every WaterUI text leaf renders into.
 *
 * Its probe answer is the layout contract's leaf contract, which a
 * MeasureSpec squeeze cannot express: a finite width offer is the wrap width
 * and the answer is the widest laid-out line — not the offer; a zero-width
 * offer asks for the narrowest wrap, the widest run between line-break
 * opportunities; and the height offer never enters at all — text answers its
 * laid-out line count times the platform line box. Measured through
 * MeasureSpec instead, `TextView.onMeasure` clamps its desired height to an
 * AT_MOST offer, so a stack's `0` minimum query was answered with a zero
 * height and a compressed stack dropped the text entirely.
 */
// The backend renders every text leaf with the framework TextView — colors
// and fonts come from resolved theme values, not compat tinting.
@SuppressLint("AppCompatCustomView")
internal class WuiTextView(context: Context) : TextView(context), WuiMeasurableLayout {

    private val density = resources.displayMetrics.density

    /**
     * The answers computed under resolved width specs, plus the narrowest-wrap
     * answer. Shaping is the expensive half of a probe — every miss runs
     * `TextView.measure`, which lays the text out again — so a repeated
     * equivalent probe must return the remembered answer instead. The holder
     * survives until [requestLayout], the funnel every shaping input (text,
     * typeface, sizes, spacing, line limits, padding) passes through on its
     * way to invalidating the layout — and `requestLayout` also runs inside
     * the TextView constructor, before any property here is initialized, so
     * the holder is nullable and created on first probe.
     */
    private var shapedAnswers: ShapedAnswers? = null

    /** How many probes have actually laid the text out; a test seam. */
    internal var layoutPassCount = 0
        private set

    private class ShapedAnswers {
        val bySpec = HashMap<Int, ProbeAnswer>()
        var narrowestWrapPx: Float? = null
    }

    /** The probe answer measured under one width spec, in dp. */
    private class ProbeAnswer(
        val intrinsicWidth: Float,
        val intrinsicHeight: Float,
        val verticalGuides: Array<VerticalGuideStruct>
    )

    override fun requestLayout() {
        shapedAnswers = null
        super.requestLayout()
    }

    override fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
        val cache = shapedAnswers ?: ShapedAnswers().also { shapedAnswers = it }
        val spec = widthSpecFor(proposal.width, cache)
        val answer = cache.bySpec.getOrPut(spec) { shapeAnswer(spec) }

        val axis = getTag(TAG_STRETCH_AXIS) as? StretchAxis ?: StretchAxis.NONE
        return ViewDimensionsStruct(
            size = SizeStruct(
                width = leafAxisAnswer(proposal.width, answer.intrinsicWidth, axis.mayFillHorizontal()),
                height = leafAxisAnswer(proposal.height, answer.intrinsicHeight, axis.mayFillVertical())
            ),
            horizontalGuides = emptyArray(),
            verticalGuides = answer.verticalGuides
        )
    }

    /**
     * The spec one width proposal resolves to: unspecified and unbounded
     * probes both ask for the unwrapped line, a finite offer is the wrap
     * width, and a zero offer asks for the narrowest wrap — a bare AT_MOST 0
     * would squeeze to nothing and UNSPECIFIED never wraps.
     */
    private fun widthSpecFor(widthDp: Float, cache: ShapedAnswers): Int = when {
        widthDp.isNaN() || widthDp.isInfinite() ->
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        widthDp > 0f ->
            MeasureSpec.makeMeasureSpec(ceil(widthDp * density).toInt(), MeasureSpec.AT_MOST)
        else -> MeasureSpec.makeMeasureSpec(ceil(narrowestWrapWidthPx(cache)).toInt(), MeasureSpec.AT_MOST)
    }

    /**
     * Lays the text out under [widthSpec] and distils the probe answer. The
     * height offer never enters: the answer is the laid-out line count times
     * the platform line box, whatever the probe proposed.
     */
    private fun shapeAnswer(widthSpec: Int): ProbeAnswer {
        layoutPassCount += 1
        measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

        val lines = layout
        // `getMaxLines` answers -1 when no limit is set.
        val shownLines = if (maxLines >= 0) minOf(lines.lineCount, maxLines) else lines.lineCount
        var widestPx = 0f
        for (line in 0 until shownLines) {
            widestPx = maxOf(widestPx, lines.getLineMax(line))
        }
        return ProbeAnswer(
            intrinsicWidth = maxOf(
                widestPx + compoundPaddingLeft + compoundPaddingRight,
                suggestedMinimumWidth.toFloat()
            ) / density,
            intrinsicHeight = measuredHeight.toFloat() / density,
            verticalGuides = baselineGuides(lines, shownLines)
        )
    }

    /**
     * The first- and last-line baselines of the laid-out text, in dp from the
     * view's top edge — the guides a Rust layout aligns text on.
     */
    private fun baselineGuides(lines: Layout, shownLines: Int): Array<VerticalGuideStruct> {
        if (shownLines <= 0) {
            return emptyArray()
        }
        val top = extendedPaddingTop
        return arrayOf(
            VerticalGuideStruct(
                VerticalAlignment.FIRST_BASELINE,
                (top + lines.getLineBaseline(0)) / density
            ),
            VerticalGuideStruct(
                VerticalAlignment.LAST_BASELINE,
                (top + lines.getLineBaseline(shownLines - 1)) / density
            )
        )
    }

    /**
     * The width in pixels of the narrowest wrap this text admits: the widest
     * run between the line-break opportunities `BreakIterator` finds in the
     * laid-out text, measured on the text's own layout so styled spans count.
     */
    private fun narrowestWrapWidthPx(cache: ShapedAnswers): Float {
        cache.narrowestWrapPx?.let { return it }
        // Lay the text out unbounded so every break offset is a horizontal
        // advance on a single laid-out line per paragraph.
        layoutPassCount += 1
        measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        val lines = layout
        val content = text
        var widestPx = 0f
        val breaks = BreakIterator.getLineInstance()
        breaks.setText(content.toString())
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            // Trailing whitespace is where the next break may happen; it is
            // not part of the run a wrap must fit.
            var runEnd = end
            while (runEnd > start && content[runEnd - 1].isWhitespace()) {
                runEnd--
            }
            if (runEnd > start) {
                val runWidth = kotlin.math.abs(
                    lines.getPrimaryHorizontal(runEnd) - lines.getPrimaryHorizontal(start)
                )
                widestPx = maxOf(widestPx, runWidth)
            }
            start = end
            end = breaks.next()
        }
        val narrowestPx = widestPx + compoundPaddingLeft + compoundPaddingRight
        cache.narrowestWrapPx = narrowestPx
        return narrowestPx
    }
}
