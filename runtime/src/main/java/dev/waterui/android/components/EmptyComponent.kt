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
/// `Space` does the same job — measure to whatever it is given, paint nothing —
/// but it is `final`, and a consumer that has to tell "the app supplied nothing
/// here" apart from "the app supplied something this consumer cannot use" has
/// to ask by type rather than guess from a layout class any other view could be
/// using too.
internal class WuiEmptyView(context: Context) : View(context) {
    init {
        setWillNotDraw(true)
    }
}

private val emptyRenderer = WuiRenderer { context, _, _, _ ->
    WuiEmptyView(context)
}

internal fun RegistryBuilder.registerWuiEmptyView() {
    register({ emptyTypeId }, emptyRenderer)
}
