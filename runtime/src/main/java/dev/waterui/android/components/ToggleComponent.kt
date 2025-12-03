package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.materialswitch.MaterialSwitch
import dev.waterui.android.reactive.WuiBinding
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
import java.util.concurrent.atomic.AtomicBoolean

private val toggleTypeId: WuiTypeId by lazy { NativeBindings.waterui_toggle_id().toTypeId() }

private val toggleRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_toggle(node.rawPtr)
    val binding = WuiBinding.bool(struct.bindingPtr, env)
    val switch = MaterialSwitch(context)
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    container.addView(switch)

    var activeColor = 0
    var inactiveColor = 0

    fun applyColors() {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val thumbColors = intArrayOf(activeColor, inactiveColor)
        val trackColors = intArrayOf(activeColor.withAlpha(0.4f), inactiveColor.withAlpha(0.4f))
        switch.thumbTintList = ColorStateList(states, thumbColors)
        switch.trackTintList = ColorStateList(states, trackColors)
    }

    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        activeColor = color.toColorInt()
        applyColors()
    }
    accent.attachTo(switch)
    val border = ThemeBridge.border(env)
    border.observe { color ->
        inactiveColor = color.toColorInt()
        applyColors()
    }
    border.attachTo(switch)

    val updating = AtomicBoolean(false)
    binding.observe { value ->
        if (switch.isChecked != value && !updating.get()) {
            updating.set(true)
            switch.isChecked = value
            updating.set(false)
        }
    }
    switch.setOnCheckedChangeListener { _, isChecked ->
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
