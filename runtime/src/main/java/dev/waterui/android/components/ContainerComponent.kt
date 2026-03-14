package dev.waterui.android.components

import android.view.View
import dev.waterui.android.layout.ChildDescriptor
import dev.waterui.android.layout.RustLayoutViewGroup
import dev.waterui.android.runtime.NativeAnyViews
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.reactive.WatcherGuard

private val layoutContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_layout_container_id().toTypeId()
}

private val fixedContainerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_fixed_container_id().toTypeId()
}

private val layoutContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_layout_container(node.rawPtr)

    val group = RustLayoutViewGroup(context, layoutPtr = struct.layoutPtr, descriptors = emptyList())
    val contentsPtr = struct.childrenPtr
    if (contentsPtr != 0L) {
        val nativeViews = NativeAnyViews(contentsPtr)
        var watcherGuard: WatcherGuard? = null

        fun syncChildren() {
            if (nativeViews.isReleased) {
                return
            }

            val inflatedChildren = nativeViews.toList().map { childPtr ->
                inflateAnyView(context, childPtr, env, registry)
            }
            val descriptors = inflatedChildren.map { child ->
                ChildDescriptor(
                    typeId = WuiTypeId(0L, 0L),
                    stretchAxis = child.getWuiStretchAxis()
                )
            }
            group.replaceChildren(inflatedChildren, descriptors)
        }

        syncChildren()
        watcherGuard = nativeViews.watch {
            group.post {
                syncChildren()
            }
        }

        group.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                watcherGuard?.close()
                watcherGuard = null
                nativeViews.close()
            }
        })
    }

    group
}

private val fixedContainerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_fixed_container(node.rawPtr)
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
    group
}

internal fun RegistryBuilder.registerWuiContainers() {
    register({ layoutContainerTypeId }, layoutContainerRenderer)
    register({ fixedContainerTypeId }, fixedContainerRenderer)
}
