package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.Keep
import dev.waterui.android.runtime.GpuSurfaceStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.SurfaceDynamicRange
import dev.waterui.android.runtime.TAG_DYNAMIC_RANGE
import dev.waterui.android.runtime.TAG_LAYOUT_PRIORITY
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import kotlin.math.roundToInt

private val gpuSurfaceTypeId: WuiTypeId by lazy { NativeBindings.waterui_gpu_surface_id().toTypeId() }
private const val GPU_SURFACE_LOG_TAG = "WaterUI.GpuSurface"

private val gpuSurfaceRenderer = WuiRenderer { context, node, env, _ ->
    val struct = NativeBindings.waterui_force_as_gpu_surface(node.rawPtr)
    GpuSurfaceView(
        context = context,
        rendererPtr = struct.rendererPtr,
        envPtr = env.raw(),
        hasHdrPreference = struct.hasHdrPreference,
        prefersHdr = struct.prefersHdr,
        hasPictureInPictureHostId = struct.hasPictureInPictureHostId,
        pictureInPictureHostId = struct.pictureInPictureHostId
    )
}

@Keep
@SuppressLint("ViewConstructor")
private class GpuSurfaceView(
    context: Context,
    rendererPtr: Long,
    envPtr: Long,
    private val hasHdrPreference: Boolean,
    private val prefersHdr: Boolean,
    hasPictureInPictureHostId: Boolean,
    pictureInPictureHostId: Long
) : SurfaceView(context), SurfaceHolder.Callback2, Choreographer.FrameCallback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private var statePtr = 0L
    private var surfaceAttached = false
    private var rendererReady = false
    private var setupPending = false
    private var layoutRequestedWhileSetup = false
    private var surfaceRedrawCompletion: Runnable? = null
    private var rendererPrefersHdr: Boolean? = null
    private lateinit var cachedDimensions: ViewDimensionsStruct
    private val frameScheduler = GpuFrameScheduler(
        postFrame = { choreographer.postFrameCallback(this) },
        cancelFrame = { choreographer.removeFrameCallback(this) },
        renderFrame = ::renderFrame
    )

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var hasPointerPosition = false
    private var pointerX = 0f
    private var pointerY = 0f
    private var hasHit = false
    private var hitX = 0f
    private var hitY = 0f
    private var gestureActive = false
    private var pinchScale = 1f
    private var hasPinchCenter = false
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f
    private var panOffsetX = 0f
    private var panOffsetY = 0f
    private var doubleTap = false
    private val redrawRequest = Runnable {
        if (statePtr != 0L && refreshRendererReadiness()) {
            frameScheduler.requestFrame()
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                gestureActive = true
                pinchScale = 1f
                updatePinchCenter(detector)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchScale *= detector.scaleFactor
                updatePinchCenter(detector)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                hasPinchCenter = false
            }
        }
    )
    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onDoubleTap(event: MotionEvent): Boolean {
                pointerX = event.x
                pointerY = event.y
                doubleTap = true
                return true
            }
        }
    )

    init {
        statePtr = NativeBindings.waterui_gpu_surface_create(
            owner = this,
            rendererPtr = rendererPtr,
            hasPictureInPictureHostId = hasPictureInPictureHostId,
            pictureInPictureHostId = pictureInPictureHostId,
            wuiEnvPtr = envPtr
        )
        setTag(TAG_LAYOUT_PRIORITY, NativeBindings.waterui_gpu_surface_priority(statePtr))
        cachedDimensions = NativeBindings.waterui_gpu_surface_measure(
            statePtr = statePtr,
            width = Float.NaN,
            height = Float.NaN
        )
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isClickable = true
        holder.addCallback(this)
        disposeWith(::disposeNativeState)
    }

    override fun surfaceCreated(_holder: SurfaceHolder) {
        Log.d(GPU_SURFACE_LOG_TAG, "surfaceCreated")
    }

    override fun surfaceChanged(holder: SurfaceHolder, _format: Int, width: Int, height: Int) {
        Log.d(
            GPU_SURFACE_LOG_TAG,
            "surfaceChanged ${width}x$height attached=$surfaceAttached previous=${surfaceWidth}x$surfaceHeight"
        )
        require(width > 0 && height > 0) { "GpuSurface dimensions must be positive: ${width}x$height" }
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        scheduleSurfaceRedraw(holder, null)
    }

    override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, drawingFinished: Runnable) {
        check(surfaceRedrawCompletion == null) {
            "GpuSurface received a second redraw request before completing the first"
        }
        surfaceRedrawCompletion = drawingFinished
        scheduleSurfaceRedraw(holder, drawingFinished)
    }

    private fun scheduleSurfaceRedraw(holder: SurfaceHolder, completion: Runnable?) {
        mainHandler.post {
            if (completion != null && surfaceRedrawCompletion !== completion) {
                return@post
            }
            if (!holder.surface.isValid) {
                finishSurfaceRedraw()
                return@post
            }
            attachSurfaceIfNeeded(holder)
            pushInput()
            frameScheduler.resume()
            if (refreshRendererReadiness()) {
                frameScheduler.requestFrame()
            }
        }
    }

    private fun attachSurfaceIfNeeded(holder: SurfaceHolder) {
        if (!surfaceAttached) {
            NativeBindings.waterui_gpu_surface_attach(
                statePtr = statePtr,
                surface = holder.surface,
                width = width,
                height = height,
                prefersHdr = frozenRendererHdrPreference()
            )
            surfaceAttached = true
            setupPending = !refreshRendererReadiness()
        }
        requestMaximumFrameRate(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(GPU_SURFACE_LOG_TAG, "surfaceDestroyed attached=$surfaceAttached")
        frameScheduler.pause()
        clearFrameRate(holder.surface)
        if (surfaceAttached) {
            NativeBindings.waterui_gpu_surface_detach(statePtr)
            surfaceAttached = false
        }
        finishSurfaceRedraw()
    }

    override fun doFrame(_frameTimeNanos: Long) {
        frameScheduler.onFrame()
    }

    /** Called from Rust's redraw callback; the callback may arrive on any thread. */
    @Keep
    fun requestNativeRedraw() {
        if (Looper.myLooper() === Looper.getMainLooper()) {
            redrawRequest.run()
        } else {
            mainHandler.post(redrawRequest)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        pointerX = event.x
        pointerY = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hasPointerPosition = true
                hasHit = true
                hitX = event.x
                hitY = event.y
                panOffsetX = 0f
                panOffsetY = 0f
                pinchScale = 1f
            }
            MotionEvent.ACTION_MOVE -> {
                hasPointerPosition = true
                panOffsetX = event.x - hitX
                panOffsetY = event.y - hitY
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                clearActiveInput()
            }
            MotionEvent.ACTION_CANCEL -> clearActiveInput()
        }
        gestureActive = hasHit || scaleDetector.isInProgress
        pushInput()
        if (refreshRendererReadiness()) {
            frameScheduler.requestFrame()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onAttachedToWindow() {
        configureRendererSurfaceFormat()
        configurePresentationDynamicRange()
        super.onAttachedToWindow()
        if (surfaceAttached) {
            requestMaximumFrameRate(holder.surface)
            frameScheduler.resume()
            if (refreshRendererReadiness()) {
                frameScheduler.requestFrame()
            }
        }
    }

    override fun onDetachedFromWindow() {
        frameScheduler.pause()
        if (surfaceAttached) {
            clearFrameRate(holder.surface)
        }
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        configurePresentationDynamicRange()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        if (setupPending) {
            layoutRequestedWhileSetup = true
        } else {
            cachedDimensions = NativeBindings.waterui_gpu_surface_measure(
                statePtr = statePtr,
                width = proposalDimension(widthMeasureSpec, density),
                height = proposalDimension(heightMeasureSpec, density)
            )
        }
        val desiredWidth = (cachedDimensions.size.width * density).roundToInt()
        val desiredHeight = (cachedDimensions.size.height * density).roundToInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    private fun renderFrame(): Boolean {
        val consumedDoubleTap = doubleTap
        val needsRedraw = NativeBindings.waterui_gpu_surface_render(
            statePtr = statePtr,
            width = surfaceWidth,
            height = surfaceHeight
        )
        if (consumedDoubleTap) {
            doubleTap = false
            pushInput()
        }
        finishSurfaceRedraw()
        return needsRedraw
    }

    private fun finishSurfaceRedraw() {
        surfaceRedrawCompletion?.run()
        surfaceRedrawCompletion = null
    }

    private fun pushInput() {
        if (!surfaceAttached) {
            return
        }
        NativeBindings.waterui_gpu_surface_set_input(
            statePtr = statePtr,
            hasPosition = hasPointerPosition,
            x = pointerX,
            y = pointerY,
            hasHit = hasHit,
            hitX = hitX,
            hitY = hitY,
            gestureActive = gestureActive,
            pinchScale = pinchScale,
            hasPinchCenter = hasPinchCenter,
            pinchCenterX = pinchCenterX,
            pinchCenterY = pinchCenterY,
            panOffsetX = panOffsetX,
            panOffsetY = panOffsetY,
            doubleTap = doubleTap
        )
    }

    private fun updatePinchCenter(detector: ScaleGestureDetector) {
        hasPinchCenter = true
        pinchCenterX = detector.focusX
        pinchCenterY = detector.focusY
    }

    private fun clearActiveInput() {
        hasPointerPosition = false
        hasHit = false
        hasPinchCenter = false
        gestureActive = false
        pinchScale = 1f
        panOffsetX = 0f
        panOffsetY = 0f
    }

    private fun disposeNativeState() {
        mainHandler.removeCallbacks(redrawRequest)
        frameScheduler.dispose()
        if (surfaceAttached) {
            NativeBindings.waterui_gpu_surface_detach(statePtr)
            surfaceAttached = false
        }
        NativeBindings.waterui_gpu_surface_drop(statePtr)
        statePtr = 0L
        holder.removeCallback(this)
    }

    private fun refreshRendererReadiness(): Boolean {
        if (!rendererReady && NativeBindings.waterui_gpu_surface_is_ready(statePtr)) {
            rendererReady = true
            setupPending = false
            if (layoutRequestedWhileSetup) {
                layoutRequestedWhileSetup = false
                requestLayout()
            }
        }
        return rendererReady
    }

    private fun proposalDimension(measureSpec: Int, density: Float): Float =
        when (MeasureSpec.getMode(measureSpec)) {
            MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> MeasureSpec.getSize(measureSpec) / density
            MeasureSpec.UNSPECIFIED -> Float.NaN
            else -> error("unknown MeasureSpec mode: ${MeasureSpec.getMode(measureSpec)}")
        }

    private fun requestMaximumFrameRate(surface: Surface) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val attachedDisplay = checkNotNull(display) {
            "GpuSurface cannot select a frame rate before it is attached to a display"
        }
        val currentMode = attachedDisplay.mode
        val maximumRefreshRate = attachedDisplay.supportedModes
            .asSequence()
            .filter { mode ->
                mode.physicalWidth == currentMode.physicalWidth &&
                    mode.physicalHeight == currentMode.physicalHeight
            }
            .maxOf { mode -> mode.refreshRate }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            surface.setFrameRate(
                maximumRefreshRate,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            )
        } else {
            surface.setFrameRate(maximumRefreshRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    private fun clearFrameRate(surface: Surface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    private fun resolvedRendererHdrPreference(): Boolean =
        if (hasHdrPreference) {
            prefersHdr
        } else {
            when (inheritedDynamicRange()) {
                SurfaceDynamicRange.STANDARD -> false
                SurfaceDynamicRange.HIGH -> true
                null -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    resources.configuration.isScreenHdr
            }
        }

    private fun frozenRendererHdrPreference(): Boolean = rendererPrefersHdr
        ?: resolvedRendererHdrPreference().also { rendererPrefersHdr = it }

    private fun configureRendererSurfaceFormat() {
        val format = if (
            frozenRendererHdrPreference() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            PixelFormat.RGBA_F16
        } else {
            PixelFormat.OPAQUE
        }
        holder.setFormat(format)
    }

    private fun configurePresentationDynamicRange() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val standardRange = if (hasHdrPreference) {
                !prefersHdr
            } else {
                inheritedDynamicRange() == SurfaceDynamicRange.STANDARD
            }
            setDesiredHdrHeadroom(if (standardRange) 1f else 0f)
        }
    }

    private fun inheritedDynamicRange(): SurfaceDynamicRange? {
        var view: View? = this
        while (view != null) {
            val range = view.getTag(TAG_DYNAMIC_RANGE) as? SurfaceDynamicRange
            if (range != null) {
                return range
            }
            view = view.parent as? View
        }
        return null
    }
}

internal fun RegistryBuilder.registerWuiGpuSurface() {
    register({ gpuSurfaceTypeId }, gpuSurfaceRenderer)
}
