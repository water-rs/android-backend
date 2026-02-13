package dev.waterui.android.components

import android.content.Context
import android.hardware.HardwareBuffer
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.ViewEffectStruct
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView

private val viewEffectTypeId: WuiTypeId by lazy { WatcherJni.viewEffectId().toTypeId() }

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
    val struct = WatcherJni.forceAsViewEffect(node.rawPtr)

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

    /**
     * Host that keeps [childView] attached for proper lifecycle management, but
     * never draws it to screen (we draw it manually into the capture surface).
     */
    private val captureHost: FrameLayout?

    private val capturer: HardwareBufferCapturer?

    init {
        captureHost = childView?.let { view ->
            CaptureHostLayout(context).also { host ->
                host.layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
                host.addView(
                    view,
                    LayoutParams(
                        LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT
                    )
                )
                addView(host)
            }
        }
        capturer = childView?.let { HardwareBufferCapturer(it) }

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
        capturer?.onSizeChanged(width, height)

        if (effectState == 0L && viewEffectData.effectPtr != 0L) {
            // Initialize GPU resources with the native surface
            effectState = WatcherJni.viewEffectInit(
                viewEffectData.rawPtr,
                holder.surface,
                width,
                height
            )

            if (effectState != 0L) {
                // Start rendering loop
                isRendering = true
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                error("ViewEffect initialization failed")
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Stop rendering and clean up
        isRendering = false

        capturer?.close()

        if (effectState != 0L) {
            WatcherJni.viewEffectDrop(effectState)
            effectState = 0L
        }
    }

    // ========== Choreographer.FrameCallback ==========

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering || effectState == 0L) {
            return
        }

        // Capture child view and pass to effect
        capturer?.capture()?.let { buffer ->
            val success = WatcherJni.viewEffectSetInputAHardwareBuffer(
                effectState,
                buffer,
                surfaceWidth,
                surfaceHeight
            )
            if (!success) {
                error("ViewEffect: failed to set AHardwareBuffer input")
            }
        }

        // Render frame with the effect
        WatcherJni.viewEffectRender(effectState)

        // Schedule next frame if still rendering
        if (isRendering) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ========== Layout ==========

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Delegate sizing to child view if present (it is attached via captureHost).
        if (childView != null) {
            measureChild(childView, widthMeasureSpec, heightMeasureSpec)
            val w = childView.measuredWidth
            val h = childView.measuredHeight
            // Ensure the output surface fills the same bounds.
            measureChild(
                outputSurfaceView,
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            captureHost?.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
            setMeasuredDimension(w, h)
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

            measureChild(
                outputSurfaceView,
                MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
            )
            captureHost?.measure(
                MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
            )
            setMeasuredDimension(measuredWidth, measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        captureHost?.layout(0, 0, right - left, bottom - top)
        // Layout output surface view to fill bounds
        outputSurfaceView.layout(0, 0, right - left, bottom - top)
        // Child is measured/laid out when we capture it
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Stop rendering when detached
        isRendering = false
        capturer?.close()
    }

    companion object {
        private const val DEFAULT_SIZE_DP = 100f
    }
}

internal fun RegistryBuilder.registerWuiViewEffect() {
    register({ viewEffectTypeId }, viewEffectRenderer)
}
