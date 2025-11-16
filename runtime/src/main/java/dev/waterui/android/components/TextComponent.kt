package dev.waterui.android.components

import android.widget.TextView
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId

private val textTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_text_id().toTypeId()
}

private val textRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_text(node.rawPtr)
    val computed = WuiComputed.styledString(struct.contentPtr, env)
    val textView = TextView(context)
    computed.observe { styled ->
        textView.text = styled.toCharSequence(env)
    }
    textView.disposeWith(computed)
    textView
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiText() {
    register({ textTypeId }, textRenderer)
}
