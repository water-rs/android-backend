package dev.waterui.android.components

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import dev.waterui.android.runtime.GpuSurfaceStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val gpuSurfaceTypeId: WuiTypeId by lazy { NativeBindings.waterui_gpu_surface_id().toTypeId() }

/**
 * GpuSurface component renderer.
 *
 * Uses Android's SurfaceView with Choreographer for high-performance GPU rendering
 * at display refresh rates (60-120fps). The actual GPU rendering is performed by
 * the Rust wgpu backend.
 *
 * # Architecture
 *
 * - SurfaceView provides an ANativeWindow for zero-copy GPU access
 * - Choreographer provides VSync-aligned frame callbacks
 * - Rust owns wgpu Device/Queue/Surface and calls user's GpuRenderer
 *
 * # HDR Support
 *
 * On Android 8.0+ (API 26+), wide color gamut is enabled when available.
 * The wgpu backend handles HDR format selection internally.
 */
@Suppress("UNUSED_PARAMETER")
private val gpuSurfaceRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_gpu_surface(node.rawPtr)

    GpuSurfaceView(context, struct)
}

/**
 * Custom SurfaceView that handles GPU surface lifecycle and frame rendering.
 */
private class GpuSurfaceView(
    context: Context,
    private val gpuSurfaceData: GpuSurfaceStruct
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    /** Opaque pointer to WuiGpuSurfaceState (owns wgpu resources) */
    private var gpuState: Long = 0L

    /** Whether we're actively rendering frames */
    private var isRendering = false

    /** Current surface dimensions in pixels */
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    init {
        // Set stretch axis - GpuSurface fills all available space by default
        setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH)

        // Set layout params to fill parent
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Register for surface callbacks
        holder.addCallback(this)
    }

    // ========== SurfaceHolder.Callback ==========

    override fun surfaceCreated(holder: SurfaceHolder) {
        // Surface is ready, but we wait for surfaceChanged to get dimensions
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        if (gpuState == 0L && gpuSurfaceData.rendererPtr != 0L) {
            // Initialize GPU resources with the native surface
            // The JNI layer extracts ANativeWindow from the Surface via ANativeWindow_fromSurface
            gpuState = NativeBindings.waterui_gpu_surface_init(
                gpuSurfaceData.rendererPtr,
                holder.surface,
                width,
                height
            )

            if (gpuState != 0L) {
                // Start rendering loop
                isRendering = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Stop rendering and clean up
        isRendering = false

        if (gpuState != 0L) {
            NativeBindings.waterui_gpu_surface_drop(gpuState)
            gpuState = 0L
        }
    }

    // ========== Choreographer.FrameCallback ==========

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering || gpuState == 0L) {
            return
        }

        // Render frame with current dimensions
        NativeBindings.waterui_gpu_surface_render(
            gpuState,
            surfaceWidth,
            surfaceHeight
        )

        // Schedule next frame if still rendering
        if (isRendering) {
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ========== Layout ==========

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // GpuSurface expands to fill available space (like SwiftUI's Color)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val measuredWidth = when (widthMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
            else -> DEFAULT_SIZE
        }

        val measuredHeight = when (heightMode) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> heightSize
            else -> DEFAULT_SIZE
        }

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Stop rendering when detached
        isRendering = false
    }

    companion object {
        private const val DEFAULT_SIZE = 100
    }
}

internal fun RegistryBuilder.registerWuiGpuSurface() {
    register({ gpuSurfaceTypeId }, gpuSurfaceRenderer)
}
