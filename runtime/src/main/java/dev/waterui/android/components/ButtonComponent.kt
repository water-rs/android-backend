package dev.waterui.android.components

import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.widget.FrameLayout
import android.widget.TextView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

import dev.waterui.android.runtime.dp

private val buttonTypeId: WuiTypeId by lazy { NativeBindings.waterui_button_id().toTypeId() }

private val buttonRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_button(node.rawPtr)
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)

    // Button is a transparent, clickable container with padding.
    // Background styling (color, rounded corners) should be applied via .background() modifier,
    // which wraps the button in Metadata<Background>.
    val container = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        val horizontal = 16f.dp(context).toInt()
        val vertical = 8f.dp(context).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        addView(labelView)
        setOnClickListener {
            NativeBindings.waterui_call_action(struct.actionPtr, env.raw())
        }
        // Add ripple effect using accent color
        val accent = ThemeBridge.accent(env)
        accent.observe { color ->
            val colorInt = color.toColorInt()
            val rippleColor = ColorStateList.valueOf(colorInt)
            foreground = RippleDrawable(rippleColor, null, null)
        }
        accent.attachTo(this)
    }

    // If label is text, apply accent foreground color
    if (labelView is TextView) {
        val contentColor = ThemeBridge.accentForeground(env)
        contentColor.observe { color ->
            labelView.setTextColor(color.toColorInt())
        }
        contentColor.attachTo(labelView)
    }

    container.disposeWith {
        NativeBindings.waterui_drop_action(struct.actionPtr)
    }
    container
}

internal fun RegistryBuilder.registerWuiButton() {
    register({ buttonTypeId }, buttonRenderer)
}
