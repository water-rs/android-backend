package dev.waterui.android.layout

import android.content.Context
import android.util.AttributeSet
import dev.waterui.android.runtime.dp
import kotlin.math.max

/**
 * A LinearLayout that matches WaterUI's axis-expanding component measurement contract:
 *
 * - During `size_that_fits` (AT_MOST / UNSPECIFIED), report a *minimum usable width*
 *   instead of greedily consuming the full proposed width.
 * - During `place`, expand to fill the allocated frame.
 *
 * Placement measures children under the proposal the layout selected — AT_MOST
 * for a finite offer — never EXACTLY the frame, so expansion happens in
 * `onLayout`, where the allocated frame is the directive. This keeps stretch
 * behavior defined by Rust (StretchAxis::Horizontal) rather than hardcoding
 * "fill max width" behavior in each component.
 *
 * Contract probes are answered by [WuiMeasurableLinearLayout]; the same
 * minimum-usable width floors its intrinsic answer there.
 */
internal class AxisExpandingLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val minWidthDp: Float = DEFAULT_MIN_WIDTH_DP
) : WuiMeasurableLinearLayout(context, attrs, defStyleAttr) {

    override val widthFloorDp: Float get() = minWidthDp

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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // `place` allocates what measurement only offered: expand into the
        // frame before the children are positioned in it.
        val width = r - l
        val height = b - t
        if (width != measuredWidth || height != measuredHeight) {
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
        super.onLayout(changed, l, t, r, b)
    }

    private companion object {
        const val DEFAULT_MIN_WIDTH_DP: Float = 100f
    }
}
