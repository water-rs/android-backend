package dev.waterui.android.runtime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The transform a chain of layout-transparent metadata modifiers — `.offset`,
 * `.rotation`, `.scale` — deposits on the view they wrap instead of claiming
 * a ViewGroup of their own.
 *
 * Each op is appended in inflation order, which is WaterUI's application
 * order (the inner modifier inflates first, so the list reads innermost to
 * outermost). An op is a translation plus a linear part (offset: T=d, L=I;
 * rotation about anchor a: T=a−R·a, L=R; scale about a: T=a−S·a, L=S), and
 * composing outer over inner gives T′ = T_out + L_out·T_in, L′ = L_out·L_in.
 * Anchors are normalized, so the recompute reads the view's current size and
 * runs again on every layout pass.
 *
 * View properties can only express T·R·S — scale applied about the pivot
 * first, then rotation, then translation — so the append gate keeps the
 * chain inside that class: offsets and rotations always fold (a rotation
 * outside an offset lands its rotated offset in T′ = R·d, preserving the
 * orbit), while a scale outside a rotation is a shear and is refused, in
 * which case the caller keeps one wrapper layout for that op.
 *
 * Animation is per op, not per composed endpoint. Interpolating the composed
 * transform is wrong: a rotation animating about an anchor moves the
 * composed translation along an arc (T′ = a − R(θ)·a + R(θ)·d), not along a
 * line between the endpoints' translations. So every op animates its own
 * value with its own clock — a `.rotation` drives `degrees`, an `.offset`
 * drives x and y — and every animation frame recomposes the affine from all
 * ops' *current* values and writes the view's five channels. An update on
 * one op starts, retargets or cancels only that op's animators; an unrelated
 * leg keeps its own interpolated value, easing and lifetime. A non-animated
 * update snaps its op's current value to target and cancels that op's legs.
 */
internal class FoldedTransform private constructor(private val view: View) {

    sealed class Op {
        /** The op's animation legs — one Bezier animator or per-channel springs. */
        internal var bezier: ValueAnimator? = null
        internal val springs = mutableMapOf<Int, SpringAnimation>()

        internal fun cancelAnimations() {
            bezier?.cancel()
            bezier = null
            springs.values.forEach(SpringAnimation::cancel)
            springs.clear()
        }

        /** Snap every channel's current value to its renderer-written target. */
        internal abstract fun snapToTarget()

        internal abstract fun channelCount(): Int

        /** The channel's current (possibly mid-flight) value. */
        internal abstract fun channelValue(index: Int): Float

        /** The channel's target value, as last written by the renderer. */
        internal abstract fun channelTarget(index: Int): Float

        /** Set the channel's current value — the animator's write path. */
        internal abstract fun setChannelValue(index: Int, value: Float)
    }

    /** A `.offset` — pure translation in px. */
    class OffsetOp : Op() {
        var x = 0f
        var y = 0f
        private var curX = 0f
        private var curY = 0f

        override fun snapToTarget() {
            curX = x
            curY = y
        }

        override fun channelCount() = 2

        override fun channelValue(index: Int) = if (index == 0) curX else curY

        override fun channelTarget(index: Int) = if (index == 0) x else y

        override fun setChannelValue(index: Int, value: Float) {
            if (index == 0) curX = value else curY = value
        }
    }

    /** A `.rotation` — degrees about a normalized anchor of the view's size. */
    class RotationOp(val anchorX: Float, val anchorY: Float) : Op() {
        var degrees = 0f
        private var curDegrees = 0f

        override fun snapToTarget() {
            curDegrees = degrees
        }

        override fun channelCount() = 1

        override fun channelValue(index: Int) = curDegrees

        override fun channelTarget(index: Int) = degrees

        override fun setChannelValue(index: Int, value: Float) {
            curDegrees = value
        }
    }

    /** A `.scale` — factors about a normalized anchor of the view's size. */
    class ScaleOp(val anchorX: Float, val anchorY: Float) : Op() {
        var x = 1f
        var y = 1f
        private var curX = 1f
        private var curY = 1f

        override fun snapToTarget() {
            curX = x
            curY = y
        }

        override fun channelCount() = 2

        override fun channelValue(index: Int) = if (index == 0) curX else curY

        override fun channelTarget(index: Int) = if (index == 0) x else y

        override fun setChannelValue(index: Int, value: Float) {
            if (index == 0) curX = value else curY = value
        }
    }

    private val ops = mutableListOf<Op>()
    private var hasRotation = false

    fun addOffset(): OffsetOp = OffsetOp().also(ops::add)

    fun addRotation(anchorX: Float, anchorY: Float): RotationOp =
        RotationOp(anchorX, anchorY).also {
            ops += it
            hasRotation = true
        }

    /**
     * Appends a scale op, or returns null when this view's chain already
     * carries a rotation — scale·rotation is a shear the View property set
     * cannot express, so the caller must wrap the op in a layout instead.
     */
    fun addScale(anchorX: Float, anchorY: Float): ScaleOp? {
        if (hasRotation) return null
        return ScaleOp(anchorX, anchorY).also(ops::add)
    }

