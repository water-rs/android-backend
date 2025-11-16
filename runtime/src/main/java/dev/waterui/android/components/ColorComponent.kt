package dev.waterui.android.components

import android.view.View
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId

private val colorTypeId: WuiTypeId by lazy { NativeBindings.waterui_color_id().toTypeId() }

private val colorRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_color(node.rawPtr)
    val computed = WuiComputed.resolvedColor(struct.colorPtr, env)
    val view = View(context)
    computed.observe { color ->
        view.setBackgroundColor(color.toColorInt())
    }
    view.disposeWith(computed)
    view
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiColor() {
    register({ colorTypeId }, colorRenderer)
}
