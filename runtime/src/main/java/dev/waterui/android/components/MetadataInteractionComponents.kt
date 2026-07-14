package dev.waterui.android.components

import android.content.Context
import android.view.MotionEvent
import android.view.PointerIcon
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val metadataCursorTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_cursor_id().toTypeId()
}
private val metadataHittableTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_hittable_id().toTypeId()
}

private class HittableLayout(context: Context) : PassThroughFrameLayout(context) {
    var hitTestingEnabled = true

    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        hitTestingEnabled && super.dispatchTouchEvent(event)
}

private fun pointerIconType(style: Int): Int = when (style) {
    0 -> PointerIcon.TYPE_ARROW
    1 -> PointerIcon.TYPE_HAND
    2 -> PointerIcon.TYPE_TEXT
    3 -> PointerIcon.TYPE_CROSSHAIR
    4 -> PointerIcon.TYPE_GRAB
    5 -> PointerIcon.TYPE_GRABBING
    6 -> PointerIcon.TYPE_NO_DROP
    7, 8, 11 -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
    9, 10, 12 -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
    13 -> PointerIcon.TYPE_ALL_SCROLL
    14 -> PointerIcon.TYPE_WAIT
    15 -> PointerIcon.TYPE_COPY
    else -> error("unknown WaterUI cursor style: $style")
}

private val metadataCursorRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_cursor(node.rawPtr)
    PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            val style = WuiComputed.cursorStyle(metadata.stylePtr)
            style.observe { value ->
                pointerIcon = PointerIcon.getSystemIcon(context, pointerIconType(value))
            }
            disposeWith(style)
        }
}

private val metadataHittableRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_hittable(node.rawPtr)
    HittableLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            val enabled = WuiComputed.bool(metadata.enabledPtr)
            enabled.observe { value -> hitTestingEnabled = value }
            disposeWith(enabled)
        }
}

internal fun RegistryBuilder.registerWuiInteractionMetadata() {
    registerMetadata({ metadataCursorTypeId }, metadataCursorRenderer)
    registerMetadata({ metadataHittableTypeId }, metadataHittableRenderer)
}
