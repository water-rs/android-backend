package dev.waterui.android.components

import android.view.View
import dev.waterui.android.runtime.LifecycleType
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView

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
    // A lifecycle hook is behavior, not geometry: it folds onto the content
    // view — the attach listener and the disposal live on the child itself,
    // so no wrapper ViewGroup is claimed.
    val child = inflateAnyView(context, metadata.contentPtr, env, registry)

    val listener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (lifecycle == LifecycleType.APPEAR) {
                view.removeOnAttachStateChangeListener(this)
                handler.call()
            }
        }

        override fun onViewDetachedFromWindow(view: View) {
            if (lifecycle == LifecycleType.DISAPPEAR) {
                view.removeOnAttachStateChangeListener(this)
                handler.call()
            }
        }
    }
    child.addOnAttachStateChangeListener(listener)
    child.disposeWith {
        child.removeOnAttachStateChangeListener(listener)
        if (handler.isPending && lifecycle == LifecycleType.DISAPPEAR) {
            handler.call()
        } else {
            handler.drop()
        }
    }
    child
}

internal fun RegistryBuilder.registerWuiLifecycleHook() {
    registerMetadata({ metadataLifecycleTypeId }, metadataLifecycleRenderer)
}
