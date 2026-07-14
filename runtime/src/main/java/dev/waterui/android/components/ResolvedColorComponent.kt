package dev.waterui.android.components

import android.content.Context
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.toColorInt

private val resolvedColorTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_resolved_color_id().toTypeId()
}
private val colorTypeId: WuiTypeId by lazy { NativeBindings.waterui_color_id().toTypeId() }

private val colorRenderer = WuiRenderer { context, node, env, _ ->
    val colorPtr = NativeBindings.waterui_force_as_color(node.rawPtr)
    val resolvedPtr = NativeBindings.waterui_resolve_color(colorPtr, env.raw())
    NativeBindings.waterui_drop_color(colorPtr)
    val resolved = WuiComputed.colorFromComputed(resolvedPtr)
    ColorFillView(context).apply {
        resolved.observe(::setResolvedColor)
        disposeWith(resolved)
    }
}

private val resolvedColorRenderer = WuiRenderer { context, node, _, _ ->
    val resolved = NativeBindings.waterui_force_as_resolved_color(node.rawPtr)
    ColorFillView(context).apply { setResolvedColor(resolved) }
}

private class ColorFillView(context: Context) : StretchVisualView(context) {
    fun setResolvedColor(color: ResolvedColorStruct) {
        setBackgroundColor(color.toColorInt())
    }
}

internal fun RegistryBuilder.registerWuiResolvedColor() {
    register({ colorTypeId }, colorRenderer)
    register({ resolvedColorTypeId }, resolvedColorRenderer)
}
