package dev.waterui.android.components

import android.content.Context
import android.view.View
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId


private val emptyTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_empty_id().toTypeId()
}

/// WaterUI's empty view.
///
/// `Space` does the same job — take up no room of its own, paint nothing — but
/// it is `final`, and a consumer that has to tell "the app supplied nothing
/// here" apart from "the app supplied something this consumer cannot use" has
/// to ask by type rather than guess from a layout class any other view could be
/// using too.
///
/// Standing in for `Space` means measuring like it, which is why [onMeasure] is
/// here: `View`'s own answers an AT_MOST proposal with the *whole* proposed
/// size, and `Space` answers it with its suggested minimum. An empty view that
/// takes every pixel it is offered leaves none for the view beside it — a
/// `Slider` renders its label above its track and hides that label by rendering
/// this view in its place, so a hidden label ate the whole height the row
/// offered the control and pushed the track out past the bottom of it. The
/// track still painted, because every WaterUI container lets its children draw
/// outside their bounds, but it was nowhere its parent could hit-test and
/// reported an empty rect to accessibility.
internal class WuiEmptyView(context: Context) : View(context) {
    init {
        setWillNotDraw(true)
    }

    /**
     * Reports no size of its own, taking only a size it is *told* to be.
     *
     * `Space.getDefaultSize2` spelled out: EXACTLY is an allocation and is
     * honoured, while AT_MOST and UNSPECIFIED are offers an empty view declines.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(allocated(widthMeasureSpec), allocated(heightMeasureSpec))
    }

    /** The size [spec] allocates, or zero when it only offers one. */
    private fun allocated(spec: Int): Int =
        if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
}

private val emptyRenderer = WuiRenderer { context, _, _, _ ->
    WuiEmptyView(context)
}

internal fun RegistryBuilder.registerWuiEmptyView() {
    register({ emptyTypeId }, emptyRenderer)
}
