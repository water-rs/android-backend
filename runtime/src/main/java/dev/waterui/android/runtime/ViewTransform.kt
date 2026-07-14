package dev.waterui.android.runtime

import android.view.View
import android.view.animation.PathInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.sqrt

internal data class ViewTransform(
    val scaleX: Float? = null,
    val scaleY: Float? = null,
    val rotation: Float? = null,
    val translationX: Float? = null,
    val translationY: Float? = null
)

internal fun View.applyRustTransform(animation: WuiAnimation, transform: ViewTransform) {
    animate().cancel()
    transformSpringAnimations().forEach(SpringAnimation::cancel)
    setTag(R.id.wui_transform_spring_animations, null)

    if (!isAttachedToWindow || animation == WuiAnimation.None) {
        applyTransform(transform)
        return
    }

    when (animation) {
        WuiAnimation.None -> error("non-animated transforms are applied before animation dispatch")
        is WuiAnimation.Bezier -> {
            val animator = animate()
                .setDuration(animation.durationMillis)
                .setInterpolator(
                    PathInterpolator(animation.x1, animation.y1, animation.x2, animation.y2)
                )
            transform.scaleX?.let(animator::scaleX)
            transform.scaleY?.let(animator::scaleY)
            transform.rotation?.let(animator::rotation)
            transform.translationX?.let(animator::translationX)
            transform.translationY?.let(animator::translationY)
            animator.start()
        }
        is WuiAnimation.Spring -> animateSpring(animation, transform)
    }
}

private fun View.applyTransform(transform: ViewTransform) {
    transform.scaleX?.let { scaleX = it }
    transform.scaleY?.let { scaleY = it }
    transform.rotation?.let { rotation = it }
    transform.translationX?.let { translationX = it }
    transform.translationY?.let { translationY = it }
}

private fun View.animateSpring(animation: WuiAnimation.Spring, transform: ViewTransform) {
    val dampingRatio = animation.damping / (2f * sqrt(animation.stiffness))
    val animations = buildList {
        transform.scaleX?.let { add(spring(DynamicAnimation.SCALE_X, it, animation, dampingRatio)) }
        transform.scaleY?.let { add(spring(DynamicAnimation.SCALE_Y, it, animation, dampingRatio)) }
        transform.rotation?.let { add(spring(DynamicAnimation.ROTATION, it, animation, dampingRatio)) }
        transform.translationX?.let {
            add(spring(DynamicAnimation.TRANSLATION_X, it, animation, dampingRatio))
        }
        transform.translationY?.let {
            add(spring(DynamicAnimation.TRANSLATION_Y, it, animation, dampingRatio))
        }
    }
    val owner = TransformSpringAnimations(animations)
    setTag(R.id.wui_transform_spring_animations, owner)
    animations.forEach { running ->
        running.addEndListener { _, _, _, _ ->
            if (
                getTag(R.id.wui_transform_spring_animations) === owner &&
                animations.none(SpringAnimation::isRunning)
            ) {
                setTag(R.id.wui_transform_spring_animations, null)
            }
        }
        running.start()
    }
}

private fun View.spring(
    property: DynamicAnimation.ViewProperty,
    target: Float,
    animation: WuiAnimation.Spring,
    dampingRatio: Float
): SpringAnimation = SpringAnimation(this, property, target).apply {
    spring = SpringForce(target).apply {
        stiffness = animation.stiffness
        this.dampingRatio = dampingRatio
    }
}

private class TransformSpringAnimations(val values: List<SpringAnimation>)

private fun View.transformSpringAnimations(): List<SpringAnimation> =
    (getTag(R.id.wui_transform_spring_animations) as? TransformSpringAnimations)?.values.orEmpty()
