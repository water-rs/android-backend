package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.FoldedTransform
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ViewTransform
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRustTransform
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView

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
// Survives as the single-child wrapper for transform ops the folded chain
// cannot express — a scale outside a rotation is a shear, outside the
// T·R·S property class a View carries.
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

private fun View.bindFloat(
    pointer: Long,
    env: WuiEnvironment,
    onValue: (Float, WuiAnimation) -> Unit
) {
    val computed = WuiComputed.float(pointer, env)
    computed.observeWithAnimation(onValue)
    disposeWith(computed)
}

private val metadataScaleRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_scale(node.rawPtr)
    val child = inflateAnyView(context, metadata.contentPtr, env, registry)

    var scaleX = 1f
    var scaleY = 1f

    val foldedOp = FoldedTransform.on(child).addScale(metadata.anchorX, metadata.anchorY)
    if (foldedOp != null) {
        // `.scale` folds into the child's own properties.
        fun apply(animation: WuiAnimation) {
            foldedOp.x = scaleX
            foldedOp.y = scaleY
            FoldedTransform.on(child).recompose(foldedOp, animation)
        }
        child.bindFloat(metadata.scaleXPtr, env) { value, animation ->
            scaleX = value
            apply(animation)
        }
        child.bindFloat(metadata.scaleYPtr, env) { value, animation ->
            scaleY = value
            apply(animation)
        }
        child
    } else {
        val container = AnchoredTransformLayout(context, metadata.anchorX, metadata.anchorY)
        container.addView(child)
        fun apply(animation: WuiAnimation) {
            container.applyRustTransform(
                animation,
                ViewTransform(scaleX = scaleX, scaleY = scaleY)
            )
        }
        container.bindFloat(metadata.scaleXPtr, env) { value, animation ->
            scaleX = value
            apply(animation)
        }
        container.bindFloat(metadata.scaleYPtr, env) { value, animation ->
            scaleY = value
            apply(animation)
        }
        container
    }
}

private val metadataRotationRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_rotation(node.rawPtr)
    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    val fold = FoldedTransform.on(child)
    val op = fold.addRotation(metadata.anchorX, metadata.anchorY)
    child.bindFloat(metadata.anglePtr, env) { angle, animation ->
        op.degrees = angle
        fold.recompose(op, animation)
    }
    child
}

private val metadataOffsetRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_offset(node.rawPtr)
    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    val fold = FoldedTransform.on(child)
    val op = fold.addOffset()
    var offsetX = 0f
    var offsetY = 0f
    fun apply(animation: WuiAnimation) {
        op.x = offsetX.dp(context)
        op.y = offsetY.dp(context)
        fold.recompose(op, animation)
    }
    child.bindFloat(metadata.offsetXPtr, env) { value, animation ->
        offsetX = value
        apply(animation)
    }
    child.bindFloat(metadata.offsetYPtr, env) { value, animation ->
        offsetY = value
        apply(animation)
    }
    child
}

internal fun RegistryBuilder.registerWuiTransforms() {
    registerMetadata({ metadataScaleTypeId }, metadataScaleRenderer)
    registerMetadata({ metadataRotationTypeId }, metadataRotationRenderer)
    registerMetadata({ metadataOffsetTypeId }, metadataOffsetRenderer)
}
