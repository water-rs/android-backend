package dev.waterui.android.layout

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import dev.waterui.android.runtime.dp
import kotlin.math.max

/**
 * A LinearLayout that matches WaterUI's axis-expanding component measurement contract:
 *
 * - During `size_that_fits` (AT_MOST / UNSPECIFIED), report a *minimum usable width*
 *   instead of greedily consuming the full proposed width.
 * - During `place` (EXACTLY), expand to fill the allocated width.
 *
 * This keeps stretch behavior defined by Rust (StretchAxis::Horizontal) rather than
 * hardcoding "fill max width" behavior in each component.
 */
internal class AxisExpandingLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val minWidthDp: Float = DEFAULT_MIN_WIDTH_DP
) : LinearLayout(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        if (widthMode == MeasureSpec.EXACTLY) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val minWidthPx = minWidthDp.dp(context).toInt()

        // Measure intrinsic size first.
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            heightMeasureSpec
        )

        var desiredWidth = max(measuredWidth, minWidthPx)
        if (widthMode == MeasureSpec.AT_MOST) {
            desiredWidth = desiredWidth.coerceAtMost(widthSize)
        }

        // Re-measure with the decided width so children get a stable size.
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(desiredWidth, MeasureSpec.EXACTLY),
            heightMeasureSpec
        )
    }

    private companion object {
        const val DEFAULT_MIN_WIDTH_DP: Float = 100f
    }
}

