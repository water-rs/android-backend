package dev.waterui.android.components

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.BackgroundType
import dev.waterui.android.runtime.MaterialStyle
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
 * Applies a background color, material (blur), or image to the wrapped view.
 * Background fills the entire bounds behind the content.
 *
 * For rounded cards, apply `.background()` first, then `.clip_shape()`.
 */
private val metadataBackgroundRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_background(node.rawPtr)

    val container = PassThroughFrameLayout(context)
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

        BackgroundType.MATERIAL -> {
            val materialStyle = MaterialStyle.fromInt(metadata.materialStyle)
            applyMaterialBackground(container, materialStyle)
        }
    }

    // Cleanup
    container.disposeWith {
        colorComputed?.close()
    }

    container
}

private fun applyBackgroundColor(container: PassThroughFrameLayout, color: ResolvedColorStruct) {
    val argb = Color.argb(
        (color.opacity * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    container.background = ColorDrawable(argb)
}

/**
 * Applies a material blur effect to the container.
 *
 * On Android API 31+, uses RenderEffect.createBlurEffect().
 * On older APIs, falls back to a semi-transparent background.
 */
private fun applyMaterialBackground(container: PassThroughFrameLayout, material: MaterialStyle) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // API 31+ supports RenderEffect blur
        val radiusPx = material.blurRadius() * container.resources.displayMetrics.density
        val blurEffect = RenderEffect.createBlurEffect(
            radiusPx,
            radiusPx,
            Shader.TileMode.CLAMP
        )
        container.setRenderEffect(blurEffect)

        // Add a semi-transparent overlay to simulate the frosted effect
        val alpha = when (material) {
            MaterialStyle.ULTRA_THIN -> 0.1f
            MaterialStyle.THIN -> 0.2f
            MaterialStyle.REGULAR -> 0.3f
            MaterialStyle.THICK -> 0.5f
            MaterialStyle.ULTRA_THICK -> 0.7f
        }
        container.background = ColorDrawable(Color.argb((alpha * 255).toInt(), 255, 255, 255))
    } else {
        // Fallback for older APIs: just use a semi-transparent background
        val alpha = when (material) {
            MaterialStyle.ULTRA_THIN -> 0.6f
            MaterialStyle.THIN -> 0.7f
            MaterialStyle.REGULAR -> 0.8f
            MaterialStyle.THICK -> 0.85f
            MaterialStyle.ULTRA_THICK -> 0.9f
        }
        container.background = ColorDrawable(Color.argb((alpha * 255).toInt(), 240, 240, 240))
    }
}

internal fun RegistryBuilder.registerWuiBackground() {
    registerMetadata({ metadataBackgroundTypeId }, metadataBackgroundRenderer)
}
