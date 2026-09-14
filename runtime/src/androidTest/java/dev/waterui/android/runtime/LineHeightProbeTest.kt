package dev.waterui.android.runtime

import android.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatTextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the `MaterialTypographyPalette.resolve` contract: M3 text
 * appearances must yield the typescale's absolute line height. Reading it
 * back through `TextView.getLineHeight` silently returns the face's natural
 * metrics instead, which shrank every text element ~5sp and broke the
 * Compose-twin parity budgets (#102).
 */
@RunWith(AndroidJUnit4::class)
class LineHeightProbeTest {
    private fun materialContext() = ContextThemeWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
        com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar
    )

    @Test
    fun bodyLargeAppearanceCarriesLineHeight() {
        val context = materialContext()
        val attributes = context.obtainStyledAttributes(
            intArrayOf(com.google.android.material.R.attr.textAppearanceBodyLarge)
        )
        val appearance = attributes.getResourceId(0, 0)
        attributes.recycle()
        check(appearance != 0) { "Material3 theme is missing textAppearanceBodyLarge" }

        val textView = AppCompatTextView(context)
        textView.setTextAppearance(appearance)
        val pxPerSp = context.resources.displayMetrics.scaledDensity

        // M3 bodyLarge: 16sp text with a 24sp absolute line height. The
        // typescale's `lineHeight` item survives on the style resource even
        // though `setTextAppearance` does not push it into the TextView.
        val lineHeightAttrs = context.obtainStyledAttributes(
            appearance,
            intArrayOf(androidx.appcompat.R.attr.lineHeight)
        )
        val lineHeightPx = lineHeightAttrs.getDimensionPixelSize(0, -1)
        lineHeightAttrs.recycle()

        assertEquals(16f, textView.textSize / pxPerSp, 0.5f)
        assertEquals(24f, lineHeightPx / pxPerSp, 0.75f)
    }
}
