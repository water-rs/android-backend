package dev.waterui.android.runtime

import android.animation.ValueAnimator
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation

/**
 * Animates a floating-point value using WaterUI animation metadata.
 */
internal class AnimatedFloat(
    initialValue: Float,
    private val update: (Float) -> Unit
) {
    private var current = initialValue
    private var valueAnimator: ValueAnimator? = null
    private var springAnimation: SpringAnimation? = null
    private val valueHolder = FloatValueHolder(initialValue)

    fun apply(target: Float, animation: WuiAnimation) {
        if (!animation.shouldAnimate || !target.isFinite()) {
            cancel()
            current = target
            update(target)
            return
        }

        when (animation) {
            is WuiAnimation.Spring -> {
                valueAnimator?.cancel()
                val spring = springAnimation ?: SpringAnimation(valueHolder).also { anim ->
                    anim.addUpdateListener { _, value, _ ->
                        current = value
                        update(value)
                    }
                }
                spring.setStartValue(current)
                spring.spring = springForceFrom(animation).setFinalPosition(target)
                spring.start()
                springAnimation = spring
            }
            else -> {
                springAnimation?.cancel()
                val animator = ValueAnimator.ofFloat(current, target).apply {
                    duration = animation.durationMs
                    interpolator = interpolatorFor(animation)
                    addUpdateListener { anim ->
                        val value = anim.animatedValue as Float
                        current = value
                        update(value)
                    }
                }
                valueAnimator?.cancel()
                valueAnimator = animator
                animator.start()
            }
        }
    }

    fun cancel() {
        valueAnimator?.cancel()
        springAnimation?.cancel()
    }
}
