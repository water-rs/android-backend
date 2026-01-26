package dev.waterui.android.components

import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.view.View
import android.widget.FrameLayout

/**
 * Captures a child [View] into an [HardwareBuffer] using an [ImageReader] (API 29+).
 *
 * This is used by GPU post-processing components (ViewEffect / AppliedFilter) to
 * feed the Rust wgpu pipeline with zero-copy textures.
 */
internal class HardwareBufferCapturer(
    private val childView: View
) {
    private var imageReader: ImageReader? = null
    private var currentHardwareBuffer: HardwareBuffer? = null
    private var width: Int = 0
    private var height: Int = 0

    fun onSizeChanged(newWidth: Int, newHeight: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("HardwareBuffer capture requires Android API 29+ (Q)")
        }
        if (newWidth <= 0 || newHeight <= 0) return

        if (newWidth == width && newHeight == height && imageReader != null) return

        width = newWidth
        height = newHeight

        imageReader?.close()
        imageReader = null
        currentHardwareBuffer?.close()
        currentHardwareBuffer = null

        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
    }

    fun capture(): HardwareBuffer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("HardwareBuffer capture requires Android API 29+ (Q)")
        }
        val reader = imageReader ?: return null

        val canvas: Canvas = reader.surface.lockHardwareCanvas()
        try {
            val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            childView.measure(widthSpec, heightSpec)
            childView.layout(0, 0, width, height)
            childView.draw(canvas)
        } finally {
            reader.surface.unlockCanvasAndPost(canvas)
        }

        val image = reader.acquireLatestImage() ?: return null
        try {
            currentHardwareBuffer?.close()
            currentHardwareBuffer = image.hardwareBuffer
            return currentHardwareBuffer
        } finally {
            image.close()
        }
    }

    fun close() {
        imageReader?.close()
        imageReader = null
        currentHardwareBuffer?.close()
        currentHardwareBuffer = null
    }
}

/**
 * Keeps a child view attached for lifecycle/resource management, but never draws it to screen.
 */
internal class CaptureHostLayout(context: android.content.Context) : FrameLayout(context) {
    override fun dispatchDraw(canvas: Canvas) {
        // Intentionally no-op.
    }
}

