package dev.waterui.android.components

import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.LifecycleType
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val metadataLifecycleTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_lifecycle_hook_id().toTypeId()
}

private class LifecycleHandler(
    handlerPtr: Long,
    private val envPtr: Long
) {
    private var handlerPtr: Long? = handlerPtr

    fun call() {
        val owned = checkNotNull(handlerPtr) { "lifecycle handler was already consumed" }
        handlerPtr = null
        NativeBindings.waterui_call_lifecycle_hook(owned, envPtr)
    }

    fun drop() {
        val owned = handlerPtr ?: return
        handlerPtr = null
        NativeBindings.waterui_drop_lifecycle_hook(owned)
    }

    val isPending: Boolean get() = handlerPtr != null
}

private val metadataLifecycleRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_lifecycle_hook(node.rawPtr)
    val lifecycle = LifecycleType.fromInt(metadata.lifecycleType)
    val handler = LifecycleHandler(metadata.handlerPtr, env.raw())
    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)

    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (lifecycle == LifecycleType.APPEAR) {
                container.removeOnAttachStateChangeListener(this)
                handler.call()
            }
        }

        override fun onViewDetachedFromWindow(view: View) {
            if (lifecycle == LifecycleType.DISAPPEAR) {
                container.removeOnAttachStateChangeListener(this)
                handler.call()
            }
        }
    }
    container.addOnAttachStateChangeListener(listener)
    container.disposeWith {
        container.removeOnAttachStateChangeListener(listener)
        if (handler.isPending && lifecycle == LifecycleType.DISAPPEAR) {
            handler.call()
        } else {
            handler.drop()
        }
    }
    container
}

internal fun RegistryBuilder.registerWuiLifecycleHook() {
    registerMetadata({ metadataLifecycleTypeId }, metadataLifecycleRenderer)
}
