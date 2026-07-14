package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ViewTransform
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRustTransform
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp

private val metadataScaleTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_scale_id().toTypeId()
}
private val metadataRotationTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_rotation_id().toTypeId()
}
private val metadataOffsetTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_offset_id().toTypeId()
}

// Rust anchor metadata is required at construction, so this runtime-only view cannot be inflated.
@SuppressLint("ViewConstructor")
private class AnchoredTransformLayout(
    context: Context,
    private val anchorX: Float,
    private val anchorY: Float
) : PassThroughFrameLayout(context) {
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        pivotX = width * anchorX
        pivotY = height * anchorY
    }
}

private fun PassThroughFrameLayout.bindFloat(
    pointer: Long,
    onValue: (Float, WuiAnimation) -> Unit
) {
    val computed = WuiComputed.float(pointer)
    computed.observeWithAnimation(onValue)
    disposeWith(computed)
}

private val metadataScaleRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_scale(node.rawPtr)
    val container = AnchoredTransformLayout(context, metadata.anchorX, metadata.anchorY)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
    var scaleX = 1f
    var scaleY = 1f
    fun apply(animation: WuiAnimation) {
        container.applyRustTransform(
            animation,
            ViewTransform(scaleX = scaleX, scaleY = scaleY)
        )
    }
    container.bindFloat(metadata.scaleXPtr) { value, animation ->
        scaleX = value
        apply(animation)
    }
    container.bindFloat(metadata.scaleYPtr) { value, animation ->
        scaleY = value
        apply(animation)
    }
    container
}

private val metadataRotationRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_rotation(node.rawPtr)
    AnchoredTransformLayout(context, metadata.anchorX, metadata.anchorY)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            bindFloat(metadata.anglePtr) { angle, animation ->
                applyRustTransform(animation, ViewTransform(rotation = angle))
            }
        }
}

private val metadataOffsetRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_offset(node.rawPtr)
    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
    var offsetX = 0f
    var offsetY = 0f
    fun apply(animation: WuiAnimation) {
        container.applyRustTransform(
            animation,
            ViewTransform(
                translationX = offsetX.dp(context),
                translationY = offsetY.dp(context)
            )
        )
    }
    container.bindFloat(metadata.offsetXPtr) { value, animation ->
        offsetX = value
        apply(animation)
    }
    container.bindFloat(metadata.offsetYPtr) { value, animation ->
        offsetY = value
        apply(animation)
    }
    container
}

internal fun RegistryBuilder.registerWuiTransforms() {
    registerMetadata({ metadataScaleTypeId }, metadataScaleRenderer)
    registerMetadata({ metadataRotationTypeId }, metadataRotationRenderer)
    registerMetadata({ metadataOffsetTypeId }, metadataOffsetRenderer)
}