    /**
     * Applies the renderer's new target for [op]. The animation spec owns the
     * op's legs only: `None` (or an unattached view) snaps the op's current
     * values to target and cancels its animators, while every other op's
     * in-flight legs keep running untouched.
     */
    fun recompose(op: Op, animation: WuiAnimation) {
        if (!view.isAttachedToWindow || animation == WuiAnimation.None) {
            op.snapToTarget()
            op.cancelAnimations()
            applyComposed()
            return
        }
        when (animation) {
            WuiAnimation.None -> error("non-animated transforms are applied before dispatch")
            is WuiAnimation.Bezier -> animateBezier(op, animation)
            is WuiAnimation.Spring -> animateSprings(op, animation)
        }
    }

    /**
     * Drives the op's channels from their current values to target over one
     * clock and one easing. A retarget restarts this op's animator from the
     * current interpolated values — retargeting is the animation contract —
     * but no other op's animator is disturbed.
     */
    private fun animateBezier(op: Op, animation: WuiAnimation.Bezier) {
        op.cancelAnimations()
        val from = FloatArray(op.channelCount()) { op.channelValue(it) }
        val to = FloatArray(op.channelCount()) { op.channelTarget(it) }
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animation.durationMillis
            interpolator = animation.toInterpolator()
            addUpdateListener { update ->
                val fraction = update.animatedValue as Float
                for (i in 0 until op.channelCount()) {
                    op.setChannelValue(i, from[i] + (to[i] - from[i]) * fraction)
                }
                applyComposed()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (op.bezier === animation) op.bezier = null
                }
            })
        }
        op.bezier = animator
        animator.start()
    }

    /**
     * Spring legs are per channel and preserve physics on retarget: an
     * existing spring is retargeted with [SpringAnimation.animateToFinalPosition]
     * so it keeps its velocity instead of restarting.
     */
    private fun animateSprings(op: Op, animation: WuiAnimation.Spring) {
        op.bezier?.cancel()
        op.bezier = null
        val dampingRatio = animation.damping / (2f * sqrt(animation.stiffness))
        for (i in 0 until op.channelCount()) {
            val target = op.channelTarget(i)
            val running = op.springs[i]
            if (running != null) {
                running.animateToFinalPosition(target)
                continue
            }
            val holder = FloatValueHolder(op.channelValue(i))
            val spring = SpringAnimation(holder).apply {
                this.spring = SpringForce(target).apply {
                    stiffness = animation.stiffness
                    this.dampingRatio = dampingRatio
                }
                addUpdateListener { _, value, _ ->
                    op.setChannelValue(i, value)
                    applyComposed()
                }
                addEndListener { _, _, _, _ ->
                    op.springs.remove(i, this)
                }
            }
            op.springs[i] = spring
            spring.start()
        }
    }

    /**
     * Writes the affine composed from all ops' current values onto the view.
     * Called on every animation frame of every op — an animating rotation
     * orbits a static offset through T′ = R(θ)·d because the translation is
     * recomputed from θ's current value, and a mid-flight retarget keeps the
     * other ops' interpolated values in the composition.
     */
    internal fun applyComposed() {
        var tx = 0f
        var ty = 0f
        var rotation = 0f
        var scaleX = 1f
        var scaleY = 1f
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        for (op in ops) {
            when (op) {
                is OffsetOp -> {
                    tx += op.channelValue(0)
                    ty += op.channelValue(1)
                }
                is RotationOp -> {
                    val ax = op.anchorX * w
                    val ay = op.anchorY * h
                    val radians = Math.toRadians(op.channelValue(0).toDouble())
                    val cos = cos(radians).toFloat()
                    val sin = sin(radians).toFloat()
                    // Rotate about the anchor: T′ = (a − R·a) + R·T.
                    val nextX = ax - (cos * ax - sin * ay) + (cos * tx - sin * ty)
                    val nextY = ay - (sin * ax + cos * ay) + (sin * tx + cos * ty)
                    tx = nextX
                    ty = nextY
                    rotation += op.channelValue(0)
                }
                is ScaleOp -> {
                    val ax = op.anchorX * w
                    val ay = op.anchorY * h
                    // Scale about the anchor: T′ = (a − S·a) + S·T.
                    tx = ax - op.channelValue(0) * ax + op.channelValue(0) * tx
                    ty = ay - op.channelValue(1) * ay + op.channelValue(1) * ty
                    scaleX *= op.channelValue(0)
                    scaleY *= op.channelValue(1)
                }
            }
        }
        view.scaleX = scaleX
        view.scaleY = scaleY
        view.rotation = rotation
        view.translationX = tx
        view.translationY = ty
    }

    /** Snap and stop every op's legs — the view is leaving the window. */
    private fun cancelAll() {
        ops.forEach {
            it.snapToTarget()
            it.cancelAnimations()
        }
    }

    companion object {
        /**
         * The view's folded-transform state, created on first use: the pivot is
         * pinned to the origin (anchors live in the composed translation, so a
         * single pivot cannot represent mixed-op chains), a layout listener
         * recomposes whenever the view's size changes, and detach retires all
         * animation legs so no animator holds the view past its window.
         */
        fun on(view: View): FoldedTransform {
            val existing =
                view.getTag(R.id.wui_folded_transform) as? FoldedTransform
            if (existing != null) return existing
            val fold = FoldedTransform(view)
            view.pivotX = 0f
            view.pivotY = 0f
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                fold.applyComposed()
            }
            view.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) = fold.cancelAll()
                }
            )
            view.setTag(R.id.wui_folded_transform, fold)
            return fold
        }
    }
}
