package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.AttrRes
import androidx.annotation.RequiresApi
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.SurfaceDynamicRange
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inheritedSurfaceDynamicRange

/**
 * The API level the view-capture path needs.
 *
 * `Image.getFence()` — the GPU-completion signal that says when a captured frame
 * is finished and its `AHardwareBuffer` may be read — arrives in API 33. Nothing
 * below it degrades into a working picture, so a filter or effect on an older
 * device fails fast rather than presenting an unfiltered or empty subtree.
 */
internal const val VIEW_CAPTURE_MINIMUM_SDK = Build.VERSION_CODES.TIRAMISU

/**
 * What a device below [VIEW_CAPTURE_MINIMUM_SDK] is told when it meets one of
 * the views that reads its own subtree's pixels.
 *
 * @param component the WaterUI view whose implementation needs the capture path.
 */
internal fun viewCaptureUnsupported(component: String): String =
    "$component reads the rendered pixels of its subtree, which needs " +
        "Image.getFence() from Android $VIEW_CAPTURE_MINIMUM_SDK " +
        "(this device runs Android ${Build.VERSION.SDK_INT})"

/**
 * Renders one native view subtree into `AHardwareBuffer`s the GPU can read.
 *
 * The subtree is recorded into a [RenderNode] and drawn by a private
 * [HardwareRenderer] whose output surface belongs to an [ImageReader]. Each
 * finished frame arrives as an [Image] whose hardware buffer is imported by
 * WaterUI's Vulkan device and copied into the filter pipeline's texture — the
 * whole path stays on the GPU, with no readback.
 *
 * Frames are collected on a private capture thread, because the wait for a
 * frame's GPU fence must never happen on the main thread. The captured image is
 * handed back on [viewHandler]'s thread, which is the view's own thread and the
 * only one allowed to call the `appliedFilter*` / `viewEffect*` entry points.
 *
 * @param logTag the tag this capture's diagnostics are logged under.
 * @param viewHandler the owner view's thread, where captured images are delivered.
 * @param onImageCaptured receives each captured image; the receiver owns it and
 *   must close it once the GPU has finished reading its buffer.
 */
