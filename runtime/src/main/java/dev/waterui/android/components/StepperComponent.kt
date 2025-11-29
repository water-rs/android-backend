package dev.waterui.android.components

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
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
    
    val density = context.resources.displayMetrics.density
    val cornerRadiusPx = 12 * density
    val buttonSize = (44 * density).toInt()
    val valueMinWidth = (48 * density).toInt()
    val spacingPx = (8 * density).toInt()

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // Add label if present, with spacing
    if (struct.labelPtr != 0L) {
        val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
        val labelParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = spacingPx
        }
        container.addView(labelView, labelParams)
    }

    // Stepper control group with connected button styling
    val controlGroup = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Add a subtle background to group the controls
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(0x10000000) // Very subtle gray background
        }
    }

    // Decrement button - rounded left corners only
    val decrement = MaterialButton(context).apply {
        text = "−" // Use proper minus sign
        textSize = 18f
        minWidth = buttonSize
        minimumWidth = buttonSize
        minHeight = buttonSize
        minimumHeight = buttonSize
        insetTop = 0
        insetBottom = 0
        setPadding(0, 0, 0, 0)
        shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCorner(CornerFamily.ROUNDED, cornerRadiusPx)
            .setBottomLeftCorner(CornerFamily.ROUNDED, cornerRadiusPx)
            .setTopRightCorner(CornerFamily.ROUNDED, 0f)
            .setBottomRightCorner(CornerFamily.ROUNDED, 0f)
            .build()
    }

    // Value display - centered with minimum width
    val valueView = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = valueMinWidth
        setPadding(spacingPx, 0, spacingPx, 0)
    }

    // Increment button - rounded right corners only
    val increment = MaterialButton(context).apply {
        text = "+"
        textSize = 18f
        minWidth = buttonSize
        minimumWidth = buttonSize
        minHeight = buttonSize
        minimumHeight = buttonSize
        insetTop = 0
        insetBottom = 0
        setPadding(0, 0, 0, 0)
        shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setTopLeftCorner(CornerFamily.ROUNDED, 0f)
            .setBottomLeftCorner(CornerFamily.ROUNDED, 0f)
            .setTopRightCorner(CornerFamily.ROUNDED, cornerRadiusPx)
            .setBottomRightCorner(CornerFamily.ROUNDED, cornerRadiusPx)
            .build()
    }

    controlGroup.addView(decrement)
    controlGroup.addView(valueView)
    controlGroup.addView(increment)
    container.addView(controlGroup)

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
