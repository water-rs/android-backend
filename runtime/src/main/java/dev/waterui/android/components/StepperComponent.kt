package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.toTypeId

private val stepperTypeId: WuiTypeId by lazy { NativeBindings.waterui_stepper_id().toTypeId() }

private val stepperRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_stepper(node.rawPtr)
    val binding = WuiBinding.int(struct.bindingPtr, env)
    val stepComputed = struct.stepPtr.takeIf { it != 0L }?.let { WuiComputed.int(it, env) }
    val rangeStart = struct.rangeStart
    val rangeEnd = struct.rangeEnd

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    if (struct.labelPtr != 0L) {
        val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
        container.addView(labelView)
    }

    val decrement = MaterialButton(context).apply { text = "-" }
    val increment = MaterialButton(context).apply { text = "+" }
    val valueView = TextView(context)

    container.addView(decrement)
    container.addView(valueView)
    container.addView(increment)

    var stepValue = 1
    stepComputed?.observe { value -> stepValue = value }

    binding.observe { value ->
        valueView.text = value.toString()
    }

    decrement.setOnClickListener {
        val newValue = (binding.current() - stepValue).coerceAtLeast(rangeStart)
        binding.set(newValue)
    }

    increment.setOnClickListener {
        val newValue = (binding.current() + stepValue).coerceAtMost(rangeEnd)
        binding.set(newValue)
    }
    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        val tint = ColorStateList.valueOf(color.toColorInt())
        decrement.backgroundTintList = tint
        increment.backgroundTintList = tint
    }
    accent.attachTo(container)
    val accentForeground = ThemeBridge.accentForeground(env)
    accentForeground.observe { color ->
        val colorInt = color.toColorInt()
        decrement.setTextColor(colorInt)
        increment.setTextColor(colorInt)
    }
    accentForeground.attachTo(container)
    val foreground = ThemeBridge.foreground(env)
    foreground.observe { color ->
        valueView.setTextColor(color.toColorInt())
    }
    foreground.attachTo(valueView)

    container.disposeWith(binding)
    stepComputed?.let { container.disposeWith(it) }
    container
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiStepper() {
    register({ stepperTypeId }, stepperRenderer)
}
