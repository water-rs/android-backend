package dev.waterui.android.runtime

import android.text.SpannableString
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

/**
 * Guards the measured-height contract `applyResolvedFont` must satisfy: a
 * text view whose resolved font carries an absolute line height measures
 * exactly that line box per line, the way Compose's typescale does.
 *
 * Regression for #110: `includeFontPadding=false` combined with
 * `setLineHeight` trims the face's natural top/bottom padding back off
 * `getDesiredHeight`, so a 24sp line box measured ~18.7sp and every text
 * row in a stack landed ~14px below where the Compose twin puts it.
 */
@RunWith(AndroidJUnit4::class)
class LineHeightMeasureProbeTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val pxPerSp
        get() = context.pxPerSp()

    private fun bodyFont() = ResolvedFontStruct(
        size = 16f,
        weight = 3, // FontWeight.Regular ordinal
        family = null,
        design = ResolvedFontStruct.FONT_DESIGN_DEFAULT,
        lineHeight = 24f,
        letterSpacing = 0f
    )

    private fun textView() = TextView(context).apply {
        // `checkForRelayout` reads `mLayoutParams` once a layout exists, so a
        // measured view needs params just like an attached one.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun TextView.measureHeight(lines: Int): Int {
        text = (1..lines).joinToString("\n") { "Ag" }
        measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return measuredHeight
    }

    @Test
    fun plainTextViewMeasuresTheExactLineBox() {
        val view = textView()
        view.applyResolvedFont(bodyFont())

        val target = (24f * pxPerSp).roundToInt()
        assertEquals(target, view.measureHeight(1))
        assertEquals(3 * target, view.measureHeight(3))
    }

    @Test
    fun spannedChunkOwnsItsOwnLineBox() {
        val view = textView()
        view.applyResolvedFont(bodyFont(), applyLineHeight = false)

        // A headline-sized run inside body-fonted chrome keeps its own slot's
        // line box: headlineMedium is 28sp text on a 32sp line.
        val spanned = SpannableString("Title")
        spanned.setSpan(
            ExactLineHeightSpan((32f * pxPerSp).roundToInt()),
            0,
            spanned.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        view.text = spanned
        view.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        assertEquals((32f * pxPerSp).roundToInt(), view.measuredHeight)
    }
}
