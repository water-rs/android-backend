package dev.waterui.android.runtime

import android.view.View
import android.view.ViewPropertyAnimator
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

/**
 * The five animatable property channels a view transform is made of; the
 * spring legs keep one animator per channel so each can be superseded alone.
 */
internal enum class TransformChannel {
    SCALE_X, SCALE_Y, ROTATION, TRANSLATION_X, TRANSLATION_Y
}

internal fun WuiAnimation.Bezier.toInterpolator(): PathInterpolator =
    PathInterpolator(x1, y1, x2, y2)

/** The channel each spring drives, so a superseded leg can cancel alone. */
internal fun View.transformSpringAnimations(): Map<TransformChannel, SpringAnimation> =
    (getTag(R.id.wui_transform_spring_animations) as? TransformSpringAnimations)
        ?.values.orEmpty()

internal fun View.applyRustTransform(animation: WuiAnimation, transform: ViewTransform) {
    animate().cancel()
    transformSpringAnimations().values.forEach(SpringAnimation::cancel)
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
                .setInterpolator(animation.toInterpolator())
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
    val animations = buildMap {
        transform.scaleX?.let {
            put(TransformChannel.SCALE_X, spring(DynamicAnimation.SCALE_X, it, animation, dampingRatio))
        }
        transform.scaleY?.let {
            put(TransformChannel.SCALE_Y, spring(DynamicAnimation.SCALE_Y, it, animation, dampingRatio))
        }
        transform.rotation?.let {
            put(TransformChannel.ROTATION, spring(DynamicAnimation.ROTATION, it, animation, dampingRatio))
        }
        transform.translationX?.let {
            put(TransformChannel.TRANSLATION_X, spring(DynamicAnimation.TRANSLATION_X, it, animation, dampingRatio))
        }
        transform.translationY?.let {
            put(TransformChannel.TRANSLATION_Y, spring(DynamicAnimation.TRANSLATION_Y, it, animation, dampingRatio))
        }
    }
    val owner = TransformSpringAnimations(animations)
    setTag(R.id.wui_transform_spring_animations, owner)
    animations.values.forEach { running ->
        running.addEndListener { _, _, _, _ ->
            if (
                getTag(R.id.wui_transform_spring_animations) === owner &&
                animations.values.none(SpringAnimation::isRunning)
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

internal class TransformSpringAnimations(
    val values: Map<TransformChannel, SpringAnimation>
)
