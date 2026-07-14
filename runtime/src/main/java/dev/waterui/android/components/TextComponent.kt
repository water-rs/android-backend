package dev.waterui.android.components

import android.view.Gravity
import android.view.View
import android.widget.TextView
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.applyRustAnimation
import dev.waterui.android.runtime.applyResolvedFont
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.toColorInt
import java.io.Closeable

private val textTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_text_id().toTypeId()
}

private val textRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_text(node.rawPtr)
    val computed = WuiComputed.styledString(struct.contentPtr)
    val paragraphAlignment = WuiComputed.horizontalAlignment(struct.paragraphAlignmentPtr)
    val textView = TextView(context)
    val foreground = ThemeBridge.foreground(env)
    foreground.observe { color ->
        textView.setTextColor(color.toColorInt())
    }
    foreground.attachTo(textView)
    val bodyFont = ThemeBridge.bodyFont(env)
    bodyFont.observe(textView::applyResolvedFont)
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
            1 -> {
                textView.textAlignment = View.TEXT_ALIGNMENT_CENTER
                textView.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
            }
            else -> error("unknown paragraph alignment: $alignment")
        }
    }
    paragraphAlignment.attachTo(textView)
    var styledBinding: Closeable? = null
    computed.observeWithAnimation { styled, animation ->
        styledBinding?.close()
        var pendingAnimation = animation
        styledBinding = styled.bind(env) { resolved ->
            val appliedAnimation = pendingAnimation
            pendingAnimation = WuiAnimation.None
            textView.applyRustAnimation(appliedAnimation) {
                textView.text = resolved
            }
            textView.requestLayout()
        }
    }
    textView.disposeWith {
        styledBinding?.close()
        computed.close()
    }
    textView
}

internal fun RegistryBuilder.registerWuiText() {
    register({ textTypeId }, textRenderer)
}
