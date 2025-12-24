package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.ToggleStyle
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

import dev.waterui.android.runtime.withAlpha
import java.util.concurrent.atomic.AtomicBoolean

private val toggleTypeId: WuiTypeId by lazy { NativeBindings.waterui_toggle_id().toTypeId() }

private val toggleRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_toggle(node.rawPtr)
    val binding = WuiBinding.bool(struct.bindingPtr, env)
    val style = struct.toggleStyle()

    // Create the appropriate control based on style
    val toggleControl: CompoundButton = when (style) {
        ToggleStyle.CHECKBOX -> MaterialCheckBox(context)
        else -> MaterialSwitch(context)  // AUTOMATIC and SWITCH both use MaterialSwitch
    }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    container.addView(toggleControl)

    var activeColor = 0
    var inactiveColor = 0

    fun applyColors() {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        when (toggleControl) {
            is MaterialSwitch -> {
                val thumbColors = intArrayOf(activeColor, inactiveColor)
                val trackColors = intArrayOf(activeColor.withAlpha(0.4f), inactiveColor.withAlpha(0.4f))
                toggleControl.thumbTintList = ColorStateList(states, thumbColors)
                toggleControl.trackTintList = ColorStateList(states, trackColors)
            }
            is MaterialCheckBox -> {
                val checkColors = intArrayOf(activeColor, inactiveColor)
                toggleControl.buttonTintList = ColorStateList(states, checkColors)
            }
        }
    }

    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        activeColor = color.toColorInt()
        applyColors()
    }
    accent.attachTo(toggleControl)
    val border = ThemeBridge.border(env)
    border.observe { color ->
        inactiveColor = color.toColorInt()
        applyColors()
    }
    border.attachTo(toggleControl)

    val updating = AtomicBoolean(false)
    binding.observe { value ->
        if (toggleControl.isChecked != value && !updating.get()) {
            updating.set(true)
            toggleControl.isChecked = value
            updating.set(false)
        }
    }
    toggleControl.setOnCheckedChangeListener { _, isChecked ->
        if (!updating.get()) {
            binding.set(isChecked)
        }
    }

    container.disposeWith(binding)
    container
}

internal fun RegistryBuilder.registerWuiToggle() {
    register({ toggleTypeId }, toggleRenderer)
}
