package dev.waterui.android.runtime

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `ExactLineHeightSpan` pins the line box to the resolved font's absolute
 * line height — but the pin only ever loosens the box. A pin below the run's
 * natural ascent-to-descent keeps natural metrics: shrinking the box would
 * leave the glyphs drawing outside the measured extent, and the view's draw
 * clip destroys the ink above it (android-backend#191).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExactLineHeightSpanTest {

    private fun metrics(ascent: Int, descent: Int) = Paint.FontMetricsInt().apply {
        this.ascent = ascent
        this.descent = descent
        this.top = ascent
        this.bottom = descent
    }

    private fun chooseHeight(pinPx: Int, fm: Paint.FontMetricsInt) =
        ExactLineHeightSpan(pinPx).chooseHeight("text", 0, 4, 0, fm.descent - fm.ascent, fm)

    @Test
    fun aTighterPinKeepsTheNaturalLineBox() {
        // The #191 shape: a ~24px pin under a ~57px natural box.
        val fm = metrics(ascent = -50, descent = 7)
        chooseHeight(pinPx = 24, fm)

        assertEquals(-50, fm.ascent)
        assertEquals(7, fm.descent)
        assertEquals(-50, fm.top)
        assertEquals(7, fm.bottom)
    }

    @Test
    fun aMatchingPinLeavesTheMetricsAlone() {
        val fm = metrics(ascent = -50, descent = 7)
        chooseHeight(pinPx = 57, fm)

        assertEquals(-50, fm.ascent)
        assertEquals(7, fm.descent)
    }

    @Test
    fun aLooserPinCentresTheGlyphRunInTheExpandedBox() {
        val fm = metrics(ascent = -50, descent = 7)
        chooseHeight(pinPx = 77, fm) // extra 20: 10 each side

        assertEquals(-60, fm.ascent)
        assertEquals(17, fm.descent)
        assertEquals(-60, fm.top)
        assertEquals(17, fm.bottom)
    }
}
