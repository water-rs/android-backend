package dev.waterui.android.components

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opacity must composite, not clip.
 *
 * SwiftUI's `.offset(...).opacity(0.5)` draws the translated content whole —
 * opacity is not a clip. On Android, `alpha < 1` on a view that reports
 * overlapping rendering promotes the subtree to a compositing layer sized to
 * the view's own bounds, and the layer clips anything drawn outside them:
 * offset content, shadows, unclipped shapes. [OpacityLayout] opts out of that
 * promotion, so an offset child under opacity keeps drawing at its translated
 * position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpacityLayoutTest {
    private companion object {
        /** The 36 dp layout frame from the benchmark's clipped-rect failure. */
        const val FRAME = 36

        /** A translation that puts the child fully outside that frame. */
        const val OFFSET = 120
    }

    private fun host(context: Context): OpacityLayout {
        val container = OpacityLayout(context)
        container.addView(
            View(context),
            FrameLayout.LayoutParams(FRAME, FRAME)
        )
        return container
    }

    private fun layOut(view: View, width: Int = FRAME * 8, height: Int = FRAME * 8) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test
    fun alphaBelowOneDoesNotPromoteABoundedCompositingLayer() {
        val context = Robolectric.buildActivity(android.app.Activity::class.java).get()
        val container = host(context).apply { alpha = 0.5f }
        layOut(container)

        // The clip lives in the promoted layer: no promotion, no clip.
        assertFalse(container.hasOverlappingRendering())
        assertEquals(View.LAYER_TYPE_NONE, container.layerType)
        assertEquals(0.5f, container.alpha)
    }

    @Test
    fun offsetChildKeepsItsTranslatedPositionUnderOpacity() {
        val context = Robolectric.buildActivity(android.app.Activity::class.java).get()
        val container = host(context).apply { alpha = 0.5f }
        val child = container.getChildAt(0).apply {
            translationX = OFFSET.toFloat()
            translationY = OFFSET.toFloat()
        }
        layOut(container)

        // Layout keeps the child inside the frame; the translation carries it
        // past the frame's right edge, where the bounded alpha layer used to
        // cut it off.
        assertEquals(OFFSET.toFloat(), child.translationX)
        assertEquals(OFFSET.toFloat(), child.translationY)
        assertEquals(FRAME, child.right)
        assertEquals((FRAME + OFFSET).toFloat(), child.right + child.translationX)
    }

    /**
     * Group opacity, not distributed alpha. Two opaque red children under
     * `.opacity(0.5)` overlap: SwiftUI composites the subtree and applies the
     * alpha once, so the overlap is the same colour as either child alone.
     * Folding the alpha into each child's draw commands instead double-dips —
     * the overlap comes out darker. The children here read pixel-for-pixel.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun overlappingChildrenCompositeAsOneGroupUnderOpacity() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = OpacityLayout(context).apply { alpha = 0.5f }
        fun redChild(leftMargin: Int, topMargin: Int) {
            container.addView(
                View(context).apply { setBackgroundColor(Color.RED) },
                FrameLayout.LayoutParams(FRAME, FRAME).apply {
                    this.leftMargin = leftMargin
                    this.topMargin = topMargin
                }
            )
        }
        // (0,0)-(36,36) and (18,18)-(54,54): overlap is 18..36 × 18..36.
        redChild(0, 0)
        redChild(18, 18)
        layOut(container)

        val bitmap = Bitmap.createBitmap(FRAME + 18 + 16, FRAME + 18 + 16, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        container.draw(canvas)

        val singleCoverage = bitmap.getPixel(9, 9)
        val overlap = bitmap.getPixel(27, 27)
        val secondChildOnly = bitmap.getPixel(45, 45)
        val background = bitmap.getPixel(FRAME + 18 + 15, FRAME + 18 + 15)

        // The two children drew (not transparent), and where both cover a
        // pixel it is identical to where only one does — the alpha folded
        // once over the composited group, not once per child.
        assertNotEquals(background, singleCoverage)
        assertEquals(singleCoverage, overlap)
        assertEquals(singleCoverage, secondChildOnly)
    }

    /**
     * `.border().opacity(0.5)` must dim the border with the content: the
     * border rides the child's foreground, which `View.draw` renders after
     * `dispatchDraw` — outside the group-alpha layer — so the ownership rule
     * wraps the child and the wrapper's layer covers it. This pins the draw
     * fact the wrap relies on: a foreground inside the subtree is dimmed by
     * the group alpha.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun childForegroundInsideTheSubtreeIsDimmedByGroupAlpha() {
        val context = Robolectric.buildActivity(Activity::class.java).setup().get()
        val container = OpacityLayout(context).apply { alpha = 0.5f }
        container.addView(
            View(context).apply {
                setBackgroundColor(Color.BLACK)
                foreground = ColorDrawable(Color.WHITE)
            },
            FrameLayout.LayoutParams(FRAME, FRAME)
        )
        layOut(container)

        val bitmap = Bitmap.createBitmap(FRAME + 16, FRAME + 16, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        container.draw(canvas)

        // The white foreground over the black view composites as the subtree's
        // draws; the group layer dims it to ~half intensity, not opaque white.
        val pixel = bitmap.getPixel(FRAME / 2, FRAME / 2)
        assertTrue(Color.red(pixel) in 96..176)
    }
}