@RequiresApi(VIEW_CAPTURE_MINIMUM_SDK)
internal class HardwareViewCapture(
    private val logTag: String,
    viewHandler: Handler,
    onImageCaptured: (Image) -> Unit
) : AutoCloseable {
    private val captureThread = HandlerThread("WaterUI.ViewCapture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    /** The node the renderer draws, sized to the capture buffer. */
    private val rootNode = RenderNode("WaterUI.ViewCapture")

    /**
     * The node the subtree is recorded into.
     *
     * The subtree's own render properties — its opacity and transform, which a
     * WaterUI modifier around it may be driving — belong to this node rather
     * than to the recording, because in the live view tree they are applied by
     * the parent that draws the subtree, and here that parent is this capture.
     */
    private val contentNode = RenderNode("WaterUI.ViewCaptureContent")

    private val renderer = HardwareRenderer().apply {
        setContentRoot(rootNode)
        // A captured subtree is composited over whatever the filter draws it
        // onto, so the parts it leaves untouched must stay transparent.
        setOpaque(false)
    }

    private var imageReader: ImageReader? = null
    private var shadowAlphas: FloatArray? = null

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        val image = checkNotNull(reader.acquireLatestImage()) {
            "View capture was told a frame was ready and then handed none"
        }
        awaitCaptureFence(image)
        check(viewHandler.post { onImageCaptured(image) }) {
            "The owner view's looper rejected a captured frame"
        }
    }

    /**
     * Sizes the capture buffers, allocating them when the size or format changes.
     *
     * @param width capture width in physical pixels.
     * @param height capture height in physical pixels.
     * @param format the [PixelFormat] the filter pipeline reads its input in.
     */
    fun configure(width: Int, height: Int, format: Int) {
        require(width > 0 && height > 0) { "View capture size must be positive: ${width}x$height" }
        val existing = imageReader
        if (existing != null && existing.holds(width, height, format)) {
            return
        }
        existing?.close()
        val reader = ImageReader.newInstance(
            width,
            height,
            format,
            CAPTURE_BUFFER_COUNT,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
        )
        reader.setOnImageAvailableListener(imageListener, captureHandler)
        renderer.setSurface(reader.surface)
        imageReader = reader
        Log.d(logTag, "view capture buffers are ${width}x$height format=$format")
    }

    /**
     * Draws [content] into the next capture buffer.
     *
     * @param content the subtree to render; it must already be laid out.
     * @param frameTimeNanos the vsync this frame belongs to, so animations inside
     *   the subtree advance on the same clock as the rest of the window.
     * @return whether a frame was submitted. `false` means the renderer dropped
     *   it — no image will arrive for it, and the caller schedules another.
     */
    fun capture(content: View, frameTimeNanos: Long): Boolean {
        val reader = checkNotNull(imageReader) { "View capture was asked to draw before configure" }
        configureLightSource(content)
        recordSubtree(content, reader.width, reader.height)
        val result = renderer.createRenderRequest()
            .setVsyncTime(frameTimeNanos)
            .syncAndDraw()
        check(result and HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND == 0) {
            "View capture lost the ImageReader surface it owns"
        }
        check(result and HardwareRenderer.SYNC_CONTEXT_IS_STOPPED == 0) {
            "View capture drew into a stopped renderer"
        }
        return result and HardwareRenderer.SYNC_FRAME_DROPPED == 0
    }

    override fun close() {
        renderer.stop()
        renderer.destroy()
        // Images still held by the consumer are closed with the reader; the
        // consumer's own later `close()` on one of them is a documented no-op.
        imageReader?.close()
        imageReader = null
        rootNode.discardDisplayList()
        contentNode.discardDisplayList()
        captureThread.quitSafely()
    }

    /**
     * Records the subtree, with its own opacity and transform on the inner node.
     */
    private fun recordSubtree(content: View, width: Int, height: Int) {
        contentNode.setPosition(0, 0, width, height)
        contentNode.alpha = content.alpha
        contentNode.translationX = content.translationX
        contentNode.translationY = content.translationY
        contentNode.rotationX = content.rotationX
        contentNode.rotationY = content.rotationY
        contentNode.rotationZ = content.rotation
        contentNode.scaleX = content.scaleX
        contentNode.scaleY = content.scaleY
        contentNode.pivotX = content.pivotX
        contentNode.pivotY = content.pivotY
        contentNode.cameraDistance = content.cameraDistance
        val contentCanvas = contentNode.beginRecording(width, height)
        try {
            content.draw(contentCanvas)
        } finally {
            contentNode.endRecording()
        }

        rootNode.setPosition(0, 0, width, height)
        val rootCanvas = rootNode.beginRecording(width, height)
        try {
            rootCanvas.drawRenderNode(contentNode)
        } finally {
            rootNode.endRecording()
        }
    }

    /**
     * Places the shadow light where the window places it.
     *
     * A subtree drawn by a renderer of its own gets no light source from the
     * window, and without one every elevation shadow inside it silently
     * disappears from the capture. The position and the alphas are the ones the
     * framework itself resolves for this device and theme, read back through the
     * same theme attributes the window's own renderer is configured from, and
     * translated into the captured subtree's coordinates.
     */
    private fun configureLightSource(content: View) {
        val context = content.context
        val lightY = themeDimension(context, "lightY")
        val lightZ = themeDimension(context, "lightZ")
        val lightRadius = themeDimension(context, "lightRadius")
        val location = IntArray(2)
        content.getLocationOnScreen(location)
        val windowManager = content.context.getSystemService(WindowManager::class.java)
        val displayBounds = checkNotNull(windowManager) {
            "View capture cannot place its shadow light without a WindowManager"
        }.maximumWindowMetrics.bounds
        renderer.setLightSourceGeometry(
            displayBounds.width() / 2f - location[0],
            lightY - location[1],
            lightZ,
            lightRadius
        )
        val alphas = shadowAlphas ?: resolveShadowAlphas(content.context).also { shadowAlphas = it }
        renderer.setLightSourceAlpha(alphas[0], alphas[1])
    }

    /**
     * Waits for the frame's own GPU work to finish, on the capture thread.
     *
     * An image with no fence is already complete: the producer signalled it
     * before handing the buffer over.
     */
    private fun awaitCaptureFence(image: Image) {
        image.fence.use { fence ->
            if (!fence.isValid) {
                return
            }
            check(fence.awaitForever()) { "A captured frame's GPU fence never signalled" }
        }
    }

    private companion object {
        /**
         * One buffer for the frame being read by the filter pipeline and one for
         * the frame being drawn into. Only ever one capture is in flight, so a
         * third would never be filled.
         */
        const val CAPTURE_BUFFER_COUNT = 2

        /** Whether this reader already hands out buffers of exactly this shape. */
        fun ImageReader.holds(width: Int, height: Int, format: Int): Boolean =
            this.width == width && this.height == height && imageFormat == format

        /**
         * The dimension the platform theme attribute `android:[name]` resolves to.
         *
         * `lightY`, `lightZ` and `lightRadius` are the private `Lighting` attributes
         * every platform theme sets from `light_y`, `light_z` and `light_radius`;
         * the window's own renderer reads the same three, so the capture's light
         * lands where the window's does.
         */
        @SuppressLint("DiscouragedApi")
        fun themeDimension(context: Context, name: String): Float {
            val attribute = context.resources.getIdentifier(name, "attr", "android")
            check(attribute != 0) {
                "The Android platform attribute android:$name, which places shadow " +
                    "light, is missing from this device's resources"
            }
            val value = TypedValue()
            check(
                context.theme.resolveAttribute(attribute, value, true) &&
                    value.type == TypedValue.TYPE_DIMENSION
            ) {
                "The application theme resolves no dimension for android:$name, which " +
                    "every Android theme inherits from the platform"
            }
            return value.getDimension(context.resources.displayMetrics)
        }

        /** The theme's ambient and spot shadow alphas, in that order. */
        fun resolveShadowAlphas(context: Context): FloatArray = floatArrayOf(
            themeFloat(context, android.R.attr.ambientShadowAlpha, "ambientShadowAlpha"),
            themeFloat(context, android.R.attr.spotShadowAlpha, "spotShadowAlpha")
        )

        /** The float [attribute] resolves to in [context]'s theme. */
        fun themeFloat(context: Context, @AttrRes attribute: Int, name: String): Float {
            val value = TypedValue()
            check(
                context.theme.resolveAttribute(attribute, value, true) &&
                    value.type == TypedValue.TYPE_FLOAT
            ) {
                "The application theme resolves no float for $name, which every " +
                    "Android theme inherits from the platform"
            }
            return value.float
        }
    }
}

