package dev.waterui.android.components

import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WatcherCallback
import dev.waterui.android.reactive.WuiWatcherMetadata
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRustAnimation
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataTransformTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_transform_id().toTypeId()
}

/**
 * Renderer for Metadata<Transform>.
 *
 * Applies a 2D transform (scale, rotation, translation) to the wrapped view.
 * Transforms are purely visual and do not affect layout.
 */
private val metadataTransformRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_transform(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    // Track current transform values
    var currentScaleX = 1f
    var currentScaleY = 1f
    var currentRotation = 0f
    var currentTranslateX = 0f
    var currentTranslateY = 0f

    // Read initial values
    if (metadata.scaleXPtr != 0L) {
        currentScaleX = NativeBindings.waterui_read_computed_f32(metadata.scaleXPtr)
    }
    if (metadata.scaleYPtr != 0L) {
        currentScaleY = NativeBindings.waterui_read_computed_f32(metadata.scaleYPtr)
    }
    if (metadata.rotationPtr != 0L) {
        currentRotation = NativeBindings.waterui_read_computed_f32(metadata.rotationPtr)
    }
    if (metadata.translateXPtr != 0L) {
        currentTranslateX = NativeBindings.waterui_read_computed_f32(metadata.translateXPtr)
    }
    if (metadata.translateYPtr != 0L) {
        currentTranslateY = NativeBindings.waterui_read_computed_f32(metadata.translateYPtr)
    }

    // Inflate the content
    var childView: View? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        childView = child
    }

    // Helper to apply current transform values
    fun applyTransform(animation: WuiAnimation) {
        childView?.let { view ->
            view.applyRustAnimation(animation) {
                view.scaleX = currentScaleX
                view.scaleY = currentScaleY
                view.rotation = currentRotation
                val density = context.resources.displayMetrics.density
                view.translationX = currentTranslateX * density
                view.translationY = currentTranslateY * density
            }
        }
    }

    // Apply initial transform
    applyTransform(WuiAnimation.None)

    // Store watcher guards
    val watcherGuards = mutableListOf<Long>()

    // Watch scale X
    if (metadata.scaleXPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentScaleX = value
            applyTransform(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.scaleXPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    // Watch scale Y
    if (metadata.scaleYPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentScaleY = value
            applyTransform(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.scaleYPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    // Watch rotation
    if (metadata.rotationPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentRotation = value
            applyTransform(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.rotationPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    // Watch translate X
    if (metadata.translateXPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentTranslateX = value
            applyTransform(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.translateXPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    // Watch translate Y
    if (metadata.translateYPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentTranslateY = value
            applyTransform(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.translateYPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    // Cleanup
    container.disposeWith {
        // Drop watcher guards
        watcherGuards.forEach { guard ->
            NativeBindings.waterui_drop_watcher_guard(guard)
        }

        // Drop computed pointers
        if (metadata.scaleXPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.scaleXPtr)
        if (metadata.scaleYPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.scaleYPtr)
        if (metadata.rotationPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.rotationPtr)
        if (metadata.translateXPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.translateXPtr)
        if (metadata.translateYPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.translateYPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiTransform() {
    registerMetadata({ metadataTransformTypeId }, metadataTransformRenderer)
}
