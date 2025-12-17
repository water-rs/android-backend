package dev.waterui.android.runtime

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator

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

    val interpolator = when (animation) {
        is WuiAnimation.Linear -> LinearInterpolator()
        is WuiAnimation.EaseIn -> AccelerateInterpolator(2.0f)
        is WuiAnimation.EaseOut -> DecelerateInterpolator(2.0f)
        is WuiAnimation.EaseInOut, is WuiAnimation.Default -> AccelerateDecelerateInterpolator()
        is WuiAnimation.Spring -> {
            // For spring animations, we apply the update immediately
            // and let AndroidX SpringAnimation handle visual interpolation if needed
            // This is a simplified implementation; full spring support would use
            // androidx.dynamicanimation.animation.SpringAnimation
            update()
            return
        }
        else -> LinearInterpolator()
    }

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

    val interpolator = when (animation) {
        is WuiAnimation.Linear -> LinearInterpolator()
        is WuiAnimation.EaseIn -> AccelerateInterpolator(2.0f)
        is WuiAnimation.EaseOut -> DecelerateInterpolator(2.0f)
        is WuiAnimation.EaseInOut, is WuiAnimation.Default -> AccelerateDecelerateInterpolator()
        is WuiAnimation.Spring -> {
            // Spring requires AndroidX dynamicanimation for proper support
            // Fall back to ease-in-out for now
            AccelerateDecelerateInterpolator()
        }
        else -> LinearInterpolator()
    }

    animate()
        .setDuration(animation.durationMs)
        .setInterpolator(interpolator)
        .apply(configure)
        .start()
}
