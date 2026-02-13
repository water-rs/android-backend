package dev.waterui.android.components

import android.view.MotionEvent
import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataHittableTypeId: WuiTypeId by lazy {
    WatcherJni.metadataHittableId().toTypeId()
}

/**
 * Renderer for Metadata<Hittable>.
 *
 * Controls whether the wrapped view responds to hit testing (touch/click events).
 * When disabled, touch events pass through the view to views behind it.
 */
private val metadataHittableRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataHittable(node.rawPtr)

    var currentEnabled = true

    if (metadata.enabledPtr != 0L) {
        currentEnabled = WatcherJni.readComputedBool(metadata.enabledPtr)
    }

    // Use a custom container that intercepts touch events when disabled
    val container = object : PassThroughFrameLayout(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            // When disabled, don't intercept - let events pass through
            return if (!currentEnabled) false else super.onInterceptTouchEvent(ev)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // When disabled, don't dispatch to children - let parent handle it
            return if (!currentEnabled) false else super.dispatchTouchEvent(ev)
        }
    }

    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    val watcherGuards = mutableListOf<Long>()

    if (metadata.enabledPtr != 0L) {
        val watcher = WatcherJni.createBoolWatcher { value, _ ->
            currentEnabled = value
        }
        val guard = WatcherJni.watchComputedBool(metadata.enabledPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        watcherGuards.forEach { WatcherJni.dropWatcherGuard(it) }
        if (metadata.enabledPtr != 0L) WatcherJni.dropComputedBool(metadata.enabledPtr)
    }

    container
}

internal fun RegistryBuilder.registerWuiHittable() {
    registerMetadata({ metadataHittableTypeId }, metadataHittableRenderer)
}
