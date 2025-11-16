package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.ViewGroup
import com.google.android.material.card.MaterialCardView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId

private val buttonTypeId: WuiTypeId by lazy { NativeBindings.waterui_button_id().toTypeId() }

private val buttonRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_button(node.rawPtr)
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    val card = MaterialCardView(context).apply {
        isClickable = true
        isFocusable = true
        rippleColor = ColorStateList.valueOf(0x1f000000)
        val horizontal = (context.resources.displayMetrics.density * 16).toInt()
        val vertical = (context.resources.displayMetrics.density * 8).toInt()
        setContentPadding(horizontal, vertical, horizontal, vertical)
        addView(labelView)
        setOnClickListener {
            NativeBindings.waterui_call_action(struct.actionPtr, env.raw())
        }
    }
    card.disposeWith {
        NativeBindings.waterui_drop_action(struct.actionPtr)
    }
    card
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiButton() {
    register({ buttonTypeId }, buttonRenderer)
}
