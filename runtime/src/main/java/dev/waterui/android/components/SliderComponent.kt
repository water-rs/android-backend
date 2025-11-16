package dev.waterui.android.components

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.slider.Slider
import dev.waterui.android.reactive.WuiBinding
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

private val sliderTypeId: WuiTypeId by lazy { NativeBindings.waterui_slider_id().toTypeId() }

private val sliderRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_slider(node.rawPtr)
    val binding = WuiBinding.double(struct.bindingPtr, env)

    val container = LinearLayout(context).apply {
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

    var updating = false
    binding.observe { value ->
        val floatValue = value.toFloat()
        if (slider.value != floatValue) {
            updating = true
            slider.value = floatValue.coerceIn(slider.valueFrom, slider.valueTo)
            updating = false
        }
    }

    slider.addOnChangeListener { _, newValue, fromUser ->
        if (fromUser && !updating) {
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

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiSlider() {
    register({ sliderTypeId }, sliderRenderer)
}
