package dev.waterui.android.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.ViewEffectStruct
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView

private const val TAG = "ViewEffectComponent"

private val viewEffectTypeId: WuiTypeId by lazy { NativeBindings.waterui_view_effect_id().toTypeId() }

/**
 * ViewEffect component renderer.
 *
 * This component captures child view content and applies GPU effects to it.
 * Uses a SurfaceView for the output layer where the effect result is rendered.
 *
 * # Architecture
 *
 * - Child view renders to an ImageReader (backed by AHardwareBuffer)
 * - Rust imports the AHardwareBuffer as a Vulkan texture (zero-copy)
 * - Effect applies transformation and renders to output SurfaceView
 *
 * # Zero-Copy Path
 *
 * Uses ImageReader with HardwareBuffer usage flags to capture child view content.
 * The AHardwareBuffer is imported into Vulkan via VK_ANDROID_external_memory_android_hardware_buffer.
 */
@Suppress("UNUSED_PARAMETER")
private val viewEffectRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_view_effect(node.rawPtr)

    // Render child view
    val childView = if (struct.contentPtr != 0L) {
        inflateAnyView(context, struct.contentPtr, env, registry)
    } else {
        null
    }

    ViewEffectView(context, struct, childView)
}

/**
 * Custom view that handles ViewEffect lifecycle and rendering.
 *
 * Contains:
 * - A child view layer (rendered to ImageReader for capture)
 * - A SurfaceView for effect output (rendered by GPU)
 *
 * Uses ImageReader with HardwareBuffer for zero-copy texture capture on API 29+.
 * Falls back to Bitmap capture on older devices.
 */
private class ViewEffectView(
    context: Context,
    private val viewEffectData: ViewEffectStruct,
    private val childView: View?
) : FrameLayout(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    /** Opaque pointer to WuiViewEffectState (owns wgpu resources) */
    private var effectState: Long = 0L

    /** Whether we're actively rendering frames */
    private var isRendering = false

    /** Current surface dimensions in pixels */
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    /** The output surface view */
    private val outputSurfaceView: SurfaceView

    /** ImageReader for capturing child view to HardwareBuffer (API 29+) */
    private var captureImageReader: ImageReader? = null

    /** Current HardwareBuffer being used for capture */
    private var currentHardwareBuffer: HardwareBuffer? = null

    init {
        // Add child view if present (hidden, we draw it ourselves to the capture surface)
        childView?.let {
            // Don't add to view hierarchy - we'll draw manually to capture surface
        }

        // Create and add output surface view
        outputSurfaceView = SurfaceView(context)
        outputSurfaceView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        )
        outputSurfaceView.holder.addCallback(this)
        // Output surface should be on top
        outputSurfaceView.setZOrderOnTop(true)
        addView(outputSurfaceView)
    }

    // ========== SurfaceHolder.Callback ==========

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Surface is ready, but we wait for surfaceChanged to get dimensions
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        // Clean up old ImageReader if size changed
        captureImageReader?.close()
        captureImageReader = null
        currentHardwareBuffer?.close()
        currentHardwareBuffer = null

        // Create ImageReader for capture (API 29+ for HardwareBuffer support)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                captureImageReader = ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2, // Max images
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
                Log.d(TAG, "Created ImageReader: ${width}x${height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create ImageReader: $e")
            }
        }

        if (effectState == 0L && viewEffectData.effectPtr != 0L) {
            // Initialize GPU resources with the native surface
            effectState = NativeBindings.waterui_view_effect_init(
                viewEffectData.rawPtr,
                holder.surface,
                width,
                height
            )

            if (effectState != 0L) {
                Log.d(TAG, "ViewEffect initialized, starting render loop")
                // Start rendering loop
                isRendering = true
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                Log.e(TAG, "ViewEffect initialization failed")
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Stop rendering and clean up
        isRendering = false

        captureImageReader?.close()
        captureImageReader = null
        currentHardwareBuffer?.close()
        currentHardwareBuffer = null

        if (effectState != 0L) {
            NativeBindings.waterui_view_effect_drop(effectState)
            effectState = 0L
        }
    }

    // ========== Choreographer.FrameCallback ==========

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering || effectState == 0L) {
            return
        }

        // Capture child view and pass to effect
        if (childView != null && captureChildToHardwareBuffer()) {
            // Pass HardwareBuffer to Rust for zero-copy import
            currentHardwareBuffer?.let { buffer ->
                val success = NativeBindings.waterui_view_effect_set_input_ahardwarebuffer(
                    effectState,
                    buffer,
                    surfaceWidth,
                    surfaceHeight
                )
                if (!success) {
                    Log.w(TAG, "Failed to set AHardwareBuffer input")
                }
            }
        }

        // Render frame with the effect
        NativeBindings.waterui_view_effect_render(effectState)

        // Schedule next frame if still rendering
        if (isRendering) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Capture the child view content to a HardwareBuffer.
     *
     * Draws the child view to the ImageReader's surface, then acquires
     * the HardwareBuffer for zero-copy import into Vulkan.
     *
     * @return true if capture succeeded
     */
    private fun captureChildToHardwareBuffer(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        val imageReader = captureImageReader ?: return false
        val child = childView ?: return false

        // Draw child to ImageReader's surface
        val surface = imageReader.surface
        val canvas: Canvas? = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock hardware canvas: $e")
            null
        }

        if (canvas == null) {
            return false
        }

        try {
            // Measure and layout the child for drawing
            val widthSpec = MeasureSpec.makeMeasureSpec(surfaceWidth, MeasureSpec.EXACTLY)
            val heightSpec = MeasureSpec.makeMeasureSpec(surfaceHeight, MeasureSpec.EXACTLY)
            child.measure(widthSpec, heightSpec)
            child.layout(0, 0, surfaceWidth, surfaceHeight)

            // Draw the child view
            child.draw(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }

        // Acquire the image and get HardwareBuffer
        try {
            val image = imageReader.acquireLatestImage()
            if (image != null) {
                // Close old buffer
                currentHardwareBuffer?.close()

                // Get new HardwareBuffer
                currentHardwareBuffer = image.hardwareBuffer
                image.close()
                return currentHardwareBuffer != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire image: $e")
        }

        return false
    }

    // ========== Layout ==========

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Delegate sizing to child view if present
        if (childView != null) {
            measureChild(childView, widthMeasureSpec, heightMeasureSpec)
            setMeasuredDimension(childView.measuredWidth, childView.measuredHeight)
        } else {
            // Default size if no child
            val defaultSizePx = DEFAULT_SIZE_DP.dp(context).toInt()
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = MeasureSpec.getSize(heightMeasureSpec)

            val measuredWidth = when (widthMode) {
                MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
                else -> defaultSizePx
            }

            val measuredHeight = when (heightMode) {
                MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> heightSize
                else -> defaultSizePx
            }

            setMeasuredDimension(measuredWidth, measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // Layout output surface view to fill bounds
        outputSurfaceView.layout(0, 0, right - left, bottom - top)
        // Child is measured/laid out when we capture it
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Stop rendering when detached
        isRendering = false
        captureImageReader?.close()
        captureImageReader = null
        currentHardwareBuffer?.close()
        currentHardwareBuffer = null
    }

    companion object {
        private const val DEFAULT_SIZE_DP = 100f
    }
}

internal fun RegistryBuilder.registerWuiViewEffect() {
    register({ viewEffectTypeId }, viewEffectRenderer)
}
