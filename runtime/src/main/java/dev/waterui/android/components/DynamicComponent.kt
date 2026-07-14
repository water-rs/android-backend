package dev.waterui.android.components

import android.os.Looper
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeAndRemoveAllViews
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView

private val dynamicTypeId: WuiTypeId by lazy { NativeBindings.waterui_dynamic_id().toTypeId() }

private val dynamicRenderer = WuiRenderer { context, node, env, registry ->
    val dynamic = NativeBindings.waterui_force_as_dynamic(node.rawPtr)
    val container = PassThroughFrameLayout(context)

    val watcher = WatcherJni.createAnyViewWatcher { pointer, _ ->
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "Dynamic view updates must run on the Android main thread"
        }
        container.disposeAndRemoveAllViews()
        if (pointer != 0L) {
            val child = inflateAnyView(context, pointer, env, registry)
            container.addView(child)
        }
    }
    NativeBindings.waterui_dynamic_connect(dynamic.dynamicPtr, watcher)

    container.disposeWith {
        NativeBindings.waterui_drop_dynamic(dynamic.dynamicPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiDynamic() {
    register({ dynamicTypeId }, dynamicRenderer)
}
