package dev.waterui.android.components

import android.content.Context
import android.view.ViewGroup
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.inflateAnyView

/**
 * Inflates a metadata node's content into this wrapper.
 *
 * Nothing about the child's slot identity is copied onto the wrapper: the
 * wrapper answers a parent's stretch, priority and measurement questions by
 * forwarding to the content live, so an identity stamped here once could only
 * go stale.
 */
internal fun <T : PassThroughFrameLayout> T.attachMetadataContent(
    context: Context,
    contentPtr: Long,
    env: WuiEnvironment,
    registry: RenderRegistry,
    layoutParams: ViewGroup.LayoutParams? = null
): T = apply {
    val child = inflateAnyView(context, contentPtr, env, registry)
    if (layoutParams == null) {
        addView(child)
    } else {
        addView(child, layoutParams)
    }
}
