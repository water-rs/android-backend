package dev.waterui.android.components

import android.view.Gravity
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId

private val toggleTypeId: WuiTypeId by lazy { NativeBindings.waterui_toggle_id().toTypeId() }

private val toggleRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_toggle(node.rawPtr)
    val binding = WuiBinding.bool(struct.bindingPtr, env)
    val switch = SwitchCompat(context)
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    container.addView(switch)

    var updating = false
    binding.observe { value ->
        if (switch.isChecked != value) {
            updating = true
            switch.isChecked = value
            updating = false
        }
    }
    switch.setOnCheckedChangeListener { _, isChecked ->
        if (!updating) {
            binding.set(isChecked)
        }
    }

    container.disposeWith(binding)
    container
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiToggle() {
    register({ toggleTypeId }, toggleRenderer)
}
