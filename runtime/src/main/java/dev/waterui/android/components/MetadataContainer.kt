package dev.waterui.android.components

import android.content.Context
import android.view.ViewGroup
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

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
    setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
}
