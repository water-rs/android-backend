package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val gpuSurfaceTypeId: WuiTypeId by lazy { NativeBindings.waterui_gpu_surface_id().toTypeId() }

@Suppress("UNUSED_PARAMETER")
private val gpuSurfaceRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_gpu_surface(node.rawPtr)
    GpuSurfaceView(context, struct)
}

@SuppressLint("ClickableViewAccessibility")
private class GpuSurfaceView(
    context: Context,
    private val gpuSurfaceData: GpuSurfaceStruct
) : SurfaceView(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    @Volatile
    private var gpuState: Long = 0L

    private var rendererPtr: Long = gpuSurfaceData.rendererPtr

    @Volatile
    private var isRendering = false

    @Volatile
    private var surfaceWidth: Int = 0

    @Volatile
    private var surfaceHeight: Int = 0

    private val renderInFlight = AtomicBoolean(false)

    private var renderExecutor: ExecutorService = createRenderExecutor()

    @Volatile
    private var renderExecutorClosed = false

    @Volatile
    private var consecutiveRenderFailures = 0

    private var pointerHasPosition: Boolean = false
    private var pointerX: Float = 0f
    private var pointerY: Float = 0f
    private var pointerHasPressOrigin: Boolean = false
    private var pointerPressOriginX: Float = 0f
    private var pointerPressOriginY: Float = 0f

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        holder.addCallback(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerHasPosition = true
                pointerX = event.x
                pointerY = event.y
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

    override fun surfaceCreated(holder: SurfaceHolder) {
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height

        if (gpuState == 0L && rendererPtr != 0L) {
            ensureRenderExecutor()

            gpuState = NativeBindings.waterui_gpu_surface_init(
                rendererPtr,
                holder.surface,
                width,
                height
            )
            rendererPtr = 0L

            if (gpuState == 0L) {
                Log.w(TAG, "GpuSurface init failed; renderer disabled for this view")
                return
            }

            consecutiveRenderFailures = 0
            isRendering = false
            resumeRenderingIfPossible()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pauseRendering()
        waitForRenderDrain()

        val statePtr = gpuState
        if (statePtr != 0L) {
            if (renderInFlight.get()) {
                Log.w(TAG, "Render still in flight during surfaceDestroyed; skipping drop to avoid race")
            } else {
                NativeBindings.waterui_gpu_surface_drop(statePtr)
            }
            gpuState = 0L
        }

        surfaceWidth = 0
        surfaceHeight = 0
        consecutiveRenderFailures = 0
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering || gpuState == 0L || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return
        }

        if (!renderInFlight.compareAndSet(false, true)) {
            if (isRendering) {
                Choreographer.getInstance().postFrameCallback(this)
            }
            return
        }

        ensureRenderExecutor()

        val statePtr = gpuState
        val width = surfaceWidth
        val height = surfaceHeight
        val pointer = PointerSnapshot(
            hasPosition = pointerHasPosition,
            x = pointerX,
            y = pointerY,
            hasHit = pointerHasPressOrigin,
            hitX = pointerPressOriginX,
            hitY = pointerPressOriginY
        )

        renderExecutor.execute {
            try {
                if (!isRendering || statePtr == 0L || gpuState != statePtr) {
                    return@execute
                }

                NativeBindings.waterui_gpu_surface_set_pointer(
                    statePtr,
                    pointer.hasPosition,
                    pointer.x,
                    pointer.y,
                    pointer.hasHit,
                    pointer.hitX,
                    pointer.hitY
                )

                val ok = NativeBindings.waterui_gpu_surface_render(statePtr, width, height)
                if (ok) {
                    consecutiveRenderFailures = 0
                } else {
                    val failures = consecutiveRenderFailures + 1
                    consecutiveRenderFailures = failures
                    if (failures >= MAX_CONSECUTIVE_RENDER_FAILURES) {
                        post {
                            if (consecutiveRenderFailures >= MAX_CONSECUTIVE_RENDER_FAILURES) {
                                Log.w(TAG, "Stopping GpuSurface render loop after repeated failures")
                                pauseRendering()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                val failures = consecutiveRenderFailures + 1
                consecutiveRenderFailures = failures
                Log.w(TAG, "GpuSurface render failed: ${t.message}", t)
                if (failures >= MAX_CONSECUTIVE_RENDER_FAILURES) {
                    post { pauseRendering() }
                }
            } finally {
                renderInFlight.set(false)

                if (isRendering && gpuState == statePtr && surfaceWidth > 0 && surfaceHeight > 0) {
                    post {
                        if (isRendering && gpuState == statePtr) {
                            Choreographer.getInstance().postFrameCallback(this@GpuSurfaceView)
                        }
                    }
                }
            }
        }
    }

    private fun resumeRenderingIfPossible() {
        if (gpuState == 0L || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return
        }
        if (windowVisibility != VISIBLE || visibility != VISIBLE) {
            return
        }
        if (isRendering) {
            return
        }

        ensureRenderExecutor()
        isRendering = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun pauseRendering() {
        if (!isRendering) {
            return
        }
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun ensureRenderExecutor() {
        if (!renderExecutorClosed) {
            return
        }
        renderExecutor = createRenderExecutor()
        renderExecutorClosed = false
    }

    private fun shutdownRenderExecutor() {
        if (renderExecutorClosed) {
            return
        }
        renderExecutor.shutdownNow()
        renderExecutorClosed = true
    }

    private fun waitForRenderDrain() {
        val deadline = SystemClock.uptimeMillis() + RENDER_DRAIN_TIMEOUT_MS
        while (renderInFlight.get() && SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(4)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
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
        pauseRendering()
        waitForRenderDrain()
        shutdownRenderExecutor()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeRenderingIfPossible()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            resumeRenderingIfPossible()
        } else {
            pauseRendering()
        }
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            resumeRenderingIfPossible()
        } else {
            pauseRendering()
        }
    }

    private fun createRenderExecutor(): ExecutorService {
        return Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "WaterUI-GpuSurfaceRenderer").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY
            }
        }
    }

    private data class PointerSnapshot(
        val hasPosition: Boolean,
        val x: Float,
        val y: Float,
        val hasHit: Boolean,
        val hitX: Float,
        val hitY: Float
    )

    companion object {
        private const val TAG = "WaterUI.GpuSurface"
        private const val DEFAULT_SIZE_DP = 100f
        private const val MAX_CONSECUTIVE_RENDER_FAILURES = 3
        private const val RENDER_DRAIN_TIMEOUT_MS = 1200L
    }
}

internal fun RegistryBuilder.registerWuiGpuSurface() {
    register({ gpuSurfaceTypeId }, gpuSurfaceRenderer)
}
