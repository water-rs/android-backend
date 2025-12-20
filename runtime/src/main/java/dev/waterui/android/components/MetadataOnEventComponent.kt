package dev.waterui.android.components

import android.os.Build
import android.view.MotionEvent
import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.EventType
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataOnEventTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_on_event_id().toTypeId()
}

/**
 * Renderer for Metadata<OnEvent>.
 *
 * Handles interaction events (hover enter/exit) for the wrapped view.
 * The handler can be called multiple times (Fn, repeatable).
 */
private val metadataOnEventRenderer = WuiRenderer { context, node, env, registry ->
    val onEventData = NativeBindings.waterui_force_as_metadata_on_event(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    val envPtr = env.raw()
    val handlerPtr = onEventData.handlerPtr

    // Inflate the content
    if (onEventData.contentPtr != 0L) {
        val child = inflateAnyView(context, onEventData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    val eventType = EventType.fromInt(onEventData.eventType)

    // Handle hover events (API 24+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        var isHovered = false
        container.setOnHoverListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER -> {
                    if (!isHovered && eventType == EventType.HOVER_ENTER) {
                        isHovered = true
                        NativeBindings.waterui_call_on_event(handlerPtr, envPtr)
                    } else {
                        isHovered = true
                    }
                    true
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    if (isHovered && eventType == EventType.HOVER_EXIT) {
                        isHovered = false
                        NativeBindings.waterui_call_on_event(handlerPtr, envPtr)
                    } else {
                        isHovered = false
                    }
                    true
                }
                else -> false
            }
        }
    }

    // Cleanup
    container.disposeWith {
        if (onEventData.contentPtr != 0L) {
            NativeBindings.waterui_drop_anyview(onEventData.contentPtr)
        }
        // Drop the repeatable handler
        NativeBindings.waterui_drop_on_event(handlerPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiOnEvent() {
    registerMetadata({ metadataOnEventTypeId }, metadataOnEventRenderer)
}
