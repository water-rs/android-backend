package dev.waterui.android.components

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.AnimatedFloat
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.withRustAnimator

// ========== Blur Filter ==========

private val metadataBlurTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_blur_id().toTypeId()
}

private val metadataBlurRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_blur(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var currentRadius = 0f

    if (metadata.radiusPtr != 0L) {
        currentRadius = NativeBindings.waterui_read_computed_f32(metadata.radiusPtr)
    }

    var blurAnimator: AnimatedFloat? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        blurAnimator = AnimatedFloat(currentRadius) { radius ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val clamped = radius.coerceAtLeast(0f)
                if (clamped > 0f) {
                    val effect = RenderEffect.createBlurEffect(
                        clamped, clamped,
                        Shader.TileMode.CLAMP
                    )
                    child.setRenderEffect(effect)
                } else {
                    child.setRenderEffect(null)
                }
            }
        }
    }

    fun applyBlur(animation: WuiAnimation) {
        blurAnimator?.apply(currentRadius, animation)
    }

    applyBlur(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.radiusPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentRadius = value
            applyBlur(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.radiusPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        watcherGuards.forEach { NativeBindings.waterui_drop_watcher_guard(it) }
        blurAnimator?.cancel()
        if (metadata.radiusPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.radiusPtr)
    }

    container
}

// ========== Opacity Filter ==========

private val metadataOpacityTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_opacity_id().toTypeId()
}

private val metadataOpacityRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_opacity(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var currentOpacity = 1f

    if (metadata.valuePtr != 0L) {
        currentOpacity = NativeBindings.waterui_read_computed_f32(metadata.valuePtr)
    }

    var childView: View? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        childView = child
    }

    fun applyOpacity(animation: WuiAnimation) {
        childView?.let { view ->
            view.withRustAnimator(animation) {
                alpha(currentOpacity)
            }
        }
    }

    applyOpacity(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.valuePtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentOpacity = value
            applyOpacity(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.valuePtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        watcherGuards.forEach { NativeBindings.waterui_drop_watcher_guard(it) }
        if (metadata.valuePtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.valuePtr)
    }

    container
}

// ========== Brightness Filter ==========

private val metadataBrightnessTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_brightness_id().toTypeId()
}

private val metadataBrightnessRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_brightness(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var currentBrightness = 0f

    if (metadata.amountPtr != 0L) {
        currentBrightness = NativeBindings.waterui_read_computed_f32(metadata.amountPtr)
    }

    var brightnessAnimator: AnimatedFloat? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        brightnessAnimator = AnimatedFloat(currentBrightness) { amount ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val brightnessValue = amount * 255f
                val colorMatrix = ColorMatrix(floatArrayOf(
                    1f, 0f, 0f, 0f, brightnessValue,
                    0f, 1f, 0f, 0f, brightnessValue,
                    0f, 0f, 1f, 0f, brightnessValue,
                    0f, 0f, 0f, 1f, 0f
                ))
                val effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorMatrix))
                child.setRenderEffect(effect)
            }
        }
    }

    fun applyBrightness(animation: WuiAnimation) {
        brightnessAnimator?.apply(currentBrightness, animation)
    }

    applyBrightness(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.amountPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentBrightness = value
            applyBrightness(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.amountPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        watcherGuards.forEach { NativeBindings.waterui_drop_watcher_guard(it) }
        brightnessAnimator?.cancel()
        if (metadata.amountPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.amountPtr)
    }

    container
}

// ========== Saturation Filter ==========

private val metadataSaturationTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_saturation_id().toTypeId()
}

private val metadataSaturationRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_saturation(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var currentSaturation = 1f

    if (metadata.amountPtr != 0L) {
        currentSaturation = NativeBindings.waterui_read_computed_f32(metadata.amountPtr)
    }

    var saturationAnimator: AnimatedFloat? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        saturationAnimator = AnimatedFloat(currentSaturation) { amount ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(amount)
                val effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorMatrix))
                child.setRenderEffect(effect)
            }
        }
    }

    fun applySaturation(animation: WuiAnimation) {
        saturationAnimator?.apply(currentSaturation, animation)
    }

    applySaturation(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.amountPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentSaturation = value
            applySaturation(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.amountPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        watcherGuards.forEach { NativeBindings.waterui_drop_watcher_guard(it) }
        saturationAnimator?.cancel()
        if (metadata.amountPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.amountPtr)
    }

    container
}

// ========== Contrast Filter ==========

private val metadataContrastTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_contrast_id().toTypeId()
}

