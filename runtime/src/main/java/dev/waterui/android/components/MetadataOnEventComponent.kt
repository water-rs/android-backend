package dev.waterui.android.components

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
 * Handles lifecycle events (appear/disappear) for the wrapped view.
 * The handler is called once when the specified event occurs.
 */
private val metadataOnEventRenderer = WuiRenderer { context, node, env, registry ->
    val onEventData = NativeBindings.waterui_force_as_metadata_on_event(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    val envPtr = env.raw()
    var hasCalledHandler = false
    var handlerPtr: Long? = onEventData.handlerPtr

    // Inflate the content
    if (onEventData.contentPtr != 0L) {
        val child = inflateAnyView(context, onEventData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    val eventType = EventType.fromInt(onEventData.eventType)

    // Handle lifecycle events
    container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (!hasCalledHandler && eventType == EventType.APPEAR) {
                handlerPtr?.let { ptr ->
                    hasCalledHandler = true
                    NativeBindings.waterui_call_on_event(ptr, envPtr)
                    handlerPtr = null
                }
            }
        }

        override fun onViewDetachedFromWindow(v: View) {
            if (!hasCalledHandler && eventType == EventType.DISAPPEAR) {
                handlerPtr?.let { ptr ->
                    hasCalledHandler = true
                    NativeBindings.waterui_call_on_event(ptr, envPtr)
                    handlerPtr = null
                }
            }
        }
    })

    // Cleanup
    container.disposeWith {
        if (onEventData.contentPtr != 0L) {
            NativeBindings.waterui_drop_anyview(onEventData.contentPtr)
        }
        // Drop handler if it was never called
        handlerPtr?.let { ptr ->
            if (!hasCalledHandler) {
                NativeBindings.waterui_drop_on_event(ptr)
            }
        }
    }

    container
}

internal fun RegistryBuilder.registerWuiOnEvent() {
    registerMetadata({ metadataOnEventTypeId }, metadataOnEventRenderer)
}
