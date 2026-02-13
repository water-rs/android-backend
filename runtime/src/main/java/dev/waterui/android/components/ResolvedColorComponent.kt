package dev.waterui.android.components

import android.view.View
import android.view.ViewGroup
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toColorInt

private val resolvedColorTypeId: WuiTypeId by lazy {
    WatcherJni.resolvedColorId().toTypeId()
}

private val resolvedColorRenderer = WuiRenderer { context, node, env, registry ->
    val color = WatcherJni.forceAsResolvedColor(node.rawPtr)
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

