package dev.waterui.android.components

import android.util.TypedValue
import android.widget.TextView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.toTypeface

import android.util.Log

private val labelTypeId: WuiTypeId by lazy { NativeBindings.waterui_plain_id().toTypeId() }

private val labelRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_plain(node.rawPtr)
    val textView = TextView(context).apply {
        text = struct.textBytes.decodeToString()
        includeFontPadding = false
        setLineSpacing(0f, 1f)
    }
    Log.d("WaterUI.Label", "render plain len=${textView.text.length}")
    val color = ThemeBridge.foreground(env)
    color.observe { resolved -> textView.setTextColor(resolved.toColorInt()) }
    color.attachTo(textView)
    val font = ThemeBridge.bodyFont(env)
    font.observe { resolved ->
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, resolved.size)
        textView.typeface = resolved.toTypeface()
    }
    font.attachTo(textView)
    textView
}

internal fun RegistryBuilder.registerWuiPlain() {
    register({ labelTypeId }, labelRenderer)
}
