package dev.waterui.android.components

import android.graphics.Bitmap
import android.widget.ImageView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RENDERER_BUFFER_FORMAT_RGBA8888
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId
import kotlin.math.roundToInt

private val rendererViewTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_renderer_view_id().toTypeId()
}

private val rendererViewRenderer = WuiRenderer { context, node, _, _ ->
    val handle = NativeBindings.waterui_force_as_renderer_view(node.rawPtr)
    val width = NativeBindings.waterui_renderer_view_width(handle).roundToInt().coerceAtLeast(0)
    val height = NativeBindings.waterui_renderer_view_height(handle).roundToInt().coerceAtLeast(0)
    val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
    }

    val bitmap = renderRendererBitmap(handle, width, height)
    if (bitmap != null) {
        imageView.setImageBitmap(bitmap)
    }

    imageView.disposeWith {
        NativeBindings.waterui_drop_renderer_view(handle)
    }
    imageView
}

private fun renderRendererBitmap(handle: Long, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    if (NativeBindings.waterui_renderer_view_preferred_format(handle) != RENDERER_BUFFER_FORMAT_RGBA8888) {
        return null
    }
    val stride = width * 4
    val buffer = ByteArray(stride * height)
    val rendered = NativeBindings.waterui_renderer_view_render_cpu(
        handle,
        buffer,
        width,
        height,
        stride,
        RENDERER_BUFFER_FORMAT_RGBA8888
    )
    if (!rendered) return null

    val pixels = IntArray(width * height)
    var offset = 0
    for (index in pixels.indices) {
        val r = buffer[offset].toInt() and 0xFF
        val g = buffer[offset + 1].toInt() and 0xFF
        val b = buffer[offset + 2].toInt() and 0xFF
        val a = buffer[offset + 3].toInt() and 0xFF
        pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
        offset += 4
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiRendererView() {
    register({ rendererViewTypeId }, rendererViewRenderer)
}
