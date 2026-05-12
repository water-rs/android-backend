package dev.waterui.android.components

import android.view.Gravity
import android.view.View
import android.util.TypedValue
import android.widget.TextView
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRustAnimation
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.toTypeface

private val textTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_text_id().toTypeId()
}

private val textRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_text(node.rawPtr)
    val computed = WuiComputed.styledString(struct.contentPtr, env)
    val paragraphAlignment = WuiComputed.horizontalAlignment(struct.paragraphAlignmentPtr, env)
    val textView = TextView(context)
    val foreground = ThemeBridge.foreground(env)
    foreground.observe { color ->
        textView.setTextColor(color.toColorInt())
    }
    foreground.attachTo(textView)
    val bodyFont = ThemeBridge.bodyFont(env)
    bodyFont.observe { font ->
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, font.size)
        textView.typeface = font.toTypeface()
    }
    bodyFont.attachTo(textView)
    paragraphAlignment.observe { alignment ->
        when (alignment) {
            0 -> {
                textView.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                textView.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            2 -> {
                textView.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                textView.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            else -> {
                textView.textAlignment = View.TEXT_ALIGNMENT_CENTER
                textView.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            }
        }
    }
    paragraphAlignment.attachTo(textView)
    computed.observeWithAnimation { styled, animation ->
        val resolved = styled.toCharSequence(env)
        textView.applyRustAnimation(animation) {
            textView.text = resolved
        }
        // Request layout after text change so parent can resize
        textView.requestLayout()
    }
    textView.disposeWith(computed)
    textView.disposeWith(paragraphAlignment)
    textView
}

internal fun RegistryBuilder.registerWuiText() {
    register({ textTypeId }, textRenderer)
}
