package dev.waterui.android.components

import android.widget.TextView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.applyResolvedFont
import dev.waterui.android.runtime.toColorInt

private val labelTypeId: WuiTypeId by lazy { NativeBindings.waterui_plain_id().toTypeId() }

private val labelRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_plain(node.rawPtr)
    val textView = TextView(context).apply {
        text = struct.text
    }
    val color = ThemeBridge.foreground(env)
    color.observe { resolved -> textView.setTextColor(resolved.toColorInt()) }
    color.attachTo(textView)
    val font = ThemeBridge.bodyFont(env)
    font.observe(textView::applyResolvedFont)
    font.attachTo(textView)
    textView
}

internal fun RegistryBuilder.registerWuiPlain() {
    register({ labelTypeId }, labelRenderer)
}
