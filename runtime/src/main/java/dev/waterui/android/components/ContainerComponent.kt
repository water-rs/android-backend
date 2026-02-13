package dev.waterui.android.components

import dev.waterui.android.layout.ChildDescriptor
import dev.waterui.android.layout.RustLayoutViewGroup
import dev.waterui.android.runtime.NativeAnyViews
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

import dev.waterui.android.runtime.usePointer

private val layoutContainerTypeId: WuiTypeId by lazy {
    WatcherJni.layoutContainerId().toTypeId()
}

private val fixedContainerTypeId: WuiTypeId by lazy {
    WatcherJni.fixedContainerId().toTypeId()
}

private val layoutContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsLayoutContainer(node.rawPtr)

    if (struct.childrenPtr == 0L) {
        RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = emptyList()).also { group ->
            group.disposeWith {
                if (struct.layoutPtr != 0L) {
                    WatcherJni.dropLayout(struct.layoutPtr)
                }
            }
        }
    } else {
        // IMPORTANT: All operations using child pointers must happen inside usePointer
        // to prevent use-after-free when NativeAnyViews is closed
        NativeAnyViews(struct.childrenPtr).usePointer { nativeViews ->
            val childPointers = nativeViews.toList()
            // Inflate children first - this resolves composite views to native views
            // and stores stretch axis on each inflated view
            val inflatedChildren = childPointers.map { childPtr ->
                inflateAnyView(context, childPtr, env, registry)
            }
            // Create descriptors from inflated children's stretch axes
            val descriptors = inflatedChildren.map { child ->
                ChildDescriptor(
                    typeId = WuiTypeId(0L, 0L),  // typeId not used for layout
                    stretchAxis = child.getWuiStretchAxis()
                )
            }
            val group = RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = descriptors)
            inflatedChildren.forEach { child ->
                group.addView(child)
            }
            group.disposeWith {
                if (struct.layoutPtr != 0L) {
                    WatcherJni.dropLayout(struct.layoutPtr)
                }
            }
            group
        }
    }
}

private val fixedContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsFixedContainer(node.rawPtr)
    val childPointers = struct.childPointers.toList()
    // Inflate children first - this resolves composite views to native views
    // and stores stretch axis on each inflated view
    val inflatedChildren = childPointers.map { childPtr ->
        inflateAnyView(context, childPtr, env, registry)
    }
    // Create descriptors from inflated children's stretch axes
    val descriptors = inflatedChildren.map { child ->
        ChildDescriptor(
            typeId = WuiTypeId(0L, 0L),  // typeId not used for layout
            stretchAxis = child.getWuiStretchAxis()
        )
    }
    val group = RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = descriptors)
    inflatedChildren.forEach { child ->
        group.addView(child)
    }
    group.disposeWith {
        if (struct.layoutPtr != 0L) {
            WatcherJni.dropLayout(struct.layoutPtr)
        }
    }
    group
}

internal fun RegistryBuilder.registerWuiContainers() {
    register({ layoutContainerTypeId }, layoutContainerRenderer)
    register({ fixedContainerTypeId }, fixedContainerRenderer)
}