private val metadataContrastRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_contrast(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var currentContrast = 1f

    if (metadata.amountPtr != 0L) {
        currentContrast = NativeBindings.waterui_read_computed_f32(metadata.amountPtr)
    }

    var contrastAnimator: AnimatedFloat? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        contrastAnimator = AnimatedFloat(currentContrast) { amount ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val scale = amount
                val translate = (1f - scale) * 0.5f * 255f
                val colorMatrix = ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                val effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorMatrix))
                child.setRenderEffect(effect)
            }
        }
    }

    fun applyContrast(animation: WuiAnimation) {
        contrastAnimator?.apply(currentContrast, animation)
    }

    applyContrast(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.amountPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentContrast = value
            applyContrast(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.amountPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        watcherGuards.forEach { NativeBindings.waterui_drop_watcher_guard(it) }
        contrastAnimator?.cancel()
        if (metadata.amountPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.amountPtr)
    }

    container
}

// ========== Hue Rotation Filter ==========

private val metadataHueRotationTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_hue_rotation_id().toTypeId()
}

private val metadataHueRotationRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_hue_rotation(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var currentAngle = 0f

    if (metadata.anglePtr != 0L) {
        currentAngle = NativeBindings.waterui_read_computed_f32(metadata.anglePtr)
    }

    var hueAnimator: AnimatedFloat? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        hueAnimator = AnimatedFloat(currentAngle) { angle ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val colorMatrix = ColorMatrix()
                colorMatrix.setRotate(0, angle)
                colorMatrix.setRotate(1, angle)
                colorMatrix.setRotate(2, angle)
                val effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorMatrix))
                child.setRenderEffect(effect)
            }
        }
    }

    fun applyHueRotation(animation: WuiAnimation) {
        hueAnimator?.apply(currentAngle, animation)
    }

    applyHueRotation(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.anglePtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentAngle = value
            applyHueRotation(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.anglePtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        watcherGuards.forEach { NativeBindings.waterui_drop_watcher_guard(it) }
        hueAnimator?.cancel()
        if (metadata.anglePtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.anglePtr)
    }

    container
}

// ========== Grayscale Filter ==========

private val metadataGrayscaleTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_grayscale_id().toTypeId()
}

private val metadataGrayscaleRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_grayscale(node.rawPtr)

    val container = PassThroughFrameLayout(context)
    var currentIntensity = 0f

    if (metadata.intensityPtr != 0L) {
        currentIntensity = NativeBindings.waterui_read_computed_f32(metadata.intensityPtr)
    }

    var grayscaleAnimator: AnimatedFloat? = null
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        grayscaleAnimator = AnimatedFloat(currentIntensity) { intensity ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val clamped = intensity.coerceIn(0f, 1f)
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(1f - clamped)
                val effect = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorMatrix))
                child.setRenderEffect(effect)
            }
        }
    }

    fun applyGrayscale(animation: WuiAnimation) {
        grayscaleAnimator?.apply(currentIntensity, animation)
    }

    applyGrayscale(WuiAnimation.None)

    val watcherGuards = mutableListOf<Long>()

    if (metadata.intensityPtr != 0L) {
        val watcher = NativeBindings.waterui_create_float_watcher { value, watcherMetadata ->
            currentIntensity = value
            applyGrayscale(watcherMetadata.animation)
        }
        val guard = NativeBindings.waterui_watch_computed_f32(metadata.intensityPtr, watcher)
        if (guard != 0L) watcherGuards.add(guard)
    }

    container.disposeWith {
        watcherGuards.forEach { NativeBindings.waterui_drop_watcher_guard(it) }
        grayscaleAnimator?.cancel()
        if (metadata.intensityPtr != 0L) NativeBindings.waterui_drop_computed_f32(metadata.intensityPtr)
    }

    container
}

// ========== Registration Functions ==========

internal fun RegistryBuilder.registerWuiBlur() {
    registerMetadata({ metadataBlurTypeId }, metadataBlurRenderer)
}

internal fun RegistryBuilder.registerWuiOpacity() {
    registerMetadata({ metadataOpacityTypeId }, metadataOpacityRenderer)
}

internal fun RegistryBuilder.registerWuiBrightness() {
    registerMetadata({ metadataBrightnessTypeId }, metadataBrightnessRenderer)
}

internal fun RegistryBuilder.registerWuiSaturation() {
    registerMetadata({ metadataSaturationTypeId }, metadataSaturationRenderer)
}

internal fun RegistryBuilder.registerWuiContrast() {
    registerMetadata({ metadataContrastTypeId }, metadataContrastRenderer)
}

internal fun RegistryBuilder.registerWuiHueRotation() {
    registerMetadata({ metadataHueRotationTypeId }, metadataHueRotationRenderer)
}

internal fun RegistryBuilder.registerWuiGrayscale() {
    registerMetadata({ metadataGrayscaleTypeId }, metadataGrayscaleRenderer)
}
