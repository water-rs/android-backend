package dev.waterui.android.components

import android.view.MotionEvent
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.EventType
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val metadataOnEventTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_on_event_id().toTypeId()
}

private val metadataOnEventRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_on_event(node.rawPtr)
    val eventType = EventType.fromInt(metadata.eventType)
    val envPtr = env.raw()
    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)

    container.setOnHoverListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                if (eventType == EventType.HOVER_ENTER) {
                    NativeBindings.waterui_call_on_event(metadata.handlerPtr, envPtr)
                }
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (eventType == EventType.HOVER_MOVE) {
                    NativeBindings.waterui_call_on_hover_event(
                        metadata.handlerPtr,
                        envPtr,
                        event.x,
                        event.y
                    )
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (eventType == EventType.HOVER_EXIT) {
                    NativeBindings.waterui_call_on_event(metadata.handlerPtr, envPtr)
                }
            }
        }
        false
    }
    container.disposeWith {
        container.setOnHoverListener(null)
        NativeBindings.waterui_drop_on_event(metadata.handlerPtr)
    }
    container
}

internal fun RegistryBuilder.registerWuiOnEvent() {
    registerMetadata({ metadataOnEventTypeId }, metadataOnEventRenderer)
}
