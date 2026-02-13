package dev.waterui.android.components

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.ffi.WatcherJni
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

private val metadataScaleTypeId: WuiTypeId by lazy {
    WatcherJni.metadataScaleId().toTypeId()
}

/**
 * Renderer for Metadata<Scale>.
 *
 * Applies a scale transform to the wrapped view around the specified anchor point.
 * Scales are purely visual and do not affect layout.
 */
private val metadataScaleRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataScale(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    var currentScaleX = 1f
    var currentScaleY = 1f
    val anchorX = metadata.anchorX
    val anchorY = metadata.anchorY

    if (metadata.scaleXPtr != 0L) {
        currentScaleX = WatcherJni.readComputedF32(metadata.scaleXPtr)
    }
    if (metadata.scaleYPtr != 0L) {
        currentScaleY = WatcherJni.readComputedF32(metadata.scaleYPtr)
    }

    var childView: View? = null
    var layoutListener: View.OnLayoutChangeListener? = null
    var scaleXSpring: SpringAnimation? = null
    var scaleYSpring: SpringAnimation? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        childView = child

        val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            updateScalePivot(view, anchorX, anchorY)
        }
        child.addOnLayoutChangeListener(listener)
        layoutListener = listener
        updateScalePivot(child, anchorX, anchorY)
    }

    fun applyScale(animation: WuiAnimation) {
        val view = childView ?: return
        updateScalePivot(view, anchorX, anchorY)
        when (animation) {
            is WuiAnimation.Spring -> {
                view.animate().cancel()
                scaleXSpring = applySpring(
                    view,
                    DynamicAnimation.SCALE_X,
                    currentScaleX,
                    scaleXSpring,
                    springForceFrom(animation)
                )
                scaleYSpring = applySpring(
                    view,
                    DynamicAnimation.SCALE_Y,
                    currentScaleY,
                    scaleYSpring,
                    springForceFrom(animation)
                )
            }
            else -> {
                scaleXSpring?.cancel()
                scaleYSpring?.cancel()
                view.withRustAnimator(animation) {
                    scaleX(currentScaleX)
                    scaleY(currentScaleY)
                }
            }
        }
    }

    applyScale(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.scaleXPtr != 0L) {
        val watcher = WatcherJni.createFloatWatcher { value, watcherMetadata ->
            currentScaleX = value
            applyScale(watcherMetadata.animation)
        }
        val guard = WatcherJni.watchComputedF32(metadata.scaleXPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    if (metadata.scaleYPtr != 0L) {
        val watcher = WatcherJni.createFloatWatcher { value, watcherMetadata ->
            currentScaleY = value
            applyScale(watcherMetadata.animation)
        }
        val guard = WatcherJni.watchComputedF32(metadata.scaleYPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        layoutListener?.let { listener ->
            childView?.removeOnLayoutChangeListener(listener)
        }
        scaleXSpring?.cancel()
        scaleYSpring?.cancel()
        watcherGuards.forEach { guard ->
            WatcherJni.dropWatcherGuard(guard)
        }
        if (metadata.scaleXPtr != 0L) WatcherJni.dropComputedF32(metadata.scaleXPtr)
        if (metadata.scaleYPtr != 0L) WatcherJni.dropComputedF32(metadata.scaleYPtr)
    }

    container
}

private fun updateScalePivot(view: View, anchorX: Float, anchorY: Float) {
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

internal fun RegistryBuilder.registerWuiScale() {
    registerMetadata({ metadataScaleTypeId }, metadataScaleRenderer)
}
