package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.slider.Slider
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

import java.util.concurrent.atomic.AtomicBoolean

private val sliderTypeId: WuiTypeId by lazy { NativeBindings.waterui_slider_id().toTypeId() }

private val sliderRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_slider(node.rawPtr)
    val binding = WuiBinding.double(struct.bindingPtr, env)

    // Slider is axis-expanding: expands width to fill available space
    val container = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Expand to fill available width (axis-expanding behavior)
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val expandedWidthSpec = if (widthMode == MeasureSpec.AT_MOST || widthMode == MeasureSpec.EXACTLY) {
                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY)
            } else {
                widthMeasureSpec
            }
            super.onMeasure(expandedWidthSpec, heightMeasureSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
    }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView)

    val slider = Slider(context).apply {
        valueFrom = struct.rangeStart.toFloat()
        valueTo = struct.rangeEnd.toFloat()
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

    val updating = AtomicBoolean(false)
    binding.observe { value ->
        val floatValue = value.toFloat()
        if (slider.value != floatValue && !updating.get()) {
            updating.set(true)
            slider.value = floatValue.coerceIn(slider.valueFrom, slider.valueTo)
            updating.set(false)
        }
    }

    slider.addOnChangeListener { _, newValue, fromUser ->
        if (fromUser && !updating.get()) {
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

    container.disposeWith(binding)
    container
}

internal fun RegistryBuilder.registerWuiSlider() {
    register({ sliderTypeId }, sliderRenderer)
}
