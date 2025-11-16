package dev.waterui.android.components

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.google.android.material.card.MaterialCardView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRustAnimation
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.toTypeId
import dev.waterui.android.runtime.dp

private val buttonTypeId: WuiTypeId by lazy { NativeBindings.waterui_button_id().toTypeId() }

private val buttonRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_button(node.rawPtr)
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    val card = MaterialCardView(context).apply {
        isClickable = true
        isFocusable = true
        cardElevation = 0f
        radius = 12f.dp(context)
        val horizontal = 16f.dp(context).toInt()
        val vertical = 8f.dp(context).toInt()
        setContentPadding(horizontal, vertical, horizontal, vertical)
        addView(labelView)
        setOnClickListener {
            NativeBindings.waterui_call_action(struct.actionPtr, env.raw())
        }
    }
    val accent = ThemeBridge.accent(env)
    accent.observeWithAnimation { color, animation ->
        val colorInt = color.toColorInt()
        card.applyRustAnimation(animation) {
            ViewCompat.setBackgroundTintList(card, ColorStateList.valueOf(colorInt))
            card.rippleColor = ColorStateList.valueOf(colorInt)
        }
    }
    accent.attachTo(card)
    if (labelView is TextView) {
        val contentColor = ThemeBridge.accentForeground(env)
        contentColor.observe { color ->
            labelView.setTextColor(color.toColorInt())
        }
        contentColor.attachTo(labelView)
    }
    card.disposeWith {
        NativeBindings.waterui_drop_action(struct.actionPtr)
    }
    card
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiButton() {
    register({ buttonTypeId }, buttonRenderer)
}
