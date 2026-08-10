package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.slider.Slider
import dev.waterui.android.layout.AxisExpandingLinearLayout
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

private val sliderTypeId: WuiTypeId by lazy { NativeBindings.waterui_slider_id().toTypeId() }

private val sliderRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_slider(node.rawPtr)
    val binding = WuiBinding.double(struct.bindingPtr)
    val rangeStart = struct.rangeStart.toFloat()
    val rangeEnd = struct.rangeEnd.toFloat()
    require(rangeStart.isFinite() && rangeEnd.isFinite() && rangeStart < rangeEnd) {
        "slider range must contain finite increasing bounds"
    }

    val container = AxisExpandingLinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView)

    val slider = Slider(context).apply {
        valueFrom = rangeStart
        valueTo = rangeEnd
        stepSize = 0f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    container.addView(slider)

    if (struct.minLabelPtr != 0L || struct.maxLabelPtr != 0L) {
        val minMaxRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        if (struct.minLabelPtr != 0L) {
            val minLabel = inflateAnyView(context, struct.minLabelPtr, env, registry)
            minMaxRow.addView(minLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        if (struct.maxLabelPtr != 0L) {
            val maxLabel = inflateAnyView(context, struct.maxLabelPtr, env, registry)
            minMaxRow.addView(maxLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(minMaxRow)
    }

    binding.observe { value ->
        require(value.isFinite() && value in struct.rangeStart..struct.rangeEnd) {
            "slider value $value is outside its range"
        }
        val floatValue = value.toFloat()
        if (slider.value != floatValue) {
            slider.value = floatValue
        }
    }

    slider.addOnChangeListener { _, newValue, fromUser ->
        if (fromUser) {
            binding.set(newValue.toDouble())
        }
    }
    val accent = ThemeBridge.accent(env)
    accent.observe { color ->
        val tint = ColorStateList.valueOf(color.toColorInt())
        slider.thumbTintList = tint
        slider.trackActiveTintList = tint
        slider.haloTintList = tint
    }
    accent.attachTo(slider)
    val border = ThemeBridge.border(env)
    border.observe { color ->
        slider.trackInactiveTintList = ColorStateList.valueOf(color.toColorInt())
    }
    border.attachTo(slider)

    val disabled = InteractionBridge.disabled(env)
    disabled.observe { isDisabled ->
        slider.isEnabled = !isDisabled
    }
    disabled.attachTo(slider)

    installSemanticAccessibilityLabel(
        target = slider,
        content = labelView,
        labelPtr = struct.accessibilityLabelPtr,
        env = env
    )

    container.disposeWith(binding)
    container
}

internal fun RegistryBuilder.registerWuiSlider() {
    register({ sliderTypeId }, sliderRenderer)
}
