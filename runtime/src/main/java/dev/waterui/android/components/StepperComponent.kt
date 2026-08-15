package dev.waterui.android.components

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonGroup
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.ReactiveStyledText
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyResolvedFont
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView

private val stepperTypeId: WuiTypeId by lazy { NativeBindings.waterui_stepper_id().toTypeId() }

// The −/+ pair is a Material connected button group: the group owns the
// shared silhouette, inner-corner morphing, and RTL corner order that the
// previous hand-built GradientDrawable got wrong, and the buttons keep their
// Widget.Material3 colors, typography, and touch targets.
private val stepperRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_stepper(node.rawPtr)
    val binding = WuiBinding.int(struct.bindingPtr)
    val stepComputed = WuiComputed.int(struct.stepPtr)
    val rangeStart = struct.rangeStart
    val rangeEnd = struct.rangeEnd
    require(rangeStart <= rangeEnd) { "stepper range must not be empty" }

    val spacingPx = 8f.dp(context).toInt()

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    val labelParams = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f
    ).apply {
        marginEnd = spacingPx
    }
    container.addView(labelView, labelParams)

    if (struct.valueFormatterPtr != 0L) {
        val valueView = TextView(context)
        val bodyFont = ThemeBridge.bodyFont(env)
        bodyFont.observe(valueView::applyResolvedFont)
        bodyFont.attachTo(valueView)
        val value = ReactiveStyledText(struct.valueFormatterPtr, env)
        value.attach { styled -> valueView.text = styled }
        container.addView(
            valueView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = spacingPx }
        )
        container.disposeWith(value)
    }

    val controlGroup = MaterialButtonGroup(context)
    val decrement = MaterialButton(context).apply { text = "−" }
    val increment = MaterialButton(context).apply { text = "+" }
    controlGroup.addView(decrement)
    controlGroup.addView(increment)
    container.addView(controlGroup)

    labelView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    container.disposeWith(
        bindSemanticAccessibilityLabel(struct.accessibilityLabelPtr, env) { label ->
            decrement.contentDescription = context.getString(R.string.wui_decrement_value, label)
            increment.contentDescription = context.getString(R.string.wui_increment_value, label)
        }
    )

    var stepValue = 1
    var currentValue = rangeStart
    fun updateEnabledState() {
        decrement.isEnabled = currentValue > rangeStart
        increment.isEnabled = currentValue < rangeEnd
    }
    stepComputed.observe { value ->
        require(value > 0) { "stepper step must be positive" }
        stepValue = value
    }
    binding.observe { value ->
        require(value in rangeStart..rangeEnd) { "stepper value $value is outside its range" }
        currentValue = value
        updateEnabledState()
    }

    decrement.setOnClickListener {
        val newValue = (currentValue.toLong() - stepValue)
            .coerceAtLeast(rangeStart.toLong())
            .toInt()
        binding.set(newValue)
    }

    increment.setOnClickListener {
        val newValue = (currentValue.toLong() + stepValue)
            .coerceAtMost(rangeEnd.toLong())
            .toInt()
        binding.set(newValue)
    }

    container.disposeWith(binding)
    container.disposeWith(stepComputed)
    container
}

internal fun RegistryBuilder.registerWuiStepper() {
    register({ stepperTypeId }, stepperRenderer)
}
