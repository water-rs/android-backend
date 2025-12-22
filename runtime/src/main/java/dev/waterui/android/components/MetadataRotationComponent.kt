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

private val metadataRotationTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_rotation_id().toTypeId()
}

/**
 * Renderer for Metadata<Rotation>.
 *
 * Applies a rotation transform to the wrapped view around the specified anchor point.
 * Rotations are purely visual and do not affect layout.
 */
private val metadataRotationRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_rotation(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    var currentRotation = 0f
    val anchorX = metadata.anchorX
    val anchorY = metadata.anchorY

    if (metadata.anglePtr != 0L) {
        currentRotation = NativeBindings.waterui_read_computed_f32(metadata.anglePtr)
    }

    var childView: View? = null
    var layoutListener: View.OnLayoutChangeListener? = null
    var rotationSpring: SpringAnimation? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        childView = child

        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updateRotationPivot(view, anchorX, anchorY)
        }
        child.addOnLayoutChangeListener(listener)
        layoutListener = listener
        updateRotationPivot(child, anchorX, anchorY)
    }

    fun applyRotation(animation: WuiAnimation) {
        val view = childView ?: return
        updateRotationPivot(view, anchorX, anchorY)
        when (animation) {
            is WuiAnimation.Spring -> {
                view.animate().cancel()
                rotationSpring = applySpring(
                    view,
                    DynamicAnimation.ROTATION,
                    currentRotation,
                    rotationSpring,
                    springForceFrom(animation)
                )
            }
            else -> {
                rotationSpring?.cancel()
                view.withRustAnimator(animation) {
                    rotation(currentRotation)
                }
            }
        }
    }

    applyRotation(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.anglePtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentRotation = value
            applyRotation(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.anglePtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        layoutListener?.let { listener ->
            childView?.removeOnLayoutChangeListener(listener)
        }
        rotationSpring?.cancel()
        watcherGuards.forEach { guard ->
            NativeBindings.waterui_drop_watcher_guard(guard)
        }
        if (metadata.anglePtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.anglePtr)
    }

    container
}

private fun updateRotationPivot(view: View, anchorX: Float, anchorY: Float) {
    val width = view.width.toFloat()
    val height = view.height.toFloat()
    if (width <= 0f || height <= 0f) return
    view.pivotX = width * anchorX
    view.pivotY = height * anchorY
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

internal fun RegistryBuilder.registerWuiRotation() {
    registerMetadata({ metadataRotationTypeId }, metadataRotationRenderer)
}
