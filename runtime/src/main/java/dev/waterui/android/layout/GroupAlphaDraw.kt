package dev.waterui.android.layout

import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import dev.waterui.android.components.WuiButtonLayout
import dev.waterui.android.runtime.R
import kotlin.math.roundToInt

/**
 * Group-opacity compositing shared by every WaterUI container.
 *
 * SwiftUI's opacity modifier composites the whole subtree and then applies
 * alpha to the result, without clipping content a child draws outside its
 * layout frame (translations, shadows, unclipped shapes). Neither stock
 * Android path matches that: `hasOverlappingRendering` true promotes
 * `alpha < 1` to a compositing layer sized to the view's bounds, which clips
 * overflow; returning false drops the layer but distributes the alpha onto
 * each child's draw commands, so overlapping children darken each other
 * instead of compositing as one group.
 *
 * So the host keeps the framework out of the alpha business entirely —
 * `onSetAlpha` claims the value, `hasOverlappingRendering` returns false —
 * and this helper composites the group's *entire* draw with
 * [Canvas.saveLayerAlpha]: background, onDraw, dispatchDraw (including
 * strokes a subclass paints there), decorations and foreground. The layer
 * wraps `View.draw`, not `dispatchDraw`, because a border's foreground and a
 * subclass's post-children paint both run after dispatchDraw returns and
 * would escape the fade. The bound is the union of each descendant's drawn
 * bounds mapped through its translation, rotation and scale, not the layout
 * frame — WaterUI's ancestor layouts set `clipChildren = false`, so that
 * overflow reaches the screen.
 */
internal class GroupAlphaDraw {
    private val mappedBounds = RectF()
    private val layerBounds = RectF()
    private val clipBounds = Rect()

    /**
     * Set by [draw]'s walk: part of the subtree draws outside its view
     * bounds — an elevation shadow or a populated view overlay — so the
     * layer must not carry a tight bound.
     */
    internal var subtreeDrawsOutsideBounds = false
        private set

    fun draw(host: ViewGroup, canvas: Canvas, drawAll: (Canvas) -> Unit) {
        val alpha = host.alpha
        if (alpha >= 1f) {
            drawAll(canvas)
            return
        }
        // The host's own background/onDraw pixels live inside its rect.
        layerBounds.set(
            0f, 0f, host.width.toFloat(), host.height.toFloat()
        )
        subtreeDrawsOutsideBounds = drawsOutsideOwnBounds(host)
        for (i in 0 until host.childCount) {
            accumulateDrawnBounds(host, host.getChildAt(i))
        }
        // A shadow caster's drawn extent is not its view bounds — the
        // elevation shadow spills past them by an amount HWUI itself does
        // not bound tightly: RenderProperties.getClipDamageToBounds()
        // disables damage clipping entirely once a node's Z exceeds 0.
        // Populated overlays and foreign views that paint or overlay past
        // their rect are likewise unbounded. For those the layer expands to
        // the canvas clip — the largest region the ancestor chain permits
        // this host to draw into — a wider buffer that never cuts content.
        if (subtreeDrawsOutsideBounds) {
            if (canvas.getClipBounds(clipBounds)) {
                layerBounds.set(clipBounds)
            } else {
                layerBounds.setEmpty()
            }
        }
        val saveCount =
            canvas.saveLayerAlpha(
                layerBounds,
                (alpha * 255f).roundToInt().coerceIn(0, 255),
            )
        try {
            drawAll(canvas)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /** True while [view] casts an elevation shadow: positive Z and an outline to project. */
    private fun castsShadow(view: View): Boolean =
        view.z > 0f && view.outlineProvider != null

    /**
     * True when [view] can draw past its own rect: a shadow caster, a view
     * the runtime tagged when populating its overlay
     * (`R.id.wui_overlay_content`), or a class outside the runtime's
     * control — `ViewOverlay`/`ViewGroupOverlay` expose no public content
     * enumeration and a foreign view may also paint beyond its bounds, so
     * anything not known-bounded conservatively widens the layer.
     */
    private fun drawsOutsideOwnBounds(view: View): Boolean {
        return castsShadow(view) ||
            view.getTag(R.id.wui_overlay_content) == true ||
            !isRuntimeBounded(view)
    }

    /** Classes the runtime itself inflates, which draw only inside their bounds. */
    private fun isRuntimeBounded(view: View): Boolean {
        return view is PassThroughFrameLayout ||
            view is RustLayoutViewGroup ||
            view is AxisExpandingLinearLayout ||
            view is WuiMeasurableLinearLayout ||
            view is ViewportClipLayout ||
            view is WuiButtonLayout
    }

    /** Unions the drawn bounds of [view] and its descendants into [layerBounds]. */
    private fun accumulateDrawnBounds(host: ViewGroup, view: View) {
        if (view.visibility != View.VISIBLE) return
        if (drawsOutsideOwnBounds(view)) {
            subtreeDrawsOutsideBounds = true
        }
        mappedBounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        view.matrix.mapRect(mappedBounds)
        mappedBounds.offset(view.left.toFloat(), view.top.toFloat())
        layerBounds.union(mappedBounds)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                accumulateDescendant(host, view.getChildAt(i), view)
            }
        }
    }

    /**
     * Unions [view]'s drawn bounds into [layerBounds]. [view] sits inside
     * [parent]'s coordinate space, so after mapping its local rect through its
     * own matrix and position the rect is walked up the ancestor chain —
     * each level's matrix (translation, rotation, scale about its pivot) and
     * layout position — until it lands in the host's coordinates.
     */
    private fun accumulateDescendant(host: ViewGroup, view: View, parent: ViewGroup) {
        if (view.visibility != View.VISIBLE) return
        if (drawsOutsideOwnBounds(view)) {
            subtreeDrawsOutsideBounds = true
        }
        mappedBounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        view.matrix.mapRect(mappedBounds)
        mappedBounds.offset(view.left.toFloat(), view.top.toFloat())
        var ancestor: View = parent
        while (ancestor !== host) {
            ancestor.matrix.mapRect(mappedBounds)
            mappedBounds.offset(ancestor.left.toFloat(), ancestor.top.toFloat())
            val grand = ancestor.parent
            if (grand is View) ancestor = grand else break
        }
        layerBounds.union(mappedBounds)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                accumulateDescendant(host, view.getChildAt(i), view)
            }
        }
    }

}
