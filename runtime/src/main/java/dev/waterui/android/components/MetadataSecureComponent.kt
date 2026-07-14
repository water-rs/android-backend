package dev.waterui.android.components

import android.view.View
import android.view.WindowManager
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.requireActivity

private val metadataSecureTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_secure_id().toTypeId()
}

private val metadataSecureRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_secure(node.rawPtr)
    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
    container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            val window = context.requireActivity().window
            val decor = window.decorView
            val count = (decor.getTag(R.id.wui_secure_view_count) as? Int ?: 0) + 1
            decor.setTag(R.id.wui_secure_view_count, count)
            if (count == 1) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        override fun onViewDetachedFromWindow(v: View) {
            val window = context.requireActivity().window
            val decor = window.decorView
            val count = requireNotNull(decor.getTag(R.id.wui_secure_view_count) as? Int) {
                "secure view detached without an attachment count"
            }
            check(count > 0) { "secure view attachment count underflow" }
            val remaining = count - 1
            decor.setTag(R.id.wui_secure_view_count, remaining)
            if (remaining == 0) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    })

    container
}

internal fun RegistryBuilder.registerWuiSecure() {
    registerMetadata({ metadataSecureTypeId }, metadataSecureRenderer)
}
