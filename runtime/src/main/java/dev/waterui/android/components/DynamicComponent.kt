package dev.waterui.android.components

import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WatcherStructFactory
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView


private val dynamicTypeId: WuiTypeId by lazy { WatcherJni.dynamicId().toTypeId() }

private val dynamicRenderer = WuiRenderer { context, node, env, registry ->
    val dynamic = WatcherJni.forceAsDynamic(node.rawPtr)
    val container = PassThroughFrameLayout(context)

    val watcher = WatcherStructFactory.anyView { pointer, _ ->
        container.post {
            // Remove old views - their resources are cleaned up via disposeWith callbacks
            // when they're detached from the window
            container.removeAllViews()
            if (pointer != 0L) {
                // inflateAnyView consumes the pointer via force_as_* FFI functions,
                // so we don't need to (and must not) call waterui_drop_anyview on it.
                // The Kotlin view tree now owns the resources, cleaned up via disposeWith.
                val child = inflateAnyView(context, pointer, env, registry)
                container.addView(child)
            }
        }
    }
    WatcherJni.dynamicConnect(dynamic.dynamicPtr, watcher)

    container.disposeWith {
        WatcherJni.dropDynamic(dynamic.dynamicPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiDynamic() {
    register({ dynamicTypeId }, dynamicRenderer)
}
