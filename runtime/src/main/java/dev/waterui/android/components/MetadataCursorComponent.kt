package dev.waterui.android.components

import android.os.Build
import android.view.MotionEvent
import android.view.PointerIcon
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.CursorStyle
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataCursorTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_cursor_id().toTypeId()
}

/**
 * Renderer for Metadata<Cursor>.
 *
 * Sets the cursor style when the pointer is over the wrapped view.
 * The cursor automatically resets when the pointer exits the view bounds.
 * Requires API 24+ (Android N) for pointer icon support.
 */
private val metadataCursorRenderer = WuiRenderer { context, node, env, registry ->
    val cursorData = NativeBindings.waterui_force_as_metadata_cursor(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var watcherGuardPtr: Long = 0
    var currentStyle = CursorStyle.ARROW

    // Inflate the content
    if (cursorData.contentPtr != 0L) {
        val child = inflateAnyView(context, cursorData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Read initial cursor style
    if (cursorData.stylePtr != 0L) {
        currentStyle = CursorStyle.fromInt(NativeBindings.waterui_read_computed_cursor_style(cursorData.stylePtr))
    }

    // Watch for reactive cursor style changes (API 24+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && cursorData.stylePtr != 0L) {
        val watcher = NativeBindings.waterui_create_cursor_style_watcher { value, _ ->
            currentStyle = CursorStyle.fromInt(value)
            // Update the pointer icon if we're currently hovering
            container.pointerIcon = getPointerIcon(context, currentStyle)
        }
        watcherGuardPtr = NativeBindings.waterui_watch_computed_cursor_style(cursorData.stylePtr, watcher)

        // Set hover listener to apply cursor
        var isHovered = false
        container.setOnHoverListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    isHovered = true
                    view.pointerIcon = getPointerIcon(context, currentStyle)
                    false // Don't consume, let child views handle
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    isHovered = false
                    view.pointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_DEFAULT)
                    false
                }
                else -> false
            }
        }
    }

    // Cleanup
    container.disposeWith {
        if (cursorData.contentPtr != 0L) {
            NativeBindings.waterui_drop_anyview(cursorData.contentPtr)
        }
        if (cursorData.stylePtr != 0L) {
            NativeBindings.waterui_drop_computed_cursor_style(cursorData.stylePtr)
        }
        if (watcherGuardPtr != 0L) {
            NativeBindings.waterui_drop_watcher_guard(watcherGuardPtr)
        }
    }

    container
}

/**
 * Maps WuiCursorStyle to Android PointerIcon type.
 */
private fun getPointerIcon(context: android.content.Context, style: CursorStyle): PointerIcon {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_DEFAULT)
    }

    val iconType = when (style) {
        CursorStyle.ARROW -> PointerIcon.TYPE_DEFAULT
        CursorStyle.POINTING_HAND -> PointerIcon.TYPE_HAND
        CursorStyle.IBEAM -> PointerIcon.TYPE_TEXT
        CursorStyle.CROSSHAIR -> PointerIcon.TYPE_CROSSHAIR
        CursorStyle.OPEN_HAND -> PointerIcon.TYPE_GRAB
        CursorStyle.CLOSED_HAND -> PointerIcon.TYPE_GRABBING
        CursorStyle.NOT_ALLOWED -> PointerIcon.TYPE_NO_DROP
        CursorStyle.RESIZE_LEFT -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
        CursorStyle.RESIZE_RIGHT -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
        CursorStyle.RESIZE_UP -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
        CursorStyle.RESIZE_DOWN -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
        CursorStyle.RESIZE_LEFT_RIGHT -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
        CursorStyle.RESIZE_UP_DOWN -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
        CursorStyle.MOVE -> PointerIcon.TYPE_ALL_SCROLL
        CursorStyle.WAIT -> PointerIcon.TYPE_WAIT
        CursorStyle.COPY -> PointerIcon.TYPE_COPY
    }
    return PointerIcon.getSystemIcon(context, iconType)
}

internal fun RegistryBuilder.registerWuiCursor() {
    registerMetadata({ metadataCursorTypeId }, metadataCursorRenderer)
}
