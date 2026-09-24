package dev.waterui.android.runtime

import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnoses the nightly-E2E finding that the gesture example's "Tap Me!" box
 * renders only "Tap": whether the TextView's text is complete but its frame is
 * narrower than the intrinsic text width (layout clip), or the text itself was
 * truncated upstream (content defect).
 */
@RunWith(AndroidJUnit4::class)
class TextMeasureTest {

    @Test
    fun gestureBoxesFitTheirLabels() {
        ActivityScenario.launch(WaterUiTestActivity::class.java).use { scenario ->
            val activity = Waiters.activity(scenario)
            Waiters.until(description = "WaterUiRootView to inflate its first child") {
                activity.rootView.childCount > 0
            }
            // Wait for a text whose content is complete in the source but which
            // the nightly screenshot shows clipped, then inspect what was laid
            // out.
            Waiters.until(description = "the tap-gesture box label to exist") {
                dump(activity.rootView).any { it.text.startsWith("Tap") }
            }

            val rows = dump(activity.rootView)
            rows.forEach { Log.i(TAG, it.describe()) }

            val box = checkNotNull(rows.find { it.text == "Tap Me!" || it.text == "Tap" }) {
                "expected a TextView for the tap-gesture box label; tree was:\n" +
                    rows.joinToString("\n") { it.describe() }
            }
            val intrinsic = box.intrinsicWidth()
            assertTrue(
                "label \"${box.text}\" laid out at ${box.bounds.width()}px " +
                    "but its intrinsic width is ${intrinsic}px (clip = ${intrinsic - box.bounds.width()}px)",
                box.bounds.width() >= intrinsic,
            )
        }
    }

    private data class TextRow(val view: TextView, val text: String, val bounds: Rect) {
        fun intrinsicWidth(): Int {
            val paint = view.paint
            return (paint.measureText(text, 0, text.length) + 0.5f).toInt() +
                view.compoundPaddingLeft + view.compoundPaddingRight
        }

        fun describe(): String =
            "text=\"$text\" bounds=$bounds measured=${view.measuredWidth}x${view.measuredHeight} " +
                "intrinsic=${intrinsicWidth()}px ellipsize=${view.ellipsize} " +
                "maxLines=${view.maxLines} parent=${view.parent?.javaClass?.simpleName}"
    }

    private fun dump(root: View): List<TextRow> {
        val rows = mutableListOf<TextRow>()
        val offset = Rect()
        fun visit(view: View, dx: Int, dy: Int) {
            val r = Rect(view.left + dx, view.top + dy, view.right + dx, view.bottom + dy)
            if (view is TextView) {
                rows += TextRow(view, Waiters.stripBidi(view.text), r)
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    visit(view.getChildAt(i), r.left, r.top)
                }
            }
        }
        visit(root, 0, 0)
        return rows
    }

    private companion object {
        const val TAG = "TextMeasureTest"
    }
}
