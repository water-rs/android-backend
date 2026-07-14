package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import androidx.core.graphics.withClip
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PathCommandStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val metadataClipShapeTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_clip_shape_id().toTypeId()
}

// Rust path commands are required at construction, so this runtime-only view cannot be inflated.
@SuppressLint("ViewConstructor")
private class ClipShapeLayout(
    context: Context,
    private val commands: Array<PathCommandStruct>
) : PassThroughFrameLayout(context) {
    private var clipPath: Path? = null

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clipPath = buildNormalizedPath(commands, width.toFloat(), height.toFloat())
    }

    override fun dispatchDraw(canvas: Canvas) {
        val path = checkNotNull(clipPath) { "clip shape drew before receiving its size" }
        canvas.withClip(path) {
            super.dispatchDraw(canvas)
        }
    }
}

private val metadataClipShapeRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_clip_shape(node.rawPtr)
    ClipShapeLayout(context, metadata.commands)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
}

internal fun RegistryBuilder.registerWuiClipShape() {
    registerMetadata({ metadataClipShapeTypeId }, metadataClipShapeRenderer)
}
