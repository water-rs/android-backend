package dev.waterui.android.components

import android.widget.HorizontalScrollView
import android.widget.ScrollView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.inflateAnyView


private val scrollTypeId: WuiTypeId by lazy { NativeBindings.waterui_scroll_view_id().toTypeId() }

private const val AXIS_HORIZONTAL = 0
private const val AXIS_VERTICAL = 1
private const val AXIS_ALL = 2

private val scrollRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_scroll(node.rawPtr)
    val content = inflateAnyView(context, struct.contentPtr, env, registry)

    // Layout decisions (including centering) are made by Rust layout engine.
    // Android only measures and places children.
    when (struct.axis) {
        AXIS_HORIZONTAL -> HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(content)
        }
        AXIS_ALL -> ScrollView(context).apply {
            val horizontal = HorizontalScrollView(context)
            horizontal.addView(content)
            addView(horizontal)
        }
        else -> ScrollView(context).apply {
            addView(content)
        }
    }
}

internal fun RegistryBuilder.registerWuiScroll() {
    register({ scrollTypeId }, scrollRenderer)
}
