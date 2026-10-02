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
 * The five animatable property channels a folded transform is made of. The
 * legs are independent — a channel may be snapped, animated or left running
 * on its own animator while the others are untouched.
 */
internal enum class TransformChannel {
    SCALE_X, SCALE_Y, ROTATION, TRANSLATION_X, TRANSLATION_Y;

    companion object {
        val ALL: Set<TransformChannel> = entries.toSet()
    }
}

internal fun ViewTransform.channelValue(channel: TransformChannel): Float? =
    when (channel) {
        TransformChannel.SCALE_X -> scaleX
        TransformChannel.SCALE_Y -> scaleY
        TransformChannel.ROTATION -> rotation
        TransformChannel.TRANSLATION_X -> translationX
        TransformChannel.TRANSLATION_Y -> translationY
    }

/** The channels whose composed value differs between this transform and [next]. */
internal fun ViewTransform.changedChannels(next: ViewTransform): Set<TransformChannel> =
    TransformChannel.ALL.filterTo(mutableSetOf()) {
        channelValue(it) != next.channelValue(it)
    }

internal fun WuiAnimation.Bezier.toInterpolator(): PathInterpolator =
    PathInterpolator(x1, y1, x2, y2)

internal fun ViewPropertyAnimator.setChannel(
    channel: TransformChannel,
    value: Float
): ViewPropertyAnimator = when (channel) {
    TransformChannel.SCALE_X -> scaleX(value)
    TransformChannel.SCALE_Y -> scaleY(value)
    TransformChannel.ROTATION -> rotation(value)
    TransformChannel.TRANSLATION_X -> translationX(value)
    TransformChannel.TRANSLATION_Y -> translationY(value)
}

/** Sets only [channels] of [transform] on the view, leaving the rest alone. */
internal fun View.applyTransformChannels(
    channels: Set<TransformChannel>,
    transform: ViewTransform
) {
    channels.forEach { channel ->
        when (channel) {
            TransformChannel.SCALE_X -> transform.scaleX?.let { scaleX = it }
            TransformChannel.SCALE_Y -> transform.scaleY?.let { scaleY = it }
            TransformChannel.ROTATION -> transform.rotation?.let { rotation = it }
            TransformChannel.TRANSLATION_X ->
                transform.translationX?.let { translationX = it }
            TransformChannel.TRANSLATION_Y ->
                transform.translationY?.let { translationY = it }
        }
    }
}

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
