package dev.waterui.android.components

import android.view.Gravity
import android.widget.CompoundButton
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.InteractionBridge
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView

private val toggleTypeId: WuiTypeId by lazy { NativeBindings.waterui_toggle_id().toTypeId() }
private const val TOGGLE_STYLE_AUTOMATIC = 0
private const val TOGGLE_STYLE_SWITCH = 1
private const val TOGGLE_STYLE_CHECKBOX = 2

// The controls keep their Widget.Material3 default tints: Compose M3 renders
// the checked switch as onPrimary-on-opaque-primary with an outline-bordered
// surfaceContainerHighest track when unchecked. The previous hand-tinting
// (accent thumb over a 40%-alpha accent track) was the Material 2 scheme and
// read as visibly out of date next to any Compose screen.
private val toggleRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_toggle(node.rawPtr)
    val binding = WuiBinding.bool(struct.bindingPtr)
    val control: CompoundButton = when (struct.style) {
        TOGGLE_STYLE_AUTOMATIC,
        TOGGLE_STYLE_SWITCH -> MaterialSwitch(context)
        TOGGLE_STYLE_CHECKBOX -> MaterialCheckBox(context)
        else -> error("unknown toggle style: ${struct.style}")
    }
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    container.addView(control)

    val disabled = InteractionBridge.disabled(env)
    disabled.observe { isDisabled ->
        control.isEnabled = !isDisabled
        container.alpha = if (isDisabled) 0.38f else 1f
    }
    disabled.attachTo(control)

    var applyingBindingValue = false
    binding.observe { value ->
        if (control.isChecked != value) {
            applyingBindingValue = true
            control.isChecked = value
            applyingBindingValue = false
        }
    }
    control.setOnCheckedChangeListener { _, isChecked ->
        if (!applyingBindingValue) {
            binding.set(isChecked)
        }
    }

    installSemanticAccessibilityLabel(
        target = control,
        content = labelView,
        labelPtr = struct.accessibilityLabelPtr,
        env = env
    )

    container.disposeWith(binding)
    container
}

internal fun RegistryBuilder.registerWuiToggle() {
    register({ toggleTypeId }, toggleRenderer)
}
