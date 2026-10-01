package dev.waterui.android.runtime

import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * The transform a chain of layout-transparent metadata modifiers — `.offset`,
 * `.rotation`, `.scale` — deposits on the view they wrap instead of claiming
 * a ViewGroup of their own.
 *
 * Each op is appended in inflation order, which is WaterUI's application
 * order (the inner modifier inflates first, so the list reads innermost to
 * outermost). On every input change the composed affine is recomputed: an op
 * is a translation plus a linear part (offset: T=d, L=I; rotation about
 * anchor a: T=a−R·a, L=R; scale about a: T=a−S·a, L=S), and composing outer
 * over inner gives T′ = T_out + L_out·T_in, L′ = L_out·L_in. Anchors are
 * normalized, so the recompute reads the view's current size and runs again
 * on every layout pass.
 *
 * View properties can only express T·R·S — scale applied about the pivot
 * first, then rotation, then translation — so the append gate keeps the
 * chain inside that class: offsets and rotations always fold (a rotation
 * outside an offset lands its rotated offset in T′ = R·d, preserving the
 * orbit), while a scale outside a rotation is a shear and is refused, in
 * which case the caller keeps one wrapper layout for that op.
 */
internal class FoldedTransform private constructor(private val view: View) {

    sealed interface Op

    /** A `.offset` — pure translation in px. */
    class OffsetOp : Op {
        var x = 0f
        var y = 0f
    }

    /** A `.rotation` — degrees about a normalized anchor of the view's size. */
    class RotationOp(val anchorX: Float, val anchorY: Float) : Op {
        var degrees = 0f
    }

    /** A `.scale` — factors about a normalized anchor of the view's size. */
    class ScaleOp(val anchorX: Float, val anchorY: Float) : Op {
        var x = 1f
        var y = 1f
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

    /** Recomputes the composed transform and applies it through [animation]. */
    fun recompose(animation: WuiAnimation) {
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
                    tx += op.x
                    ty += op.y
                }
                is RotationOp -> {
                    val ax = op.anchorX * w
                    val ay = op.anchorY * h
                    val radians = Math.toRadians(op.degrees.toDouble())
                    val cos = cos(radians).toFloat()
                    val sin = sin(radians).toFloat()
                    // Rotate about the anchor: T′ = (a − R·a) + R·T.
                    val nextX = ax - (cos * ax - sin * ay) + (cos * tx - sin * ty)
                    val nextY = ay - (sin * ax + cos * ay) + (sin * tx + cos * ty)
                    tx = nextX
                    ty = nextY
                    rotation += op.degrees
                }
                is ScaleOp -> {
                    val ax = op.anchorX * w
                    val ay = op.anchorY * h
                    // Scale about the anchor: T′ = (a − S·a) + S·T.
                    tx = ax - op.x * ax + op.x * tx
                    ty = ay - op.y * ay + op.y * ty
                    scaleX *= op.x
                    scaleY *= op.y
                }
            }
        }
        view.applyRustTransform(
            animation,
            ViewTransform(
                scaleX = scaleX,
                scaleY = scaleY,
                rotation = rotation,
                translationX = tx,
                translationY = ty
            )
        )
    }

    companion object {
        /**
         * The view's folded-transform state, created on first use: the pivot is
         * pinned to the origin (anchors live in the composed translation, so a
         * single pivot cannot represent mixed-op chains) and a layout listener
         * recomposes whenever the view's size changes.
         */
        fun on(view: View): FoldedTransform {
            val existing =
                view.getTag(R.id.wui_folded_transform) as? FoldedTransform
            if (existing != null) return existing
            val fold = FoldedTransform(view)
            view.pivotX = 0f
            view.pivotY = 0f
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                fold.recompose(WuiAnimation.None)
            }
            view.setTag(R.id.wui_folded_transform, fold)
            return fold
        }
    }
}
