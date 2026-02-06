package dev.waterui.android.components

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.MetadataIgnoreSafeAreaStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataIgnoreSafeAreaTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_ignore_safe_area_id().toTypeId()
}

private val safeAreaTypes: Int =
    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

/**
 * Renderer for Metadata<IgnoreSafeArea>.
 *
 * The Android root view applies safe-area insets by default. IgnoreSafeArea
 * selectively cancels those insets for the wrapped subtree by extending the
 * container beyond its parent via negative margins on ignored edges.
 */
private val metadataIgnoreSafeAreaRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_ignore_safe_area(node.rawPtr)

    val container = PassThroughFrameLayout(context)

    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        container.addView(child, params)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
        val insets = windowInsets.getInsets(safeAreaTypes)
        view.setPadding(0, 0, 0, 0)
        updateIgnoredMargins(view, metadata, insets)

        val forwardedInsets = Insets.of(
            if (metadata.leading) 0 else insets.left,
            if (metadata.top) 0 else insets.top,
            if (metadata.trailing) 0 else insets.right,
            if (metadata.bottom) 0 else insets.bottom
        )

        WindowInsetsCompat.Builder(windowInsets)
            .setInsets(safeAreaTypes, forwardedInsets)
            .build()
    }

    ViewCompat.requestApplyInsets(container)
    container
}

private fun updateIgnoredMargins(
    view: View,
    metadata: MetadataIgnoreSafeAreaStruct,
    insets: Insets
) {
    val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return

    val targetLeft = if (metadata.leading) -insets.left else 0
    val targetTop = if (metadata.top) -insets.top else 0
    val targetRight = if (metadata.trailing) -insets.right else 0
    val targetBottom = if (metadata.bottom) -insets.bottom else 0

    if (
        params.leftMargin == targetLeft &&
        params.topMargin == targetTop &&
        params.rightMargin == targetRight &&
        params.bottomMargin == targetBottom
    ) {
        return
    }

    params.leftMargin = targetLeft
    params.topMargin = targetTop
    params.rightMargin = targetRight
    params.bottomMargin = targetBottom
    view.layoutParams = params
}

internal fun RegistryBuilder.registerWuiIgnoreSafeArea() {
    registerMetadata({ metadataIgnoreSafeAreaTypeId }, metadataIgnoreSafeAreaRenderer)
}
