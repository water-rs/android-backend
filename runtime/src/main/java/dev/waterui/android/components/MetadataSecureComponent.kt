package dev.waterui.android.components

import android.view.View
import android.view.WindowManager
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataSecureTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_secure_id().toTypeId()
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
    val metadata = NativeBindings.waterui_force_as_metadata_secure(node.rawPtr)

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
        override fun onViewAttachedToWindow(v: View) {
            // Set FLAG_SECURE on the window to prevent screenshots
            (context as? android.app.Activity)?.window?.addFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        override fun onViewDetachedFromWindow(v: View) {
            // Clear FLAG_SECURE when detached
            // Note: This is simplified - a production implementation would
            // track all secure views and only clear when none are visible
            (context as? android.app.Activity)?.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    })

    container
}

internal fun RegistryBuilder.registerWuiSecure() {
    registerMetadata({ metadataSecureTypeId }, metadataSecureRenderer)
}
