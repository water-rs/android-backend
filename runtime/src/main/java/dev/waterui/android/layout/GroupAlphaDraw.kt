package dev.waterui.android.layout

import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isEmpty
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
 * and this helper composites the group in `dispatchDraw` with
 * [Canvas.saveLayerAlpha] over the subtree's drawn bounds: the union of each
 * descendant's bounds mapped through its translation, rotation and scale,
 * not the layout frame. WaterUI's ancestor layouts set `clipChildren =
 * false`, so that overflow reaches the screen.
 */
internal class GroupAlphaDraw {
    private val mappedBounds = RectF()
    private val layerBounds = RectF()

    fun dispatchDraw(host: ViewGroup, canvas: Canvas, drawChildren: (Canvas) -> Unit) {
        val alpha = host.alpha
        if (alpha >= 1f || host.isEmpty()) {
            drawChildren(canvas)
            return
        }
        layerBounds.setEmpty()
        for (i in 0 until host.childCount) {
            accumulateDrawnBounds(host, host.getChildAt(i))
        }
        if (layerBounds.isEmpty) {
            drawChildren(canvas)
            return
        }
        val saveCount =
            canvas.saveLayerAlpha(layerBounds, (alpha * 255f).roundToInt().coerceIn(0, 255))
        try {
            drawChildren(canvas)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /** Unions the drawn bounds of [view] and its descendants into [layerBounds]. */
    private fun accumulateDrawnBounds(host: ViewGroup, view: View) {
        if (view.visibility != View.VISIBLE) return
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
