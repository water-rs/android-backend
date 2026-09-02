package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
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
internal class GpuSurfaceView(
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
    private val surfaceRedrawCompletions = ArrayDeque<Runnable>()
    private var frameRateDeclared = false
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
    /**
     * The event sink for a GPU view that draws its own interactive content.
     *
     * Null for the common case — a view that only draws — so no focus is
     * claimed, no keystroke is intercepted, and the surrounding WaterUI widgets
     * keep every event.
     */
    private var inputSink: GpuSurfaceInputSink? = null
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
        // onMeasure reports the size the Rust renderer measured for the incoming
        // proposal, so the surface asks its parent to propose rather than dictate.
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        isClickable = true
        // A SurfaceView never composites *within* the view tree: its surface is
        // either below the window, which punches a hole through it, or above
        // the window entirely. Below the window, everywhere the renderer leaves
        // alpha at zero shows the punched-out black rather than the content
        // behind it, which turns every translucent icon and shape into a black
        // tile. `setZOrderMediaOverlay` does not avoid that — it only orders
        // this surface against other surfaces, still underneath the window.
        //
        // So the side is chosen by what the content is. In-flow SDR content is
        // translucent and small, and needs its transparency to blend over the
        // UI, so it goes on top. An HDR presentation surface is opaque and
        // full-bleed, and gains from staying below the window where ordinary
        // views can still draw over it.
        //
        // Both sides are compromises of the same limitation: a view that is
        // meant to overlap in-flow GPU content cannot, because z-order here is
        // not the tree's. `TextureView` is the only Android view that composites
        // GPU output in tree order with alpha, and is where in-flow surfaces
        // belong; moving them is a backend change of its own, not a detail of
        // this one.
        if (hasHdrPreference && prefersHdr) {
            setZOrderMediaOverlay(true)
        } else {
            setZOrderOnTop(true)
        }
        if (NativeBindings.waterui_gpu_surface_wants_input_events(statePtr)) {
            inputSink = GpuSurfaceInputSink(statePtr)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        holder.addCallback(this)
        disposeWith(::disposeNativeState)
    }

    override fun onCheckIsTextEditor(): Boolean = inputSink != null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val sink = inputSink ?: return null
        describeSurfaceEditor(outAttrs)
        return GpuSurfaceInputConnection(this, sink)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val sink = inputSink ?: return super.onKeyDown(keyCode, event)
        return sendSurfaceKey(sink, event, pressed = true) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val sink = inputSink ?: return super.onKeyUp(keyCode, event)
        return sendSurfaceKey(sink, event, pressed = false) || super.onKeyUp(keyCode, event)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        inputSink?.send(kind = WuiSurfaceInputEventKind.Focus, focused = gainFocus)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val sink = inputSink
        if (sink == null || event.actionMasked != MotionEvent.ACTION_SCROLL) {
            return super.onGenericMotionEvent(event)
        }
        val density = resources.displayMetrics.density
        // A wheel notch is a whole line and complete on its own; a trackpad or a
        // precise wheel reports a fraction of one, which the view scales itself.
        val fromWheel = event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)
        return sink.send(
            kind = WuiSurfaceInputEventKind.Scroll,
            x = (event.x / density).toDouble(),
            y = (event.y / density).toDouble(),
            deltaX = event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
            deltaY = event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble(),
            scrollUnit = WuiScrollUnit.Line,
            finished = fromWheel
        ) || super.onGenericMotionEvent(event)
    }

    override fun surfaceCreated(_holder: SurfaceHolder) {
        Log.d(GPU_SURFACE_LOG_TAG, "surfaceCreated")
        check(!surfaceAttached) {
            "GpuSurface received surfaceCreated while a previous surface is still attached"
        }
    }

    /**
     * Attaches the native swapchain.
     *
     * `SurfaceView` always delivers `surfaceChanged` right after `surfaceCreated`,
     * and it is the first callback that carries the real buffer dimensions, so this
     * is where attachment belongs. `surfaceRedrawNeeded`/`surfaceRedrawNeededAsync`
     * are draw-report callbacks that a window may never issue, so they only
     * schedule and complete redraws.
     */
    override fun surfaceChanged(holder: SurfaceHolder, _format: Int, width: Int, height: Int) {
        Log.d(
            GPU_SURFACE_LOG_TAG,
            "surfaceChanged ${width}x$height attached=$surfaceAttached previous=${surfaceWidth}x$surfaceHeight"
        )
        require(width > 0 && height > 0) { "GpuSurface dimensions must be positive: ${width}x$height" }
        surfaceWidth = width
        surfaceHeight = height
        attachSurfaceIfNeeded(holder)
        pushInput()
        resumeRendering()
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        scheduleSurfaceRedraw(holder)
    }

    override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, drawingFinished: Runnable) {
        // Several redraw requests can legitimately be in flight at once — a resize
        // arriving while an earlier request is still queued — and every one of them
        // owns a completion that `ViewRootImpl` waits on, so they all get to run.
        surfaceRedrawCompletions.addLast(drawingFinished)
        scheduleSurfaceRedraw(holder)
    }

    private fun scheduleSurfaceRedraw(holder: SurfaceHolder) {
        check(
            mainHandler.post {
                if (!holder.surface.isValid) {
                    finishSurfaceRedraw()
                    return@post
                }
                check(surfaceAttached) {
                    "GpuSurface was asked to redraw a surface it never attached"
                }
                // A redraw request is also the moment an occluded surface becomes
                // visible again, and Rust reports "no frame needed" for an occluded
                // surface, so the render loop is re-armed here.
                resumeRendering()
                if (!rendererReady) {
                    // wgpu setup is asynchronous and `ViewRootImpl` blocks the
                    // window's first draw report on this completion, so the report
                    // is never held hostage to GPU setup: it is answered now and the
                    // surface paints when Rust fires its redraw callback on setup
                    // completion.
                    finishSurfaceRedraw()
                }
            }
        ) { "Android main looper rejected a GpuSurface redraw request" }
    }

    /** Resumes the frame loop and asks for a frame once the renderer can draw one. */
    private fun resumeRendering() {
        if (!surfaceAttached) {
            return
        }
        frameScheduler.resume()
        if (refreshRendererReadiness()) {
            frameScheduler.requestFrame()
        }
    }

    private fun attachSurfaceIfNeeded(holder: SurfaceHolder) {
        if (surfaceAttached) {
            return
        }
        NativeBindings.waterui_gpu_surface_attach(
            statePtr = statePtr,
            surface = holder.surface,
            width = surfaceWidth,
            height = surfaceHeight,
            prefersHdr = frozenRendererHdrPreference()
        )
        surfaceAttached = true
        setupPending = !refreshRendererReadiness()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(GPU_SURFACE_LOG_TAG, "surfaceDestroyed attached=$surfaceAttached")
        frameScheduler.pause()
        clearDeclaredFrameRate(holder.surface)
        if (surfaceAttached) {
            NativeBindings.waterui_gpu_surface_detach(statePtr)
            surfaceAttached = false
        }
        finishSurfaceRedraw()
    }

    override fun doFrame(_frameTimeNanos: Long) {
        frameScheduler.onFrame()
    }

    /**
     * Called from Rust's redraw callback; the callback may arrive on any thread.
     *
     * The request is *always* delivered asynchronously, never inline, even when it
     * already arrives on the main looper. `waterui_gpu_surface_render` holds an
     * exclusive borrow of the native surface state for the whole of its call and
     * the renderer may request a redraw from inside it, so running the request
     * inline would re-enter the FFI (`waterui_gpu_surface_is_ready`) while that
     * borrow is live.
     */
    @Keep
    fun requestNativeRedraw() {
        check(mainHandler.post(redrawRequest)) {
            "Android main looper rejected a GpuSurface redraw request"
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isClickable) {
            return false
        }
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
                // Keep panOffset and pinchScale: the renderer settles the
                // gesture from the state pushed with active=false, so zeroing
                // them here would snap the camera back to where the gesture
                // started. They reset on the next ACTION_DOWN.
                releaseActiveInput()
            }
            MotionEvent.ACTION_CANCEL -> releaseActiveInput()
        }
        gestureActive = hasHit || scaleDetector.isInProgress
        forwardPointerEvent(event)
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
        resumeRendering()
    }

    override fun onDetachedFromWindow() {
        frameScheduler.pause()
        if (surfaceAttached) {
            clearDeclaredFrameRate(holder.surface)
        }
        super.onDetachedFromWindow()
    }

    // Rust reports "no further frame needed" for a frame that wgpu refused because
    // the surface was occluded, which stops the loop. Becoming visible again is the
    // signal that the occlusion is over, so the loop is re-armed here.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            resumeRendering()
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) {
            resumeRendering()
        }
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
        // The same display density `onMeasure` sizes this view with: the surface
        // holds `density` physical pixels per logical unit, and a move to a
        // display of another density is picked up on the next frame.
        val needsRedraw = NativeBindings.waterui_gpu_surface_render(
            statePtr = statePtr,
            width = surfaceWidth,
            height = surfaceHeight,
            scale = resources.displayMetrics.density
        )
        if (consumedDoubleTap) {
            doubleTap = false
            pushInput()
        }
        updateFrameRateDeclaration(continuous = needsRedraw)
        finishSurfaceRedraw()
        return needsRedraw
    }

    private fun finishSurfaceRedraw() {
        while (surfaceRedrawCompletions.isNotEmpty()) {
            surfaceRedrawCompletions.removeFirst().run()
        }
    }

    /**
     * Reports the touch as a pointer event to a GPU view that takes its own input.
     *
     * Positions are logical, surface-local points — the vocabulary's contract —
     * while the pointer snapshot beside it stays in the physical pixels its own
     * consumers already read.
     */
    private fun forwardPointerEvent(event: MotionEvent) {
        val sink = inputSink ?: return
        val density = resources.displayMetrics.density
        val x = (event.x / density).toDouble()
        val y = (event.y / density).toDouble()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                showSoftKeyboard()
                sink.send(kind = WuiSurfaceInputEventKind.PointerMove, x = x, y = y)
                sink.send(
                    kind = WuiSurfaceInputEventKind.PointerButton,
                    x = x,
                    y = y,
                    pressed = true
                )
            }
            MotionEvent.ACTION_MOVE ->
                sink.send(kind = WuiSurfaceInputEventKind.PointerMove, x = x, y = y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                sink.send(
                    kind = WuiSurfaceInputEventKind.PointerButton,
                    x = x,
                    y = y,
                    pressed = false
                )
        }
    }

    /** Raises the software keyboard for a GPU view that composes its own text. */
    private fun showSoftKeyboard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowInsetsController?.show(WindowInsets.Type.ime())
            return
        }
        val manager = context.getSystemService(InputMethodManager::class.java) ?: return
        manager.showSoftInput(this, 0)
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

    /**
     * Ends the gesture while preserving its final pan and pinch values, which
     * the renderer needs for the settling frame.
     */
    private fun releaseActiveInput() {
        hasPointerPosition = false
        hasHit = false
        hasPinchCenter = false
        gestureActive = false
    }

    private fun disposeNativeState() {
        mainHandler.removeCallbacks(redrawRequest)
        frameScheduler.dispose()
        if (surfaceAttached) {
            NativeBindings.waterui_gpu_surface_detach(statePtr)
            surfaceAttached = false
        }
        inputSink?.invalidate()
        inputSink = null
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

    /**
     * Keeps the declared frame rate in step with the renderer's actual cadence.
     *
     * The renderer's cadence is not known statically: it reports per frame whether
     * it wants another one. Declaring the display's maximum refresh rate
     * unconditionally would ask the system for a high-refresh mode for a surface
     * that may draw once and stop, so the declaration is made only while the
     * renderer keeps asking for frames and is dropped as soon as it goes idle.
     */
    private fun updateFrameRateDeclaration(continuous: Boolean) {
        if (continuous == frameRateDeclared) {
            return
        }
        val surface = holder.surface
        check(surface.isValid) { "GpuSurface rendered a frame into an invalid surface" }
        if (continuous) {
            requestMaximumFrameRate(surface)
        } else {
            clearFrameRate(surface)
        }
        frameRateDeclared = continuous
    }

    /**
     * Drops a live frame-rate declaration. A declaration belongs to the surface, so
     * one made against a surface that Android has already torn down is gone with it
     * and only the local flag has to be cleared.
     */
    private fun clearDeclaredFrameRate(surface: Surface) {
        if (!frameRateDeclared) {
            return
        }
        frameRateDeclared = false
        if (surface.isValid) {
            clearFrameRate(surface)
        }
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

    // An HDR-capable *screen* is not evidence the *content* is HDR: a renderer
    // that never declared a preference is SDR content, and forcing it into an
    // RGBA_F16 swapchain doubles buffer memory for zero visual gain — and on
    // Pixel 9 gralloc rejects the F16 composer-overlay allocation outright,
    // losing the Vulkan device. HDR is therefore opt-in: declared by the
    // renderer or inherited from an ancestor's dynamic-range scope, never
    // inferred from display capability.
    private fun resolvedRendererHdrPreference(): Boolean =
        if (hasHdrPreference) {
            prefersHdr
        } else {
            inheritedDynamicRange() == SurfaceDynamicRange.HIGH
        }

    private fun frozenRendererHdrPreference(): Boolean = rendererPrefersHdr
        ?: resolvedRendererHdrPreference().also { rendererPrefersHdr = it }

    /**
     * Selects a surface format whose alpha channel matches what Rust negotiates.
     *
     * `waterui_gpu_surface_attach` (`ffi/src/components/visual/gpu_surface.rs`)
     * takes the first composite alpha mode the swapchain reports in the order
     * `PreMultiplied`, `PostMultiplied`, `Inherit`, `Opaque`, so the renderer writes
     * premultiplied alpha whenever the surface has an alpha channel at all.
     * `PixelFormat.OPAQUE` drops that channel, which leaves translucent renderer
     * output composited against nothing, so the SDR path asks for `TRANSLUCENT`
     * (RGBA_8888). `RGBA_F16` is itself a four-channel format, so the HDR path
     * already agrees with the same negotiation.
     */
    private fun configureRendererSurfaceFormat() {
        val format = if (frozenRendererHdrPreference()) {
            PixelFormat.RGBA_F16
        } else {
            PixelFormat.TRANSLUCENT
        }
        holder.setFormat(format)
    }

    private fun configurePresentationDynamicRange() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            setDesiredHdrHeadroom(if (frozenRendererHdrPreference()) 0f else 1f)
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
