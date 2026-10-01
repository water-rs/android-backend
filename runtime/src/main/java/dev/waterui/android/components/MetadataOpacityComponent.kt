package dev.waterui.android.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isEmpty
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import kotlin.math.roundToInt

private val metadataOpacityTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_opacity_id().toTypeId()
}

/**
 * Host for an `.opacity` subtree.
 *
 * SwiftUI's opacity modifier composites the whole subtree and then applies
 * alpha to the result, without clipping content a child draws outside its
 * layout frame (translations, shadows, unclipped shapes).
 *
 * Neither stock Android path matches that: `hasOverlappingRendering` true
 * promotes `alpha < 1` to a compositing layer sized to this view's bounds,
 * which clips overflow; returning false drops the layer but distributes the
 * alpha onto each child's draw commands, so overlapping children darken each
 * other instead of compositing as one group.
 *
 * So the framework is kept out of the alpha business entirely: [onSetAlpha]
 * claims the value so no view-bounded layer or per-child distribution ever
 * happens, and [dispatchDraw] composites the group itself with
 * [Canvas.saveLayerAlpha] over the subtree's drawn bounds — the union of
 * each descendant's bounds mapped through its translation, rotation and
 * scale — rather than the layout frame. Ancestor layouts already set
 * `clipChildren = false`, so that overflow reaches the screen.
 */
internal class OpacityLayout(context: Context) : PassThroughFrameLayout(context) {
    private val mappedBounds = RectF()
    private val layerBounds = RectF()

    override fun hasOverlappingRendering(): Boolean = false

    /** Alpha is composited manually in [dispatchDraw]; the framework must not. */
    override fun onSetAlpha(alpha: Int): Boolean = true

    override fun dispatchDraw(canvas: Canvas) {
        val alpha = alpha
        if (alpha >= 1f || isEmpty()) {
            super.dispatchDraw(canvas)
            return
        }
        layerBounds.setEmpty()
        for (i in 0 until childCount) {
            accumulateDrawnBounds(getChildAt(i))
        }
        if (layerBounds.isEmpty) {
            super.dispatchDraw(canvas)
            return
        }
        val saveCount =
            canvas.saveLayerAlpha(layerBounds, (alpha * 255f).roundToInt().coerceIn(0, 255))
        try {
            super.dispatchDraw(canvas)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    /** Unions the drawn bounds of [view] and its descendants into [layerBounds]. */
    private fun accumulateDrawnBounds(view: View) {
        if (view.visibility != VISIBLE) return
        mappedBounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        view.matrix.mapRect(mappedBounds)
        mappedBounds.offset(view.left.toFloat(), view.top.toFloat())
        layerBounds.union(mappedBounds)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                accumulateDescendant(view.getChildAt(i), view)
            }
        }
    }

    /**
     * Unions [view]'s drawn bounds into [layerBounds]. [view] sits inside
     * [parent]'s coordinate space, so after mapping its local rect through its
     * own matrix and position the rect is walked up the ancestor chain —
     * each level's matrix (translation, rotation, scale about its pivot) and
     * layout position — until it lands in this layout's coordinates.
     */
    private fun accumulateDescendant(view: View, parent: ViewGroup) {
        if (view.visibility != VISIBLE) return
        mappedBounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
        view.matrix.mapRect(mappedBounds)
        mappedBounds.offset(view.left.toFloat(), view.top.toFloat())
        var ancestor: View = parent
        while (ancestor !== this) {
            ancestor.matrix.mapRect(mappedBounds)
            mappedBounds.offset(ancestor.left.toFloat(), ancestor.top.toFloat())
            val grand = ancestor.parent
            if (grand is View) ancestor = grand else break
        }
        layerBounds.union(mappedBounds)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                accumulateDescendant(view.getChildAt(i), view)
            }
        }
    }
}

private val metadataOpacityRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_opacity(node.rawPtr)
    val container = OpacityLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)

    val opacityComputed = WuiComputed.float(metadata.valuePtr, env)
    opacityComputed.observe { alpha ->
        container.alpha = alpha
    }

    container.disposeWith(opacityComputed)

    container
}

internal fun RegistryBuilder.registerWuiOpacity() {
    registerMetadata({ metadataOpacityTypeId }, metadataOpacityRenderer)
}
