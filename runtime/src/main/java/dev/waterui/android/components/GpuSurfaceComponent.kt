package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import dev.waterui.android.runtime.GpuSurfaceStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.dp

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
@SuppressLint("ClickableViewAccessibility")
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

    // Pointer state tracking
    private var pointerHasPosition: Boolean = false
    private var pointerX: Float = 0f
    private var pointerY: Float = 0f
    private var pointerPressed: Boolean = false
    private var pointerHasPressOrigin: Boolean = false
    private var pointerPressOriginX: Float = 0f
    private var pointerPressOriginY: Float = 0f

    init {
        // Set layout params to fill parent
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Register for surface callbacks
        holder.addCallback(this)
    }

    // ========== Touch/Pointer Tracking ==========

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerHasPosition = true
                pointerX = event.x
                pointerY = event.y
                pointerPressed = true
                pointerHasPressOrigin = true
                pointerPressOriginX = event.x
                pointerPressOriginY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                pointerHasPosition = true
                pointerX = event.x
                pointerY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerPressed = false
                pointerHasPressOrigin = false
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                pointerHasPosition = true
                pointerX = event.x
                pointerY = event.y
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                pointerHasPosition = false
            }
        }
        return super.onHoverEvent(event)
    }

    /** Sync pointer state to the GPU surface before rendering. */
    private fun syncPointerState() {
        if (gpuState != 0L) {
            NativeBindings.waterui_gpu_surface_set_pointer(
                gpuState,
                pointerHasPosition,
                pointerX,
                pointerY,
                pointerHasPressOrigin,
                pointerPressOriginX,
                pointerPressOriginY
            )
        }
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

        // Sync pointer state before rendering
        syncPointerState()

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

        val defaultSizePx = DEFAULT_SIZE_DP.dp(context).toInt()

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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Stop rendering when detached
        isRendering = false
    }

    companion object {
        private const val DEFAULT_SIZE_DP = 100f
    }
}

internal fun RegistryBuilder.registerWuiGpuSurface() {
    register({ gpuSurfaceTypeId }, gpuSurfaceRenderer)
}
