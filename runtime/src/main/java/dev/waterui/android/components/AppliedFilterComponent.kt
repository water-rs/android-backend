package dev.waterui.android.components

import android.content.Context
import android.view.Choreographer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.waterui.android.runtime.AppliedFilterStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataAppliedFilterTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_applied_filter_id().toTypeId()
}

/**
 * AppliedFilter metadata renderer.
 *
 * Captures the child view into an AHardwareBuffer and applies a Rust/wgpu filter pipeline,
 * rendering the filtered result into a SurfaceView.
 */
@Suppress("UNUSED_PARAMETER")
private val metadataAppliedFilterRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_applied_filter(node.rawPtr)

    if (metadata.contentPtr == 0L || metadata.filterPtr == 0L) {
        error("AppliedFilter metadata missing content/filter pointers")
    }

    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    val container = AppliedFilterView(context, metadata, child).apply {
        setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    container
}

private class AppliedFilterView(
    context: Context,
    private val data: AppliedFilterStruct,
    private val childView: View
) : FrameLayout(context), SurfaceHolder.Callback, Choreographer.FrameCallback {

    private var statePtr: Long = 0L
    private var isRendering = false
    private var isReady = false

    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    private val outputSurfaceView: SurfaceView
    private val captureHost: FrameLayout
    private val capturer: HardwareBufferCapturer

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        captureHost = CaptureHostLayout(context).also { host ->
            host.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            host.addView(
                childView,
                LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT
                )
            )
            addView(host)
        }
        capturer = HardwareBufferCapturer(childView)

        outputSurfaceView = SurfaceView(context).also { surfaceView ->
            surfaceView.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
            surfaceView.holder.addCallback(this)
            surfaceView.setZOrderOnTop(true)
            addView(surfaceView)
        }

        disposeWith { close() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        capturer.onSizeChanged(width, height)

        if (statePtr == 0L) {
            statePtr = NativeBindings.waterui_applied_filter_init(
                data.contentPtr,
                data.filterPtr,
                holder.surface,
                width,
                height
            )
            if (statePtr == 0L) {
                error("AppliedFilter init failed")
            }

            // Setup is async from native's POV; Rust calls the callback when ready.
            NativeBindings.waterui_applied_filter_setup(statePtr) {
                isReady = true
                if (!isRendering) {
                    isRendering = true
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isRendering = false
        isReady = false
        close()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering || !isReady || statePtr == 0L) return

        capturer.capture()?.let { buffer ->
            val ok = NativeBindings.waterui_applied_filter_set_input_ahardwarebuffer(
                statePtr,
                buffer,
                surfaceWidth,
                surfaceHeight
            )
            if (!ok) {
                error("AppliedFilter: failed to set AHardwareBuffer input")
            }
        }

        val result = NativeBindings.waterui_applied_filter_render(statePtr, surfaceWidth, surfaceHeight)
        if (!result.success) {
            error("AppliedFilter render failed")
        }

        if (isRendering) {
            // Render continuously (child can change outside of filter animation).
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChild(childView, widthMeasureSpec, heightMeasureSpec)
        val w = childView.measuredWidth
        val h = childView.measuredHeight
        measureChild(
            outputSurfaceView,
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        captureHost.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        captureHost.layout(0, 0, right - left, bottom - top)
        outputSurfaceView.layout(0, 0, right - left, bottom - top)
    }

    private fun close() {
        capturer.close()
        if (statePtr != 0L) {
            NativeBindings.waterui_applied_filter_drop(statePtr)
            statePtr = 0L
        }
    }
}

internal fun RegistryBuilder.registerWuiAppliedFilter() {
    registerMetadata({ metadataAppliedFilterTypeId }, metadataAppliedFilterRenderer)
}

