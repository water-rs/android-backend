package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import dev.waterui.android.runtime.GpuSurfaceStruct
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiDynamicRangeMode
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.activateHdrWindowMode
import dev.waterui.android.runtime.deactivateHdrWindowMode
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.findActivity
import dev.waterui.android.runtime.resolveWuiDynamicRangeMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val gpuSurfaceTypeId: WuiTypeId by lazy { WatcherJni.gpuSurfaceId().toTypeId() }

@Suppress("UNUSED_PARAMETER")
private val gpuSurfaceRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsGpuSurface(node.rawPtr)
    GpuSurfaceView(context, struct)
}

@SuppressLint("ClickableViewAccessibility")
private class GpuSurfaceView(
    context: Context,
    private val gpuSurfaceData: GpuSurfaceStruct
) : SurfaceView(context), SurfaceHolder.Callback2, Choreographer.FrameCallback {

    @Volatile
    private var gpuState: Long = 0L

    private var rendererPtr: Long = gpuSurfaceData.rendererPtr
    private val renderMode: Int = gpuSurfaceData.renderMode

    @Volatile
    private var isRendering = false

    @Volatile
    private var surfaceWidth: Int = 0

    @Volatile
    private var surfaceHeight: Int = 0

    @Volatile
    private var needsRender = true

    @Volatile
    private var frameCallbackScheduled = false

    private val renderInFlight = AtomicBoolean(false)

    private var renderExecutor: ExecutorService = createRenderExecutor()

    @Volatile
    private var renderExecutorClosed = false

    @Volatile
    private var consecutiveRenderFailures = 0

    private var hdrWindowModeActive = false

    private var frameCounter: Long = 0

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
        requestRenderIfNeeded()
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
        requestRenderIfNeeded()
        return super.onHoverEvent(event)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        Log.i(TAG, "surfaceChanged")
        needsRender = true

        if (gpuState == 0L && rendererPtr != 0L) {
            Log.i(TAG, "init requested")

            ensureRenderExecutor()

            gpuState = WatcherJni.gpuSurfaceInit(
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

            Log.i(TAG, "init succeeded")

            consecutiveRenderFailures = 0
            isRendering = false
            resumeRenderingIfPossible()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed")

        pauseRendering()
        waitForRenderDrain()

        val statePtr = gpuState
        if (statePtr != 0L) {
            if (renderInFlight.get()) {
                Log.w(TAG, "Render still in flight during surfaceDestroyed; skipping drop to avoid race")
            } else {
                WatcherJni.gpuSurfaceDrop(statePtr)
            }
            gpuState = 0L
        }

        surfaceWidth = 0
        surfaceHeight = 0
        consecutiveRenderFailures = 0
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        renderOneShot()
    }

    override fun surfaceRedrawNeededAsync(
        holder: SurfaceHolder,
        drawingFinished: Runnable
    ) {
        renderOneShot {
            drawingFinished.run()
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering || gpuState == 0L || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return
        }

        frameCallbackScheduled = false
        if (renderMode == RENDER_MODE_ON_DEMAND && !needsRender) {
            return
        }

        val frame = frameCounter + 1
        frameCounter = frame
        if (frame <= 3L || frame % 120L == 0L) {
            Log.i(TAG, "doFrame start frame=" + frame + " state=" + gpuState)
        }

        if (!renderInFlight.compareAndSet(false, true)) {
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

        if (renderMode == RENDER_MODE_ON_DEMAND) {
            needsRender = false
        }

        renderExecutor.execute {
            try {
                if (!isRendering || statePtr == 0L || gpuState != statePtr) {
                    return@execute
                }

                WatcherJni.gpuSurfaceSetPointer(
                    statePtr,
                    pointer.hasPosition,
                    pointer.x,
                    pointer.y,
                    pointer.hasHit,
                    pointer.hitX,
                    pointer.hitY
                )

                val ok = WatcherJni.gpuSurfaceRender(statePtr, width, height)
                if (frame <= 3L || frame % 120L == 0L) {
                    Log.i(TAG, "doFrame result frame=" + frame + " ok=" + ok)
                }
                if (ok) {
                    consecutiveRenderFailures = 0
                } else {
                    val failures = consecutiveRenderFailures + 1
                    consecutiveRenderFailures = failures
                    if (renderMode == RENDER_MODE_ON_DEMAND) {
                        needsRender = true
                    }
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
                if (renderMode == RENDER_MODE_ON_DEMAND) {
                    needsRender = true
                }
                if (failures >= MAX_CONSECUTIVE_RENDER_FAILURES) {
                    post { pauseRendering() }
                }
            } finally {
                renderInFlight.set(false)

                if (isRendering && gpuState == statePtr && surfaceWidth > 0 && surfaceHeight > 0) {
                    post {
                        if (isRendering && gpuState == statePtr) {
                            scheduleFrameIfNeeded()
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
        needsRender = true
        scheduleFrameIfNeeded()
    }

    private fun pauseRendering() {
        if (!isRendering) {
            return
        }
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(this)
        frameCallbackScheduled = false
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
        releaseDynamicRangePolicy()
        shutdownRenderExecutor()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyDynamicRangePolicy()
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

    private fun requestRenderIfNeeded() {
        if (renderMode != RENDER_MODE_ON_DEMAND) {
            return
        }
        needsRender = true
        scheduleFrameIfNeeded()
    }

    private fun scheduleFrameIfNeeded() {
        if (!isRendering || gpuState == 0L || surfaceWidth <= 0 || surfaceHeight <= 0) {
            return
        }
        if (frameCallbackScheduled) {
            return
        }
        if (renderMode == RENDER_MODE_ON_DEMAND && !needsRender) {
            return
        }
        frameCallbackScheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun renderOneShot(onFinished: (() -> Unit)? = null) {
        if (gpuState == 0L || surfaceWidth <= 0 || surfaceHeight <= 0) {
            onFinished?.invoke()
            return
        }
        if (!renderInFlight.compareAndSet(false, true)) {
            onFinished?.invoke()
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
                if (statePtr == 0L || gpuState != statePtr) {
                    return@execute
                }

                WatcherJni.gpuSurfaceSetPointer(
                    statePtr,
                    pointer.hasPosition,
                    pointer.x,
                    pointer.y,
                    pointer.hasHit,
                    pointer.hitX,
                    pointer.hitY
                )

                WatcherJni.gpuSurfaceRender(statePtr, width, height)
                if (renderMode == RENDER_MODE_ON_DEMAND) {
                    needsRender = false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "GpuSurface redraw failed: ${t.message}", t)
                if (renderMode == RENDER_MODE_ON_DEMAND) {
                    needsRender = true
                }
            } finally {
                renderInFlight.set(false)
                onFinished?.invoke()
            }
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

    private fun applyDynamicRangePolicy() {
        val mode = resolveWuiDynamicRangeMode()
        configureSurfacePixelFormat(mode)
        if (mode == WuiDynamicRangeMode.HIGH) {
            val activity = context.findActivity() ?: return
            // Default policy is HDR; on non-HDR displays we degrade gracefully.
            activateHdrWindowMode(activity.window, requireCapability = false)
            hdrWindowModeActive = true
            return
        }
        releaseDynamicRangePolicy()
    }

    private fun releaseDynamicRangePolicy() {
        if (!hdrWindowModeActive) {
            return
        }
        val activity = context.findActivity()
        deactivateHdrWindowMode(activity?.window)
        hdrWindowModeActive = false
    }

    private fun configureSurfacePixelFormat(mode: WuiDynamicRangeMode) {
        val format = if (mode == WuiDynamicRangeMode.HIGH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PixelFormat.RGBA_F16
        } else {
            PixelFormat.RGBA_8888
        }
        holder.setFormat(format)
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
        private const val RENDER_MODE_CONTINUOUS = 0
        private const val RENDER_MODE_ON_DEMAND = 1
    }
}

internal fun RegistryBuilder.registerWuiGpuSurface() {
    register({ gpuSurfaceTypeId }, gpuSurfaceRenderer)
}
