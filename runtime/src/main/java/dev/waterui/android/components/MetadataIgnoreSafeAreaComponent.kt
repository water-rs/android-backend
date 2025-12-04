package dev.waterui.android.components

import android.view.View
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataIgnoreSafeAreaTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_ignore_safe_area_id().toTypeId()
}

/**
 * Renderer for Metadata<IgnoreSafeArea>.
 *
 * Allows the wrapped view to extend beyond safe area insets on specified edges.
 * On Android, this adjusts padding based on system window insets.
 */
private val metadataIgnoreSafeAreaRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_ignore_safe_area(node.rawPtr)

    val container = FrameLayout(context)

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Apply window insets listener to handle safe area
    ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
        val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

        // Calculate padding, ignoring specified edges
        val leftPadding = if (metadata.leading) 0 else systemBars.left
        val topPadding = if (metadata.top) 0 else systemBars.top
        val rightPadding = if (metadata.trailing) 0 else systemBars.right
        val bottomPadding = if (metadata.bottom) 0 else systemBars.bottom

        view.setPadding(leftPadding, topPadding, rightPadding, bottomPadding)

        // Consume the insets we've handled
        WindowInsetsCompat.Builder(windowInsets)
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                androidx.core.graphics.Insets.of(
                    if (metadata.leading) systemBars.left else 0,
                    if (metadata.top) systemBars.top else 0,
                    if (metadata.trailing) systemBars.right else 0,
                    if (metadata.bottom) systemBars.bottom else 0
                )
            )
            .build()
    }

    // Request insets to be applied
    ViewCompat.requestApplyInsets(container)

    // Cleanup
    container.disposeWith {
        if (metadata.contentPtr != 0L) {
            NativeBindings.waterui_drop_anyview(metadata.contentPtr)
        }
    }

    container
}

internal fun RegistryBuilder.registerWuiIgnoreSafeArea() {
    registerMetadata({ metadataIgnoreSafeAreaTypeId }, metadataIgnoreSafeAreaRenderer)
}
