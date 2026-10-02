package dev.waterui.android.runtime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout-transparent modifiers (`.offset`, `.rotation`, `.scale`) fold into
 * the view they wrap instead of claiming a ViewGroup: each op keeps its own
 * animated value and clock, and every frame the composed affine is written
 * from all ops' current values — preserving WaterUI's modifier order,
 * including the orbit a rotation outside an offset produces mid-animation —
 * while adding zero views to the hierarchy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FoldedTransformTest {
    private companion object {
        const val SIZE = 36
        const val EPS = 1e-3f
        val SQRT_HALF = (Math.sqrt(2.0) / 2.0).toFloat()
    }

    private fun context(): Context =
        Robolectric.buildActivity(android.app.Activity::class.java).get()

    private fun sizedView(context: Context): View =
        View(context).apply { layout(0, 0, SIZE, SIZE) }

    private fun bezier(durationMillis: Long = 10_000) =
        WuiAnimation.Bezier(
            durationMillis = durationMillis,
            x1 = 0f, y1 = 0f, x2 = 1f, y2 = 1f
        )

    @Test
    fun foldingProducesNoAdditionalViews() {
        val view = sizedView(context())
        val fold = FoldedTransform.on(view)
        val offset = fold.addOffset()
        fold.addRotation(0.5f, 0.5f)
        fold.recompose(offset, WuiAnimation.None)

        // The chain lives on the view itself: no wrapper, no extra child.
        assertSame(fold, FoldedTransform.on(view))
        assertNull(view.parent)
        assertEquals(0f, view.pivotX, EPS)
        assertEquals(0f, view.pivotY, EPS)
    }

    @Test
    fun offsetInsideRotationOrbitsTheAnchor() {
        // `.offset(d).rotation(r)`: the rotated view's own frame is offset, so
        // the content orbits the anchor — T′ = (a − R·a) + R·d.
        val view = sizedView(context())
        val fold = FoldedTransform.on(view)
        val offset = fold.addOffset()
        val rotation = fold.addRotation(0.5f, 0.5f)
        offset.x = 40f
        rotation.degrees = 90f
        fold.recompose(offset, WuiAnimation.None)
        fold.recompose(rotation, WuiAnimation.None)

        // a = (18,18); R90·a = (−18,18); a − R·a = (36,0); R90·d = (0,40).
        assertEquals(90f, view.rotation, EPS)
        assertEquals(36f, view.translationX, EPS)
        assertEquals(40f, view.translationY, EPS)
    }

    @Test
    fun rotationInsideOffsetTranslatesAfterRotating() {
        // `.rotation(r).offset(d)`: the content rotates about its anchor, then
        // the offset shifts it — T′ = (a − R·a) + d.
        val view = sizedView(context())
        val fold = FoldedTransform.on(view)
        val rotation = fold.addRotation(0.5f, 0.5f)
        val offset = fold.addOffset()
        rotation.degrees = 90f
        offset.x = 40f
        fold.recompose(rotation, WuiAnimation.None)
        fold.recompose(offset, WuiAnimation.None)

        // a − R·a = (36,0), then + d = (76,0).
        assertEquals(90f, view.rotation, EPS)
        assertEquals(76f, view.translationX, EPS)
        assertEquals(0f, view.translationY, EPS)
    }

    @Test
    fun scaleOutsideRotationIsRefusedForTheWrapperFallback() {
        val view = sizedView(context())
        val fold = FoldedTransform.on(view)
        fold.addRotation(0.5f, 0.5f)

        // scale·rotation is a shear a single View cannot express — the caller
        // keeps an AnchoredTransformLayout for that op instead of folding a
        // wrong approximation.
        assertNull(fold.addScale(0.5f, 0.5f))
    }

    @Test
    fun scaleInsideRotationComposesIntoTprimeRdotS() {
        // `.scale(s).rotation(r)` is expressible: L′ = R·S stays in class.
        val view = sizedView(context())
        val fold = FoldedTransform.on(view)
        val scale = checkNotNull(fold.addScale(0.5f, 0.5f))
        val rotation = fold.addRotation(0.5f, 0.5f)
        scale.x = 2f
        scale.y = 2f
        rotation.degrees = 0f
        fold.recompose(scale, WuiAnimation.None)
        fold.recompose(rotation, WuiAnimation.None)

        // No rotation yet: scale about the centre anchor leaves the frame
        // centred — T′ = a − S·a = (18,18) − (36,36) = (−18,−18).
        assertEquals(2f, view.scaleX, EPS)
        assertEquals(2f, view.scaleY, EPS)
        assertEquals(-18f, view.translationX, EPS)
        assertEquals(-18f, view.translationY, EPS)
    }

    @Test
    fun anAnimatingRotationOrbitsTheOffsetEveryFrame() {
        // `.offset(10,0).rotation(r, anchor=(0,0))` with r animating 0→90: at
        // the halfway frame the offset must sit on the arc — R(45°)·(10,0) =
        // (7.071,7.071) — not on the chord between the endpoints (5,5),
        // which is what interpolating the composed endpoints produces.
        val view = attachedView()
        val fold = FoldedTransform.on(view)
        val offset = fold.addOffset()
        val rotation = fold.addRotation(0f, 0f)
        offset.x = 10f
        fold.recompose(offset, WuiAnimation.None)
        rotation.degrees = 90f
        fold.recompose(rotation, bezier())

        val animator = checkNotNull(rotation.bezier)
        animator.setCurrentFraction(0.5f)

        assertEquals(45f, view.rotation, EPS)
        assertEquals(10f * SQRT_HALF, view.translationX, 0.01f)
        assertEquals(10f * SQRT_HALF, view.translationY, 0.01f)

        animator.setCurrentFraction(1f)
        assertEquals(0f, view.translationX, EPS)
        assertEquals(10f, view.translationY, EPS)
    }

    @Test
    fun anAnimatedUpdateLeavesAnUnrelatedLegUntouched() {
        // Two ops animate on their own clocks: updating the offset's target
        // mid-rotation must not cancel, restart or snap the rotation's leg.
        val view = attachedView()
        val fold = FoldedTransform.on(view)
        val offset = fold.addOffset()
        val rotation = fold.addRotation(0f, 0f)
        offset.x = 10f
        rotation.degrees = 90f
        fold.recompose(offset, bezier(durationMillis = 20_000))
        fold.recompose(rotation, bezier(durationMillis = 5_000))
        val rotationAnimator = checkNotNull(rotation.bezier)
        rotationAnimator.setCurrentFraction(0.5f)
        assertEquals(45f, view.rotation, EPS)

        offset.x = 20f
        fold.recompose(offset, bezier(durationMillis = 20_000))

        assertNotSame(rotation.bezier, offset.bezier)
        assertSame(rotationAnimator, rotation.bezier)
        assertTrue(rotationAnimator.isRunning)
        // The rotation still owns its trajectory; its frames now recompose
        // the orbit around the offset's *current* value.
        rotationAnimator.setCurrentFraction(1f)
        assertEquals(90f, view.rotation, EPS)
    }

    @Test
    fun aNonAnimatedUpdateSnapsOnlyItsOwnOp() {
        // A spring rotation is mid-flight when the offset moves without
        // animation: the offset snaps, the rotation's spring keeps running,
        // and the composed translation is read off the rotation's *current*
        // angle, not its 90° target.
        val view = attachedView()
        val fold = FoldedTransform.on(view)
        val offset = fold.addOffset()
        val rotation = fold.addRotation(0f, 0f)
        offset.x = 10f
        fold.recompose(offset, WuiAnimation.None)
        rotation.degrees = 90f
        fold.recompose(rotation, WuiAnimation.Spring(stiffness = 1f, damping = 1f))
        val rotationSpring = rotation.springs[0]
        assertNotNull(rotationSpring)

        offset.x = 20f
        fold.recompose(offset, WuiAnimation.None)

        assertEquals(20f, offset.channelValue(0), EPS)
        assertTrue(rotationSpring!!.isRunning)
        // θ's current value is still 0 (no frame has run): the snap left the
        // offset lying on the rotation's start frame, translation (20,0).
        assertEquals(20f, view.translationX, EPS)
        assertEquals(0f, view.translationY, EPS)
    }

    @Test
    fun aRetargetStartsItsOwnLegFromTheCurrentValue() {
        // Retargeting mid-flight keeps the op's own animation contract: the
        // new leg starts from the interpolated value, easing from zero —
        // unrelated ops' legs are not part of the restart.
        val view = attachedView()
        val fold = FoldedTransform.on(view)
        val offset = fold.addOffset()
        val rotation = fold.addRotation(0f, 0f)
        offset.x = 10f
        fold.recompose(offset, WuiAnimation.None)
        rotation.degrees = 90f
        fold.recompose(rotation, bezier())
        rotation.bezier!!.setCurrentFraction(0.5f)
        assertEquals(45f, rotation.channelValue(0), EPS)

        rotation.degrees = 0f
        fold.recompose(rotation, bezier())
        val retarget = checkNotNull(rotation.bezier)
        retarget.setCurrentFraction(0.5f)

        // Half of the way 45° → 0°: the orbit, not a snap, carries it back.
        assertEquals(22.5f, rotation.channelValue(0), EPS)
        assertEquals(22.5f, view.rotation, EPS)
        assertEquals(
            10f * Math.cos(Math.toRadians(22.5)).toFloat(),
            view.translationX,
            0.01f
        )
        assertEquals(
            10f * Math.sin(Math.toRadians(22.5)).toFloat(),
            view.translationY,
            0.01f
        )
    }

    @Test
    fun detachingTheViewRetiresItsAnimationLegs() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java)
            .setup()
            .get()
        val parent = android.widget.FrameLayout(activity)
        activity.setContentView(parent)
        val view = View(activity)
        parent.addView(view, ViewGroup.LayoutParams(SIZE, SIZE))
        view.layout(0, 0, SIZE, SIZE)
        val fold = FoldedTransform.on(view)
        val rotation = fold.addRotation(0f, 0f)
        rotation.degrees = 90f
        fold.recompose(rotation, bezier())
        val animator = checkNotNull(rotation.bezier)

        parent.removeView(view)

        assertFalse(animator.isRunning)
        assertNull(rotation.bezier)
        // Detach retires legs at their targets so no animator holds the view
        // past its window — and reattach shows the final state, not a
        // mid-flight fossil.
        assertEquals(90f, rotation.channelValue(0), EPS)
    }

    private fun attachedView(): View {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java)
            .setup()
            .get()
        return View(activity).also {
            it.layoutParams = ViewGroup.LayoutParams(SIZE, SIZE)
            activity.setContentView(it)
            it.layout(0, 0, SIZE, SIZE)
        }
    }
}
