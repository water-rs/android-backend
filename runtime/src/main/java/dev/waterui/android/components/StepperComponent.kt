package dev.waterui.android.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.ReactiveStyledText
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

private val stepperTypeId: WuiTypeId by lazy { NativeBindings.waterui_stepper_id().toTypeId() }

private val stepperRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_stepper(node.rawPtr)
    val binding = WuiBinding.int(struct.bindingPtr)
    val stepComputed = WuiComputed.int(struct.stepPtr)
    val rangeStart = struct.rangeStart
    val rangeEnd = struct.rangeEnd
    require(rangeStart <= rangeEnd) { "stepper range must not be empty" }

    val cornerRadiusPx = 12f.dp(context)
    val buttonSize = 44f.dp(context).toInt()
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

    val controlGroup = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
        }
    }

    val decrement = stepperButton(
        context,
        "−",
        buttonSize,
        stepperShape(cornerRadiusPx, roundsLeadingCorners = true)
    )
    val increment = stepperButton(
        context,
        "+",
        buttonSize,
        stepperShape(cornerRadiusPx, roundsLeadingCorners = false)
    )

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

    val surfaceVariant = ThemeBridge.surfaceVariant(env)
    surfaceVariant.observe { color ->
        (controlGroup.background as GradientDrawable).setColor(color.toColorInt())
    }
    surfaceVariant.attachTo(container)

    container.disposeWith(binding)
    container.disposeWith(stepComputed)
    container
}

private fun stepperButton(
    context: Context,
    label: String,
    size: Int,
    shape: ShapeAppearanceModel
): MaterialButton = MaterialButton(context).apply {
    text = label
    textSize = 18f
    minWidth = size
    minimumWidth = size
    minHeight = size
    minimumHeight = size
    insetTop = 0
    insetBottom = 0
    setPadding(0, 0, 0, 0)
    shapeAppearanceModel = shape
}

private fun stepperShape(
    radius: Float,
    roundsLeadingCorners: Boolean
): ShapeAppearanceModel {
    val leadingRadius = if (roundsLeadingCorners) radius else 0f
    val trailingRadius = if (roundsLeadingCorners) 0f else radius
    return ShapeAppearanceModel.builder()
        .setTopLeftCorner(CornerFamily.ROUNDED, leadingRadius)
        .setBottomLeftCorner(CornerFamily.ROUNDED, leadingRadius)
        .setTopRightCorner(CornerFamily.ROUNDED, trailingRadius)
        .setBottomRightCorner(CornerFamily.ROUNDED, trailingRadius)
        .build()
}

internal fun RegistryBuilder.registerWuiStepper() {
    register({ stepperTypeId }, stepperRenderer)
}
