package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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

    @Volatile
    private var isRendering = false

    @Volatile
    private var surfaceWidth: Int = 0

    @Volatile
    private var surfaceHeight: Int = 0

    @Volatile
    private var needsRender = true

    @Volatile
    private var requiresRedrawPolling = false

    @Volatile
    private var frameCallbackScheduled = false

    private val renderInFlight = AtomicBoolean(false)

    private var renderExecutor: ExecutorService = SharedGpuRenderExecutor.acquire()
    @Volatile
    private var hasRenderExecutorLease = true

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

    private var gestureActive: Boolean = false
    private var gesturePinchScale: Float = 1f
    private var gestureHasPinchCenter: Boolean = false
    private var gesturePinchCenterX: Float = 0f
    private var gesturePinchCenterY: Float = 0f
    private var gesturePanOffsetX: Float = 0f
    private var gesturePanOffsetY: Float = 0f
    private var gestureDoubleTap: Boolean = false

    private var panTracking: Boolean = false
    private var panStartCenterX: Float = 0f
    private var panStartCenterY: Float = 0f

    private val tapDetector: GestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetGestureState()
                gestureDoubleTap = true
                requestRenderIfNeeded()
                return true
            }
        }
    )

    private val scaleDetector: ScaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                gestureActive = true
                gesturePinchScale = 1f
                gestureHasPinchCenter = true
                gesturePinchCenterX = detector.focusX
                gesturePinchCenterY = detector.focusY
                requestRenderIfNeeded()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                gestureActive = true
                gesturePinchScale *= detector.scaleFactor
                gestureHasPinchCenter = true
                gesturePinchCenterX = detector.focusX
                gesturePinchCenterY = detector.focusY
                requestRenderIfNeeded()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                gesturePinchScale = 1f
                gestureHasPinchCenter = false
                if (!panTracking) {
                    gestureActive = false
                }
                requestRenderIfNeeded()
            }
        }
    )

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        holder.addCallback(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerHasPosition = true
                pointerX = event.x
                pointerY = event.y
                pointerHasPressOrigin = true
                pointerPressOriginX = event.x
                pointerPressOriginY = event.y
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    beginPanTracking(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                pointerHasPosition = true
                pointerX = event.x
                pointerY = event.y
                if (panTracking && event.pointerCount >= 2) {
                    updatePanTracking(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 2) {
                    endPanTracking()
                    if (!scaleDetector.isInProgress) {
                        gestureActive = false
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerHasPressOrigin = false
                endPanTracking()
                if (!scaleDetector.isInProgress) {
                    gestureActive = false
                    gesturePinchScale = 1f
                    gestureHasPinchCenter = false
                }
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
        Log.d(TAG, "surfaceCreated")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        Log.d(TAG, "surfaceChanged")
        needsRender = true

        if (gpuState == 0L && rendererPtr != 0L) {
            Log.d(TAG, "init requested")

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

            Log.d(TAG, "init succeeded")

            consecutiveRenderFailures = 0
            isRendering = false
            requiresRedrawPolling = WatcherJni.gpuSurfaceRequiresRedrawPoll(gpuState)
            resumeRenderingIfPossible()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")

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
        requiresRedrawPolling = false
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
        if (!needsRender) {
            if (!requiresRedrawPolling) {
                return
            }
            needsRender = WatcherJni.gpuSurfaceNeedsRedraw(gpuState)
            if (!needsRender) {
                scheduleFrameIfNeeded()
                return
            }
        }

        val frame = frameCounter + 1
        frameCounter = frame
        if (frame <= 3L || frame % 120L == 0L) {
            Log.d(TAG, "doFrame start frame=" + frame + " state=" + gpuState)
        }

        if (!renderInFlight.compareAndSet(false, true)) {
            return
        }

        ensureRenderExecutor()

        val statePtr = gpuState
        val width = surfaceWidth
        val height = surfaceHeight
        val input = captureInputSnapshot()

        needsRender = false

        renderExecutor.execute {
            try {
                if (!isRendering || statePtr == 0L || gpuState != statePtr) {
                    return@execute
                }

                WatcherJni.gpuSurfaceSetInput(
                    statePtr,
                    input.pointer.hasPosition,
                    input.pointer.x,
                    input.pointer.y,
                    input.pointer.hasHit,
                    input.pointer.hitX,
                    input.pointer.hitY,
                    input.gesture.active,
                    input.gesture.pinchScale,
                    input.gesture.hasPinchCenter,
                    input.gesture.pinchCenterX,
                    input.gesture.pinchCenterY,
                    input.gesture.panOffsetX,
                    input.gesture.panOffsetY,
                    input.gesture.doubleTap
                )

                val packed = WatcherJni.gpuSurfaceRender(statePtr, width, height)
                val ok = (packed and 1L) != 0L
                val needsRedraw = (packed and (1L shl 1)) != 0L
                if (frame <= 3L || frame % 120L == 0L) {
                    Log.d(TAG, "doFrame result frame=" + frame + " ok=" + ok)
                }
                if (ok) {
                    consecutiveRenderFailures = 0
                    if (needsRedraw) {
                        needsRender = true
                    }
                } else {
                    val failures = consecutiveRenderFailures + 1
                    consecutiveRenderFailures = failures
                    needsRender = true
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
                needsRender = true
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
        if (hasRenderExecutorLease) {
            return
        }
        renderExecutor = SharedGpuRenderExecutor.acquire()
        hasRenderExecutorLease = true
    }

    private fun shutdownRenderExecutor() {
        if (!hasRenderExecutorLease) {
            return
        }
        SharedGpuRenderExecutor.release()
        hasRenderExecutorLease = false
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
        if (!needsRender && !requiresRedrawPolling) {
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
        val input = captureInputSnapshot()

        renderExecutor.execute {
            try {
                if (statePtr == 0L || gpuState != statePtr) {
                    return@execute
                }

                WatcherJni.gpuSurfaceSetInput(
                    statePtr,
                    input.pointer.hasPosition,
                    input.pointer.x,
                    input.pointer.y,
                    input.pointer.hasHit,
                    input.pointer.hitX,
                    input.pointer.hitY,
                    input.gesture.active,
                    input.gesture.pinchScale,
                    input.gesture.hasPinchCenter,
                    input.gesture.pinchCenterX,
                    input.gesture.pinchCenterY,
                    input.gesture.panOffsetX,
                    input.gesture.panOffsetY,
                    input.gesture.doubleTap
                )

                val packed = WatcherJni.gpuSurfaceRender(statePtr, width, height)
                val ok = (packed and 1L) != 0L
                val needsRedraw = (packed and (1L shl 1)) != 0L
                needsRender = !ok || needsRedraw
            } catch (t: Throwable) {
                Log.w(TAG, "GpuSurface redraw failed: ${t.message}", t)
                needsRender = true
            } finally {
                renderInFlight.set(false)
                onFinished?.invoke()
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

    private fun beginPanTracking(event: MotionEvent) {
        val (cx, cy) = twoFingerCenter(event)
        panTracking = true
        panStartCenterX = cx
        panStartCenterY = cy
        gesturePanOffsetX = 0f
        gesturePanOffsetY = 0f
        gestureActive = true
    }

    private fun updatePanTracking(event: MotionEvent) {
        val (cx, cy) = twoFingerCenter(event)
        gesturePanOffsetX = cx - panStartCenterX
        gesturePanOffsetY = cy - panStartCenterY
        gestureActive = true
    }

    private fun endPanTracking() {
        panTracking = false
        gesturePanOffsetX = 0f
        gesturePanOffsetY = 0f
    }

    private fun resetGestureState() {
        gestureActive = false
        gesturePinchScale = 1f
        gestureHasPinchCenter = false
        gesturePanOffsetX = 0f
        gesturePanOffsetY = 0f
        panTracking = false
    }

    private fun twoFingerCenter(event: MotionEvent): Pair<Float, Float> {
        val firstIndex = 0
        val secondIndex = if (event.pointerCount > 1) 1 else 0
        val x = (event.getX(firstIndex) + event.getX(secondIndex)) * 0.5f
        val y = (event.getY(firstIndex) + event.getY(secondIndex)) * 0.5f
        return x to y
    }

    private fun captureInputSnapshot(): InputSnapshot {
        val snapshot = InputSnapshot(
            pointer = PointerSnapshot(
                hasPosition = pointerHasPosition,
                x = pointerX,
                y = pointerY,
                hasHit = pointerHasPressOrigin,
                hitX = pointerPressOriginX,
                hitY = pointerPressOriginY
            ),
            gesture = GestureSnapshot(
                active = gestureActive,
                pinchScale = gesturePinchScale,
                hasPinchCenter = gestureHasPinchCenter,
                pinchCenterX = gesturePinchCenterX,
                pinchCenterY = gesturePinchCenterY,
                panOffsetX = gesturePanOffsetX,
                panOffsetY = gesturePanOffsetY,
                doubleTap = gestureDoubleTap
            )
        )

        // doubleTap is a one-frame pulse for renderers.
        if (gestureDoubleTap) {
            gestureDoubleTap = false
        }
        return snapshot
    }

    private data class InputSnapshot(
        val pointer: PointerSnapshot,
        val gesture: GestureSnapshot
    )

    private data class PointerSnapshot(
        val hasPosition: Boolean,
        val x: Float,
        val y: Float,
        val hasHit: Boolean,
        val hitX: Float,
        val hitY: Float
    )

    private data class GestureSnapshot(
        val active: Boolean,
        val pinchScale: Float,
        val hasPinchCenter: Boolean,
        val pinchCenterX: Float,
        val pinchCenterY: Float,
        val panOffsetX: Float,
        val panOffsetY: Float,
        val doubleTap: Boolean
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
