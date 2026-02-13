package dev.waterui.android.components

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.LinearLayout
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import com.google.android.material.slider.Slider
import dev.waterui.android.layout.AxisExpandingLinearLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRustAnimation
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.interpolatorFor
import dev.waterui.android.runtime.springForceFrom
import dev.waterui.android.runtime.toColorInt

import java.util.concurrent.atomic.AtomicBoolean

private val sliderTypeId: WuiTypeId by lazy { WatcherJni.sliderId().toTypeId() }

private val sliderRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsSlider(node.rawPtr)
    val binding = WuiBinding.double(struct.bindingPtr, env)

    // Slider is StretchAxis::Horizontal (Rust-defined):
    // report a minimum usable width in size_that_fits, then expand during place.
    val container = AxisExpandingLinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

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
    var sliderValueAnimator: ValueAnimator? = null
    var sliderSpringAnimator: SpringAnimation? = null
    binding.observeWithAnimation { value, animation ->
        val floatValue = value.toFloat()
        if (slider.value != floatValue && !updating.get()) {
            animateSliderValue(
                slider = slider,
                targetValue = floatValue.coerceIn(slider.valueFrom, slider.valueTo),
                animation = animation,
                updating = updating,
                valueAnimatorRef = { sliderValueAnimator },
                setValueAnimator = { sliderValueAnimator = it },
                springAnimatorRef = { sliderSpringAnimator },
                setSpringAnimator = { sliderSpringAnimator = it }
            )
        }
    }

    slider.addOnChangeListener { _, newValue, fromUser ->
        if (fromUser && !updating.get()) {
            binding.set(newValue.toDouble())
        }
    }
    val accent = ThemeBridge.accent(env)
    accent.observeWithAnimation { color, animation ->
        slider.applyRustAnimation(animation) {
            val tint = ColorStateList.valueOf(color.toColorInt())
            slider.thumbTintList = tint
            slider.trackActiveTintList = tint
            slider.haloTintList = tint
        }
    }
    accent.attachTo(slider)
    val border = ThemeBridge.border(env)
    border.observeWithAnimation { color, animation ->
        slider.applyRustAnimation(animation) {
            slider.trackInactiveTintList = ColorStateList.valueOf(color.toColorInt())
        }
    }
    border.attachTo(slider)

    container.disposeWith(binding)
    container.disposeWith {
        sliderValueAnimator?.cancel()
        sliderSpringAnimator?.cancel()
    }
    container
}

internal fun RegistryBuilder.registerWuiSlider() {
    register({ sliderTypeId }, sliderRenderer)
}

private fun animateSliderValue(
    slider: Slider,
    targetValue: Float,
    animation: WuiAnimation,
    updating: AtomicBoolean,
    valueAnimatorRef: () -> ValueAnimator?,
    setValueAnimator: (ValueAnimator?) -> Unit,
    springAnimatorRef: () -> SpringAnimation?,
    setSpringAnimator: (SpringAnimation?) -> Unit
) {
    valueAnimatorRef()?.cancel()
    springAnimatorRef()?.cancel()

    if (!slider.isAttachedToWindow || !animation.shouldAnimate) {
        updating.set(true)
        slider.value = targetValue
        updating.set(false)
        return
    }

    when (animation) {
        is WuiAnimation.Spring -> {
            val holder = FloatValueHolder(slider.value)
            val spring = SpringAnimation(holder).apply {
                spring = springForceFrom(animation).apply { finalPosition = targetValue }
                addUpdateListener { _, value, _ ->
                    updating.set(true)
                    slider.value = value.coerceIn(slider.valueFrom, slider.valueTo)
                    updating.set(false)
                }
            }
            setSpringAnimator(spring)
            spring.start()
        }
        else -> {
            val animator = ValueAnimator.ofFloat(slider.value, targetValue).apply {
                duration = animation.durationMs
                interpolator = interpolatorFor(animation)
                addUpdateListener { valueAnimator ->
                    val next = valueAnimator.animatedValue as Float
                    updating.set(true)
                    slider.value = next.coerceIn(slider.valueFrom, slider.valueTo)
                    updating.set(false)
                }
            }
            setValueAnimator(animator)
            animator.start()
        }
    }
}
