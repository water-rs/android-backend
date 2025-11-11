package dev.waterui.android.components

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiAnyView
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId
import dev.waterui.android.reactive.WatcherStructFactory

private val dynamicTypeId: WuiTypeId by lazy { NativeBindings.waterui_dynamic_id().toTypeId() }

private val dynamicRenderer = WuiRenderer { node, env ->
    val dynamic = remember(node) { NativeBindings.waterui_force_as_dynamic(node.rawPtr) }
    val currentEnv = rememberUpdatedState(env)
    val childState = remember { mutableStateOf<Long?>(null) }

    DisposableEffect(dynamic) {
        val watcher = WatcherStructFactory.anyView { pointer, _ ->
            val previous = childState.value
            childState.value = pointer
            if (previous != null && previous != pointer) {
                NativeBindings.waterui_anyview_drop(previous)
            }
        }
        NativeBindings.waterui_dynamic_connect(dynamic.dynamicPtr, watcher)
        onDispose {
            childState.value?.let { NativeBindings.waterui_anyview_drop(it) }
            NativeBindings.waterui_drop_dynamic(dynamic.dynamicPtr)
        }
    }

    val childPtr = childState.value
    if (childPtr != null) {
        WuiAnyView(pointer = childPtr, environment = currentEnv.value)
    }
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiDynamic() {
    register({ dynamicTypeId }, dynamicRenderer)
}
