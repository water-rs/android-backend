package dev.waterui.android.runtime

import android.animation.TimeInterpolator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.sqrt

/**
 * Applies a Rust animation to a view update.
 *
 * For cross-fade transitions (view content changes), this performs
 * a fade-out, update, fade-in sequence with the appropriate duration and timing.
 *
 * @param animation The animation configuration from Rust
 * @param update The update closure to execute (typically changes view content)
 */
fun View.applyRustAnimation(animation: WuiAnimation, update: () -> Unit) {
    if (!isAttachedToWindow || !animation.shouldAnimate) {
        update()
        return
    }

    if (animation is WuiAnimation.Spring) {
        // Spring is handled by components that support it.
        update()
        return
    }

    val interpolator = interpolatorFor(animation)

    val halfDuration = animation.durationMs / 2

    animate().cancel()
    animate()
        .alpha(0f)
        .setDuration(halfDuration)
        .setInterpolator(interpolator)
        .withEndAction {
            update()
            animate()
                .alpha(1f)
                .setDuration(halfDuration)
                .setInterpolator(interpolator)
                .start()
        }
        .start()
}

/**
 * Applies a Rust animation for property changes that can be animated directly.
 *
 * Unlike [applyRustAnimation] which does cross-fade, this applies the animation
 * timing to the view's property animator for smooth value transitions.
 *
 * @param animation The animation configuration from Rust
 * @param configure Closure to configure additional animator properties
 */
fun View.withRustAnimator(animation: WuiAnimation, configure: android.view.ViewPropertyAnimator.() -> Unit) {
    if (!isAttachedToWindow || !animation.shouldAnimate) {
        // No animation - configure with duration 0
        animate().setDuration(0).apply(configure).start()
        return
    }

    val interpolator = interpolatorFor(animation)

    animate()
        .setDuration(animation.durationMs)
        .setInterpolator(interpolator)
        .apply(configure)
        .start()
}

internal fun springForceFrom(animation: WuiAnimation.Spring): SpringForce {
    val stiffness = animation.stiffness.coerceAtLeast(1f)
    val dampingRatio = dampingRatioFrom(stiffness, animation.damping)
    return SpringForce()
        .setStiffness(stiffness)
        .setDampingRatio(dampingRatio)
}

internal fun interpolatorFor(animation: WuiAnimation): TimeInterpolator = when (animation) {
    is WuiAnimation.Linear -> LinearInterpolator()
    is WuiAnimation.EaseIn -> AccelerateInterpolator(2.0f)
    is WuiAnimation.EaseOut -> DecelerateInterpolator(2.0f)
    is WuiAnimation.EaseInOut, is WuiAnimation.Default, is WuiAnimation.Spring ->
        AccelerateDecelerateInterpolator()
    else -> LinearInterpolator()
}

private fun dampingRatioFrom(stiffness: Float, damping: Float): Float {
    if (stiffness <= 0f) {
        return SpringForce.DAMPING_RATIO_NO_BOUNCY
    }
    val ratio = damping / (2f * sqrt(stiffness.toDouble()).toFloat())
    return ratio.coerceIn(0.05f, 5f)
}