/**
 * A WaterUI view whose picture is its own subtree, filtered on the GPU.
 *
 * The subtree stays in the view tree — laid out, animating, focusable and
 * touchable — and is captured into an `AHardwareBuffer` every frame; the
 * filtered result is presented on a [SurfaceView] laid over it. From the moment
 * the first filtered frame is on screen the subtree stops being painted by this
 * container, so the filtered picture is the only one, and the two are never both
 * visible.
 *
 * Everything below this line is shared by `AppliedFilter` and `ViewEffect`; the
 * two differ only in how they resolve their output size and when their native
 * state becomes ready, which each subclass answers for itself.
 *
 * @param logTag the tag this host's diagnostics are logged under.
 */
@RequiresApi(VIEW_CAPTURE_MINIMUM_SDK)
@SuppressLint("ViewConstructor")
internal abstract class CapturedSubtreeView(
    context: Context,
    private val logTag: String
) : PassThroughFrameLayout(context),
    SurfaceHolder.Callback2,
    Choreographer.FrameCallback,
    NestedSurfaceHost {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val outputView = SurfaceView(context)
    private lateinit var capturedContent: View
    private var capture: HardwareViewCapture? = null
    private var surfaceAttached = false
    private var captureInFlight = false
    private var captureRequestedWhileInFlight = false
    private var captureImageFormat = 0
    private var frameTimeNanos = 0L
    private var outputRevealed = false
    private var disposed = false
    private var resolvedPrefersHdr: Boolean? = null
    private val frameScheduler = GpuFrameScheduler(
        postFrame = { choreographer.postFrameCallback(this) },
        cancelFrame = { choreographer.removeFrameCallback(this) },
        renderFrame = ::renderFrame
    )
    private val redrawRequest = Runnable { onNativeRedraw() }

    /**
     * The GPU surfaces inside the captured subtree whose layers this host holds.
     *
     * Rebuilt from the tree on every captured frame, so a surface that appears
     * or leaves is picked up without anything having to announce it.
     */
    private val suppressedSurfaces = mutableSetOf<GpuSurfaceView>()
    private val hostLocation = IntArray(2)
    private val surfaceLocation = IntArray(2)

    /** Whether the native state refuses to capture until its setup has finished. */
    protected abstract val requiresReadyBeforeCapture: Boolean

    /** Hands the presentation surface to the native state. */
    protected abstract fun attachNative(
        surface: Surface,
        inputWidth: Int,
        inputHeight: Int,
        prefersHdr: Boolean
    )

    /** Releases the presentation surface and every buffer imported for it. */
    protected abstract fun detachNative()

    /** Whether the native state's asynchronous setup has finished. */
    protected abstract fun isNativeReady(): Boolean

    /** The [PixelFormat] the native state reads its captured input in. */
    protected abstract fun captureFormat(): Int

    /**
     * Prepares the native state for a frame captured at the given input size.
     */
    protected open fun beginFrame(inputWidth: Int, inputHeight: Int) = Unit

    /**
     * Hands one captured buffer to the native state.
     *
     * @return the capture fence, which the caller consumes exactly once.
     */
    protected abstract fun setCaptureBuffer(buffer: HardwareBuffer): Long

    /**
     * Draws one GPU surface nested in the captured subtree into the capture.
     *
     * @param surfaceStatePtr the nested surface's own native state.
     * @param x left edge inside the captured content, in pixels.
     * @param y top edge inside the captured content, in pixels.
     * @param width the surface's width in pixels.
     * @param height the surface's height in pixels.
     * @param scale pixels per logical unit, which the surface renders at.
     */
    @Suppress("LongParameterList") // A destination rectangle crosses the ABI flattened.
    protected abstract fun compositeNestedSurface(
        surfaceStatePtr: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Float
    )

    /**
     * Presents the frame whose buffer was just handed over.
     *
     * @return whether the native state wants another frame after this one.
     */
    protected abstract fun renderCapturedFrame(): Boolean

    /** Releases the native state itself, after it has been detached. */
    protected abstract fun dropNative()

    /**
     * Starts presenting [content] filtered.
     *
     * Called from the subclass's own initialisation, once its native state
     * exists, because the surface callbacks this installs can run as soon as the
     * host is laid out.
     */
    protected fun beginPresenting(content: View) {
        capturedContent = content
        addView(
            outputView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        outputView.holder.addCallback(this)
        disposeWith(::disposePresentation)
    }

    /**
     * Whether this subtree presents in high dynamic range.
     *
     * Settled once, when the view enters a window, and unchanged after: the
     * presentation surface's format is chosen from it and the native state is
     * attached against that format.
     */
    protected fun prefersHdr(): Boolean = checkNotNull(resolvedPrefersHdr) {
        "$logTag was asked for its dynamic range before it entered a window"
    }

    /** Asks for another frame; the native state calls this when it is dirty. */
    protected fun requestFrame() {
        frameScheduler.requestFrame()
    }

    /**
     * Shows the filtered output and stops painting the subtree it came from.
     *
     * Held back until the first filtered frame is actually on screen, so the
     * subtree is never replaced by an empty surface.
     */
    protected fun revealOutput() {
        if (outputRevealed) {
            return
        }
        outputRevealed = true
        Log.d(logTag, "first filtered frame presented")
        invalidate()
    }

    /**
     * Called from the native redraw callback, on the view's own thread.
     *
     * The base behaviour asks for a frame; a state that has deferred work
     * waiting on its own readiness finishes that work first.
     */
    protected open fun onNativeRedraw() {
        if (disposed) {
            return
        }
        requestFrame()
    }

    /**
     * Called from Rust's redraw callback, which may arrive on any thread.
     *
     * Always delivered asynchronously, never inline: a render call holds an
     * exclusive borrow of the native state for its whole duration and the
     * pipeline may ask for a redraw from inside it, so running the request
     * inline would re-enter the FFI while that borrow is live.
     */
    @Keep
    fun requestNativeRedraw() {
        check(mainHandler.post(redrawRequest)) {
            "Android main looper rejected a view-capture redraw request"
        }
    }

    /**
     * Asks for another captured frame on behalf of a surface this host suppresses.
     */
    override fun requestCaptureFrame() {
        if (disposed) {
            return
        }
        requestFrame()
    }

    /**
     * Draws every GPU surface inside the captured subtree into the capture.
     *
     * HWUI records a `SurfaceView` as a cleared hole, so the buffer that just
     * arrived has nothing where a nested `GpuSurface` belongs. Worse, the
     * surface's own layer is positioned from the render node the capture drew,
     * so it keeps presenting over the window at a position taken from the
     * capture's coordinates rather than the window's. Both are answered the
     * same way: the layer is hidden and the surface renders one frame straight
     * into the capture, at the rectangle it occupies inside the captured
     * content.
     */
    private fun compositeNestedSurfaces() {
        val nested = collectNestedSurfaces(capturedContent)
        for (surface in suppressedSurfaces.toList()) {
            if (surface !in nested) {
                surface.releaseLayerFor(this)
                suppressedSurfaces.remove(surface)
            }
        }
        if (nested.isEmpty()) {
            return
        }
        capturedContent.getLocationInWindow(hostLocation)
        val scale = resources.displayMetrics.density
        for (surface in nested) {
            if (!surface.isReadyForCapture()) {
                // Nothing has been drawn into its swapchain yet, so it is
                // showing nothing either; the frame that makes it ready wakes
                // this host through its own redraw callback.
                continue
            }
            surface.suppressLayerFor(this)
            suppressedSurfaces.add(surface)
            surface.getLocationInWindow(surfaceLocation)
            compositeNestedSurface(
                surfaceStatePtr = surface.nativeStatePtr,
                x = surfaceLocation[0] - hostLocation[0],
                y = surfaceLocation[1] - hostLocation[1],
                width = surface.width,
                height = surface.height,
                scale = scale
            )
        }
    }

    /**
     * Returns every GPU surface under [view] that belongs to its picture, in the
     * order the subtree draws them.
     *
     * A surface this host already suppresses is `INVISIBLE` by its own doing and
     * still belongs; one the application hid does not, and neither does anything
     * under a container that is not being drawn.
     */
    private fun collectNestedSurfaces(view: View): List<GpuSurfaceView> {
        val found = mutableListOf<GpuSurfaceView>()
        collectNestedSurfacesInto(view, found)
        return found
    }

    private fun collectNestedSurfacesInto(view: View, found: MutableList<GpuSurfaceView>) {
        if (view is GpuSurfaceView) {
            if (view.participatesInCapture) {
                found.add(view)
            }
            return
        }
        if (view.visibility != VISIBLE || view !is ViewGroup) {
            return
        }
        for (index in 0 until view.childCount) {
            collectNestedSurfacesInto(view.getChildAt(index), found)
        }
    }

    /** Returns every nested surface's presentation to its own layer. */
    private fun releaseNestedSurfaces() {
        for (surface in suppressedSurfaces) {
            surface.releaseLayerFor(this)
        }
        suppressedSurfaces.clear()
    }

    /**
     * Stops painting the captured subtree once its filtered picture is showing.
     *
     * Only the painting stops. The subtree keeps its `VISIBLE` visibility, so it
     * still lays out, takes touches, holds focus and reaches accessibility; and
     * the capture draws it explicitly, which this does not intercept.
     */
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        if (outputRevealed && child === capturedContent) {
            return false
        }
        return super.drawChild(canvas, child, drawingTime)
    }

    override fun surfaceCreated(_holder: SurfaceHolder) {
        check(!surfaceAttached) {
            "$logTag received surfaceCreated while a previous surface is still attached"
        }
    }

    /**
     * Attaches the native presentation target.
     *
     * `SurfaceView` always delivers `surfaceChanged` right after
     * `surfaceCreated`, and only then is there a surface to hand over. The size
     * handed over is this host's, not the one reported here: the reported size is
     * the presentation buffer's, which the filter owns and sizes from its own
     * resolved output, while what is captured is always the subtree at its laid
     * out size.
     */
    override fun surfaceChanged(holder: SurfaceHolder, _format: Int, _width: Int, _height: Int) {
        attachSurfaceIfNeeded(holder)
        resumeRendering()
    }

    override fun surfaceRedrawNeeded(_holder: SurfaceHolder) {
        resumeRendering()
    }

    override fun surfaceDestroyed(_holder: SurfaceHolder) {
        Log.d(logTag, "surfaceDestroyed attached=$surfaceAttached")
        frameScheduler.pause()
        if (surfaceAttached) {
            detachNative()
            surfaceAttached = false
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        this.frameTimeNanos = frameTimeNanos
        frameScheduler.onFrame()
    }

    override fun onAttachedToWindow() {
        configurePresentation()
        super.onAttachedToWindow()
        resumeRendering()
    }

    /**
     * Settles the presentation surface's dynamic range, z-order and format.
     *
     * Done on attach rather than at construction, because the dynamic-range
     * scope this reads is declared by an ancestor and there is no ancestor until
     * this view is in a tree; and done before the children are dispatched their
     * own attach, because the output surface is created from that dispatch and
     * both settings have to be in place by then.
     *
     * As with a GPU surface, an HDR-capable screen is not evidence that the
     * content is HDR: the answer comes from an enclosing dynamic-range scope, or
     * the presentation is standard range.
     */
    private fun configurePresentation() {
        if (resolvedPrefersHdr != null) {
            return
        }
        val hdr = inheritedSurfaceDynamicRange() == SurfaceDynamicRange.HIGH
        resolvedPrefersHdr = hdr
        // The same z-order compromise `GpuSurfaceView` documents at length: a
        // surface is either above the window or below it, never in tree order,
        // and translucent filtered output needs to blend over the UI.
        if (hdr) {
            outputView.setZOrderMediaOverlay(true)
        } else {
            outputView.setZOrderOnTop(true)
        }
        // The same negotiation `GpuSurfaceView` documents: WaterUI renders
        // premultiplied alpha whenever the surface carries an alpha channel, and
        // a filtered subtree is translucent wherever it did not draw.
        outputView.holder.setFormat(if (hdr) PixelFormat.RGBA_F16 else PixelFormat.TRANSLUCENT)
    }

    override fun onDetachedFromWindow() {
        frameScheduler.pause()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            resumeRendering()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // The capture texture is sized per frame from this host's bounds, so a
        // resize only has to ask for the frame that re-sizes it.
        resumeRendering()
    }

    private fun attachSurfaceIfNeeded(holder: SurfaceHolder) {
        if (surfaceAttached || disposed) {
            return
        }
        val inputWidth = width
        val inputHeight = height
        require(inputWidth > 0 && inputHeight > 0) {
            "$logTag cannot capture a subtree laid out at ${inputWidth}x$inputHeight"
        }
        attachNative(holder.surface, inputWidth, inputHeight, prefersHdr())
        surfaceAttached = true
        captureImageFormat = captureFormat()
        Log.d(logTag, "attached ${inputWidth}x$inputHeight format=$captureImageFormat")
    }

    private fun resumeRendering() {
        if (!surfaceAttached || disposed) {
            return
        }
        frameScheduler.resume()
        requestFrame()
    }

    /**
     * Captures one frame of the subtree.
     *
     * The capture is asynchronous, so this never reports that another frame is
     * needed: the frame loop is re-armed when the captured image comes back.
     */
    private fun renderFrame(): Boolean {
        if (disposed || !surfaceAttached) {
            return false
        }
        if (requiresReadyBeforeCapture && !isNativeReady()) {
            // Setup completion fires the redraw callback, which asks again.
            return false
        }
        if (captureInFlight) {
            captureRequestedWhileInFlight = true
            return false
        }
        val inputWidth = width
        val inputHeight = height
        if (inputWidth <= 0 || inputHeight <= 0) {
            return false
        }
        beginFrame(inputWidth, inputHeight)
        val frameCapture = requireCapture()
        frameCapture.configure(inputWidth, inputHeight, captureImageFormat)
        captureInFlight = true
        if (!frameCapture.capture(capturedContent, frameTimeNanos)) {
            Log.d(logTag, "the renderer dropped a capture frame")
            captureInFlight = false
            return true
        }
        return false
    }

    /**
     * The renderer that captures this subtree, started on its first frame so a
     * filter that never presents costs no thread.
     */
    private fun requireCapture(): HardwareViewCapture = capture
        ?: HardwareViewCapture(logTag, mainHandler, ::onCapturedImage).also { capture = it }

    /** Hands one captured frame to the filter pipeline, on the view's thread. */
    private fun onCapturedImage(image: Image) {
        if (disposed || !surfaceAttached) {
            image.close()
            captureInFlight = false
            return
        }
        val buffer = checkNotNull(image.hardwareBuffer) {
            "$logTag captured a frame with no hardware buffer behind it"
        }
        val fence = buffer.use { setCaptureBuffer(it) }
        compositeNestedSurfaces()
        val needsAnotherFrame = renderCapturedFrame()
        NativeBindings.waterui_gpu_capture_fence_on_complete(fence) {
            // Rust's GPU completion thread: the buffer is finished with, so the
            // image may go back to the reader, but only the view's own thread
            // may touch the frame loop.
            check(mainHandler.post { finishCapturedImage(image, needsAnotherFrame) }) {
                "Android main looper rejected a finished view capture"
            }
        }
    }

    private fun finishCapturedImage(image: Image, needsAnotherFrame: Boolean) {
        image.close()
        captureInFlight = false
        if (disposed) {
            return
        }
        if (needsAnotherFrame || captureRequestedWhileInFlight) {
            captureRequestedWhileInFlight = false
            requestFrame()
        }
    }

    private fun disposePresentation() {
        disposed = true
        releaseNestedSurfaces()
        mainHandler.removeCallbacks(redrawRequest)
        frameScheduler.dispose()
        // Detaching first waits for the GPU and releases every imported buffer,
        // so the capture's own buffers are unreferenced by the time it closes.
        if (surfaceAttached) {
            detachNative()
            surfaceAttached = false
        }
        capture?.close()
        capture = null
        dropNative()
        outputView.holder.removeCallback(this)
    }
}
