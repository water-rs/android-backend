package dev.waterui.android.components

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.springForceFrom
import dev.waterui.android.runtime.withRustAnimator

private val metadataOffsetTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_offset_id().toTypeId()
}

/**
 * Renderer for Metadata<Offset>.
 *
 * Applies a translation transform to the wrapped view.
 * Offsets are purely visual and do not affect layout.
 */
private val metadataOffsetRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_offset(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    var currentOffsetX = 0f
    var currentOffsetY = 0f

    if (metadata.offsetXPtr != 0L) {
        currentOffsetX = NativeBindings.waterui_read_computed_f32(metadata.offsetXPtr)
    }
    if (metadata.offsetYPtr != 0L) {
        currentOffsetY = NativeBindings.waterui_read_computed_f32(metadata.offsetYPtr)
    }

    var childView: View? = null
    var translateXSpring: SpringAnimation? = null
    var translateYSpring: SpringAnimation? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        childView = child
    }

    fun applyOffset(animation: WuiAnimation) {
        val view = childView ?: return
        val density = context.resources.displayMetrics.density
        val targetX = currentOffsetX * density
        val targetY = currentOffsetY * density
        when (animation) {
            is WuiAnimation.Spring -> {
                view.animate().cancel()
                translateXSpring = applySpring(
                    view,
                    DynamicAnimation.TRANSLATION_X,
                    targetX,
                    translateXSpring,
                    springForceFrom(animation)
                )
                translateYSpring = applySpring(
                    view,
                    DynamicAnimation.TRANSLATION_Y,
                    targetY,
                    translateYSpring,
                    springForceFrom(animation)
                )
            }
            else -> {
                translateXSpring?.cancel()
                translateYSpring?.cancel()
                view.withRustAnimator(animation) {
                    translationX(targetX)
                    translationY(targetY)
                }
            }
        }
    }

    applyOffset(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.offsetXPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentOffsetX = value
            applyOffset(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.offsetXPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    if (metadata.offsetYPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentOffsetY = value
            applyOffset(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.offsetYPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        translateXSpring?.cancel()
        translateYSpring?.cancel()
        watcherGuards.forEach { guard ->
            NativeBindings.waterui_drop_watcher_guard(guard)
        }
        if (metadata.offsetXPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.offsetXPtr)
        if (metadata.offsetYPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.offsetYPtr)
    }

    container
}

private fun applySpring(
    view: View,
    property: DynamicAnimation.ViewProperty,
    target: Float,
    existing: SpringAnimation?,
    springForce: SpringForce
): SpringAnimation {
    val animation = existing ?: SpringAnimation(view, property)
    springForce.setFinalPosition(target)
    animation.spring = springForce
    animation.start()
    return animation
}

internal fun RegistryBuilder.registerWuiOffset() {
    registerMetadata({ metadataOffsetTypeId }, metadataOffsetRenderer)
}
