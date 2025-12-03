package dev.waterui.android.components

import android.widget.FrameLayout
import dev.waterui.android.reactive.WatcherStructFactory
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toTypeId

private val dynamicTypeId: WuiTypeId by lazy { NativeBindings.waterui_dynamic_id().toTypeId() }

private val dynamicRenderer = WuiRenderer { context, node, env, registry ->
    val dynamic = NativeBindings.waterui_force_as_dynamic(node.rawPtr)
    val container = FrameLayout(context)
    var currentPtr: Long? = null

    val watcher = WatcherStructFactory.anyView { pointer, _ ->
        container.post {
            if (currentPtr != pointer) {
                currentPtr?.let { NativeBindings.waterui_drop_anyview(it) }
                container.removeAllViews()
                currentPtr = pointer
                if (pointer != 0L) {
                    val child = inflateAnyView(context, pointer, env, registry)
                    container.addView(child)
                }
            }
        }
    }
    NativeBindings.waterui_dynamic_connect(dynamic.dynamicPtr, watcher)

    container.disposeWith {
        currentPtr?.let { NativeBindings.waterui_drop_anyview(it) }
        NativeBindings.waterui_drop_dynamic(dynamic.dynamicPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiDynamic() {
    register({ dynamicTypeId }, dynamicRenderer)
}
