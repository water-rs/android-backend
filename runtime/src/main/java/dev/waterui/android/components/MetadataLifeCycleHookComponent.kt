package dev.waterui.android.components

import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.LifeCycleType
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataLifeCycleHookTypeId: WuiTypeId by lazy {
    WatcherJni.metadataLifeCycleHookId().toTypeId()
}

/**
 * Renderer for Metadata<LifeCycleHook>.
 *
 * Handles lifecycle events (appear/disappear) for the wrapped view.
 * The handler is called once (FnOnce) when the lifecycle event occurs.
 */
private val metadataLifeCycleHookRenderer = WuiRenderer { context, node, env, registry ->
    val hookData = WatcherJni.forceAsMetadataLifeCycleHook(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    val envPtr = env.raw()
    var hasCalledHandler = false
    var handlerPtr: Long? = hookData.handlerPtr

    // Inflate the content
    if (hookData.contentPtr != 0L) {
        val child = inflateAnyView(context, hookData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    val lifecycleType = LifeCycleType.fromInt(hookData.lifecycleType)

    // Handle lifecycle events
    container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (!hasCalledHandler && lifecycleType == LifeCycleType.APPEAR) {
                handlerPtr?.let { ptr ->
                    hasCalledHandler = true
                    WatcherJni.callLifeCycleHook(ptr, envPtr)
                    handlerPtr = null
                }
            }
        }

        override fun onViewDetachedFromWindow(v: View) {
            if (!hasCalledHandler && lifecycleType == LifeCycleType.DISAPPEAR) {
                handlerPtr?.let { ptr ->
                    hasCalledHandler = true
                    WatcherJni.callLifeCycleHook(ptr, envPtr)
                    handlerPtr = null
                }
            }
        }
    })

    // Cleanup
    container.disposeWith {
        // Drop handler if it was never called
        handlerPtr?.let { ptr ->
            if (!hasCalledHandler) {
                WatcherJni.dropLifeCycleHook(ptr)
            }
        }
    }

    container
}

internal fun RegistryBuilder.registerWuiLifeCycleHook() {
    registerMetadata({ metadataLifeCycleHookTypeId }, metadataLifeCycleHookRenderer)
}
