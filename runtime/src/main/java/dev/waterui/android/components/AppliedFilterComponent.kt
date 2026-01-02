package dev.waterui.android.components

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.AppliedFilterStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataAppliedFilterTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_applied_filter_id().toTypeId()
}

/**
 * AppliedFilter component renderer.
 *
 * Applies GPU-based filters to captured view content using wgpu.
 * Uses SurfaceView for zero-copy GPU rendering.
 *
 * # Architecture
 *
 * - Child view is rendered to a capture surface
 * - GPU filter processes the captured content
 * - Result is displayed on output SurfaceView
 *
 * # Current Implementation
 *
 * For now, shows the child view directly. Full GPU filter rendering
 * requires JNI C++ callback implementation for async setup.
 */
@Suppress("UNUSED_PARAMETER")
private val metadataAppliedFilterRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_applied_filter(node.rawPtr)

    // For now, just show the child view until full GPU integration is complete
    // TODO: Implement GPU filter rendering with SurfaceView + wgpu
    val container = PassThroughFrameLayout(context)

    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    container.disposeWith {
        // Filter pointer is consumed during init (if implemented)
        // Currently no cleanup needed since GPU path isn't active
    }

    container
}

internal fun RegistryBuilder.registerWuiAppliedFilter() {
    registerMetadata({ metadataAppliedFilterTypeId }, metadataAppliedFilterRenderer)
}
