package dev.waterui.android.runtime

import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.sqrt

fun View.applyRustAnimation(animation: WuiAnimation, update: () -> Unit) {
    cancelRustAnimation()
    if (!isAttachedToWindow) {
        update()
        return
    }

    when (animation) {
        WuiAnimation.None -> update()
        is WuiAnimation.Bezier -> crossFade(animation, update)
        is WuiAnimation.Spring -> springFade(animation, update)
    }
}

private fun View.crossFade(animation: WuiAnimation.Bezier, update: () -> Unit) {
    val fadeOutDuration = animation.durationMillis / 2
    val interpolator = PathInterpolator(animation.x1, animation.y1, animation.x2, animation.y2)
    animate()
        .alpha(0f)
        .setDuration(fadeOutDuration)
        .setInterpolator(interpolator)
        .withEndAction {
            update()
            animate()
                .alpha(1f)
                .setDuration(animation.durationMillis - fadeOutDuration)
                .setInterpolator(interpolator)
                .start()
        }
        .start()
}

private fun View.springFade(animation: WuiAnimation.Spring, update: () -> Unit) {
    alpha = 0f
    update()
    val springAnimation = SpringAnimation(this, DynamicAnimation.ALPHA, 1f).apply {
        spring = SpringForce(1f).apply {
            stiffness = animation.stiffness
            dampingRatio = animation.damping / (2f * sqrt(animation.stiffness))
        }
        addEndListener { running, _, _, _ ->
            if (getTag(R.id.wui_spring_animation) === running) {
                setTag(R.id.wui_spring_animation, null)
            }
        }
    }
    setTag(R.id.wui_spring_animation, springAnimation)
    springAnimation.start()
}

private fun View.cancelRustAnimation() {
    animate().cancel()
    (getTag(R.id.wui_spring_animation) as? SpringAnimation)?.cancel()
    setTag(R.id.wui_spring_animation, null)
    alpha = 1f
}
