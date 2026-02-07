package dev.waterui.android.components

import android.view.View
import android.view.ViewGroup
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toColorInt

private val resolvedColorTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_resolved_color_id().toTypeId()
}

private val resolvedColorRenderer = WuiRenderer { context, node, env, registry ->
    val color = NativeBindings.waterui_force_as_resolved_color(node.rawPtr)
    View(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(color.toColorInt())
    }
}

internal fun RegistryBuilder.registerWuiResolvedColor() {
    register({ resolvedColorTypeId }, resolvedColorRenderer)
}

