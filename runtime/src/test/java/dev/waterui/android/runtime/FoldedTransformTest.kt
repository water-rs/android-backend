package dev.waterui.android.runtime

import android.content.Context
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout-transparent modifiers (`.offset`, `.rotation`, `.scale`) fold into
 * the view they wrap instead of claiming a ViewGroup: the ops compose into
 * the view's own translation/rotation/scale, preserving WaterUI's modifier
 * order semantics — including the orbit a rotation outside an offset
 * produces — while adding zero views to the hierarchy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FoldedTransformTest {
    private companion object {
        const val SIZE = 36
        const val EPS = 1e-4f
    }

    private fun context(): Context =
        Robolectric.buildActivity(android.app.Activity::class.java).get()

    private fun sizedView(context: Context): View =
        View(context).apply { layout(0, 0, SIZE, SIZE) }

    @Test
    fun foldingProducesNoAdditionalViews() {
        val view = sizedView(context())
        val fold = FoldedTransform.on(view)
        fold.addOffset()
        fold.addRotation(0.5f, 0.5f)
        FoldedTransform.on(view).recompose(WuiAnimation.None)

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
        fold.recompose(WuiAnimation.None)

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
        fold.recompose(WuiAnimation.None)

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
        fold.recompose(WuiAnimation.None)

        // No rotation yet: scale about the centre anchor leaves the frame
        // centred — T′ = a − S·a = (18,18) − (36,36) = (−18,−18).
        assertEquals(2f, view.scaleX, EPS)
        assertEquals(2f, view.scaleY, EPS)
        assertEquals(-18f, view.translationX, EPS)
        assertEquals(-18f, view.translationY, EPS)
    }
}
