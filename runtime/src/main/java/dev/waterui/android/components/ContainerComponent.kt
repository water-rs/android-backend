package dev.waterui.android.components

import dev.waterui.android.layout.RustLayoutViewGroup
import dev.waterui.android.layout.toChildDescriptors
import dev.waterui.android.runtime.NativeAnyViews
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId
import dev.waterui.android.runtime.usePointer

private val layoutContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_layout_container_id().toTypeId()
}

private val fixedContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_fixed_container_id().toTypeId()
}

private val layoutContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_layout_container(node.rawPtr)
    
    if (struct.childrenPtr == 0L) {
        RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = emptyList())
    } else {
        // IMPORTANT: All operations using child pointers must happen inside usePointer
        // to prevent use-after-free when NativeAnyViews is closed
        NativeAnyViews(struct.childrenPtr).usePointer { nativeViews ->
            val childPointers = nativeViews.toList()
            val descriptors = childPointers.toChildDescriptors()
            val group = RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = descriptors)
            childPointers.forEach { childPtr ->
                val child = inflateAnyView(context, childPtr, env, registry)
                group.addView(child)
            }
            group
        }
    }
}

private val fixedContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_fixed_container(node.rawPtr)
    val childPointers = struct.childPointers.toList()
    val descriptors = childPointers.toChildDescriptors()
    val group = RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = descriptors)
    childPointers.forEach { childPtr ->
        val child = inflateAnyView(context, childPtr, env, registry)
        group.addView(child)
    }
    group
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiContainers() {
    register({ layoutContainerTypeId }, layoutContainerRenderer)
    register({ fixedContainerTypeId }, fixedContainerRenderer)
}
