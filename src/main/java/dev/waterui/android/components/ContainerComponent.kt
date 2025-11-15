package dev.waterui.android.components

import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.waterui.android.layout.ChildDescriptor
import dev.waterui.android.layout.RustLayout
import dev.waterui.android.layout.toChildDescriptors
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.*

private val layoutContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_layout_container_id().toTypeId()
}

private val fixedContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_fixed_container_id().toTypeId()
}

private val layoutContainerRenderer = WuiRenderer { node, env ->
    val struct = remember(node) { NativeBindings.waterui_force_as_layout_container(node.rawPtr) }
    val childPointers = remember(struct.childrenPtr) {
        if (struct.childrenPtr == 0L) {
            emptyList()
        } else {
            NativeAnyViews(struct.childrenPtr).usePointer { pointer ->
                pointer.toList()
            }
        }
    }
    val descriptors = remember(childPointers) { childPointers.toChildDescriptors() }

    RustLayout(layoutPtr = struct.layoutPtr, descriptors = descriptors, environment = env) {
        childPointers.forEach { childPtr ->
            key(childPtr) {
                WuiAnyView(pointer = childPtr, environment = env)
            }
        }
    }
}

private val fixedContainerRenderer = WuiRenderer { node, env ->
    val struct = remember(node) { NativeBindings.waterui_force_as_fixed_container(node.rawPtr) }
    val childPointers = remember(struct.childPointers) {
        if (struct.childPointers.isEmpty()) emptyList() else struct.childPointers.toList()
    }
    val descriptors = remember(childPointers) { childPointers.toChildDescriptors() }

    RustLayout(layoutPtr = struct.layoutPtr, descriptors = descriptors, environment = env) {
        childPointers.forEach { childPtr ->
            key(childPtr) {
                WuiAnyView(pointer = childPtr, environment = env)
            }
        }
    }
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiContainers() {
    register({ layoutContainerTypeId }, layoutContainerRenderer)
    register({ fixedContainerTypeId }, fixedContainerRenderer)
}
