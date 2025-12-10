package dev.waterui.android.components

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.FrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.BackgroundType
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataBackgroundTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_background_id().toTypeId()
}

/**
 * Renderer for Metadata<Background>.
 *
 * Applies a background color or image to the wrapped view.
 * Supports reactive color updates.
 */
private val metadataBackgroundRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_background(node.rawPtr)

    val container = FrameLayout(context)
    var colorComputed: WuiComputed<ResolvedColorStruct>? = null

    // Inflate the content
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Apply background based on type
    when (BackgroundType.fromInt(metadata.backgroundType)) {
        BackgroundType.COLOR -> {
            if (metadata.colorPtr != 0L) {
                // Read the Color from Computed<Color>, then resolve it
                val colorPtr = NativeBindings.waterui_read_computed_color(metadata.colorPtr)
                if (colorPtr != 0L) {
                    colorComputed = WuiComputed.resolvedColor(colorPtr, env)

                    // Apply initial color and watch for changes
                    colorComputed?.observe { newColor ->
                        applyBackgroundColor(container, newColor)
                    }

                    NativeBindings.waterui_drop_color(colorPtr)
                }
            }
        }

        BackgroundType.IMAGE -> {
            // TODO: Implement image background support
            // Would need to load image from path/URL
        }
    }

    // Cleanup
    container.disposeWith {
        colorComputed?.close()
    }

    container
}

private fun applyBackgroundColor(container: FrameLayout, color: ResolvedColorStruct) {
    val argb = Color.argb(
        (color.opacity * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    container.background = ColorDrawable(argb)
}

internal fun RegistryBuilder.registerWuiBackground() {
    registerMetadata({ metadataBackgroundTypeId }, metadataBackgroundRenderer)
}
