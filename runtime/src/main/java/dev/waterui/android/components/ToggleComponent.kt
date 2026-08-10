package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.InteractionBridge
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.withAlpha

private val toggleTypeId: WuiTypeId by lazy { NativeBindings.waterui_toggle_id().toTypeId() }
private const val TOGGLE_STYLE_AUTOMATIC = 0
private const val TOGGLE_STYLE_SWITCH = 1
private const val TOGGLE_STYLE_CHECKBOX = 2

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

    var activeColor = 0
    var inactiveColor = 0

    fun applyColors() {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val thumbColors = intArrayOf(activeColor, inactiveColor)
        val trackColors = intArrayOf(activeColor.withAlpha(0.4f), inactiveColor.withAlpha(0.4f))
        when (control) {
            is MaterialSwitch -> {
                control.thumbTintList = ColorStateList(states, thumbColors)
                control.trackTintList = ColorStateList(states, trackColors)
            }
            is MaterialCheckBox -> control.buttonTintList = ColorStateList(states, thumbColors)
        }
    }

    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        activeColor = color.toColorInt()
        applyColors()
    }
    accent.attachTo(control)
    val border = ThemeBridge.border(env)
    border.observe { color ->
        inactiveColor = color.toColorInt()
        applyColors()
    }
    border.attachTo(control)

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
