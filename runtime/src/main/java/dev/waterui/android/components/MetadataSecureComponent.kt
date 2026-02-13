package dev.waterui.android.components

import android.view.View
import android.view.Window
import android.view.WindowManager
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.findActivity

private val metadataSecureTypeId: WuiTypeId by lazy {
    WatcherJni.metadataSecureId().toTypeId()
}

/**
 * Renderer for Metadata<Secure>.
 *
 * Marks the wrapped content as secure to prevent screenshots and screen recording.
 * On Android, this uses FLAG_SECURE which is a window-level flag.
 *
 * Note: FLAG_SECURE affects the entire window. When secure content is visible,
 * the window should have FLAG_SECURE set; when removed, it should be cleared
 * (unless other secure views are still visible).
 */
private val metadataSecureRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataSecure(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        // Metadata is transparent - propagate child's stretch axis to container
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Set FLAG_SECURE when attached to window
    container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        private var isApplied = false

        override fun onViewAttachedToWindow(v: View) {
            if (isApplied) return
            context.findActivity()?.let { activity ->
                incrementSecureFlag(activity.window)
                isApplied = true
            }
        }

        override fun onViewDetachedFromWindow(v: View) {
            if (!isApplied) return
            context.findActivity()?.let { activity ->
                decrementSecureFlag(activity.window)
            }
            isApplied = false
        }
    })

    container
}

internal fun RegistryBuilder.registerWuiSecure() {
    registerMetadata({ metadataSecureTypeId }, metadataSecureRenderer)
}

private const val TAG_SECURE_REFCOUNT = 0x57554902 // "WUI\x02" as int

/**
 * FLAG_SECURE is window-scoped; multiple Secure views can coexist.
 *
 * Avoid global state by storing a refcount on the Window's decor view tag.
 * This runs on main thread only (view attach/detach), so no synchronization is needed.
 */
private fun incrementSecureFlag(window: Window?) {
    val w = window ?: return
    val decor = w.decorView ?: return
    val current = (decor.getTag(TAG_SECURE_REFCOUNT) as? Int) ?: 0
    val next = current + 1
    decor.setTag(TAG_SECURE_REFCOUNT, next)
    if (current == 0) {
        w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

private fun decrementSecureFlag(window: Window?) {
    val w = window ?: return
    val decor = w.decorView ?: return
    val current = (decor.getTag(TAG_SECURE_REFCOUNT) as? Int) ?: 0
    val next = current - 1
    if (next <= 0) {
        decor.setTag(TAG_SECURE_REFCOUNT, 0)
        w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        decor.setTag(TAG_SECURE_REFCOUNT, next)
    }
}
