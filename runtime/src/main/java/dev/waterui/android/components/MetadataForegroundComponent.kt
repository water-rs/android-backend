package dev.waterui.android.components

import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataForegroundTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_foreground_id().toTypeId()
}

/**
 * Renderer for Metadata<ForegroundColor>.
 *
 * Sets the foreground/tint color for the wrapped view and its children.
 * This affects drawable tinting and can be used for icons, etc.
 */
private val metadataForegroundRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_foreground(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var colorComputed: WuiComputed<ResolvedColorStruct>? = null

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Apply foreground color
    if (metadata.colorPtr != 0L) {
        // Read the Color from Computed<Color>, then resolve it
        val colorPtr = NativeBindings.waterui_read_computed_color(metadata.colorPtr)
        if (colorPtr != 0L) {
            colorComputed = WuiComputed.resolvedColor(colorPtr, env)

            // Apply initial color and watch for changes
            colorComputed?.observe { newColor ->
                applyForegroundColor(container, newColor)
            }

            NativeBindings.waterui_drop_color(colorPtr)
        }
    }

    // Cleanup
    container.disposeWith {
        colorComputed?.close()
    }

    container
}

private fun applyForegroundColor(container: PassThroughFrameLayout, color: ResolvedColorStruct) {
    val argb = Color.argb(
        (color.opacity * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )

    // Apply as foreground color filter if there's a foreground drawable
    container.foreground?.colorFilter = PorterDuffColorFilter(argb, PorterDuff.Mode.SRC_IN)
}

internal fun RegistryBuilder.registerWuiForeground() {
    registerMetadata({ metadataForegroundTypeId }, metadataForegroundRenderer)
}
