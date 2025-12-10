package dev.waterui.android.components

import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataFocusedTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_focused_id().toTypeId()
}

/**
 * Renderer for Metadata<Focused>.
 *
 * Tracks and manages focus state for the wrapped view.
 * The binding reflects whether any focusable child has focus.
 */
private val metadataFocusedRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_focused(node.rawPtr)

    val container = FrameLayout(context)

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Create binding wrapper
    val focusBinding = WuiBinding.bool(metadata.bindingPtr, env)

    // Listen for focus changes from the binding
    focusBinding.observe { shouldFocus ->
        if (shouldFocus) {
            // Find the first focusable child and request focus
            findFirstFocusable(container)?.requestFocus()
        } else {
            // Clear focus
            container.clearFocus()
        }
    }

    // Track focus changes in the view hierarchy
    val focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val hasFocus = newFocus != null && isDescendantOf(newFocus, container)
        if (focusBinding.current() != hasFocus) {
            focusBinding.set(hasFocus)
        }
    }

    container.viewTreeObserver.addOnGlobalFocusChangeListener(focusChangeListener)

    // Cleanup
    container.disposeWith {
        container.viewTreeObserver.removeOnGlobalFocusChangeListener(focusChangeListener)
        focusBinding.close()
    }

    container
}

/**
 * Recursively finds the first focusable view in the hierarchy.
 */
private fun findFirstFocusable(view: View): View? {
    if (view.isFocusable) {
        return view
    }
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            val focusable = findFirstFocusable(view.getChildAt(i))
            if (focusable != null) {
                return focusable
            }
        }
    }
    return null
}

/**
 * Checks if a view is a descendant of another view.
 */
private fun isDescendantOf(view: View, ancestor: View): Boolean {
    if (view === ancestor) return true
    var parent = view.parent
    while (parent != null) {
        if (parent === ancestor) return true
        parent = parent.parent
    }
    return false
}

internal fun RegistryBuilder.registerWuiFocused() {
    registerMetadata({ metadataFocusedTypeId }, metadataFocusedRenderer)
}
