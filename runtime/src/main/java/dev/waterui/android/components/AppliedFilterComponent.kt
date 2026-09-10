package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Surface
import android.view.View
import androidx.annotation.RequiresApi
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val metadataAppliedFilterTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_applied_filter_id().toTypeId()
}

private const val APPLIED_FILTER_LOG_TAG = "WaterUI.AppliedFilter"

private val appliedFilterRenderer = WuiRenderer { context, node, env, registry ->
    if (Build.VERSION.SDK_INT < VIEW_CAPTURE_MINIMUM_SDK) {
        error(viewCaptureUnsupported("AppliedFilter"))
    } else {
        val metadata = NativeBindings.waterui_force_as_metadata_applied_filter(node.rawPtr)
        AppliedFilterView(context, metadata.filterPtr, metadata.contentPtr, env, registry)
    }
}

/**
 * A subtree drawn through a WaterUI filter chain.
 *
 * The filter owns both ends of the pipeline: it says how large its output is for
 * the input it is given, it sizes the capture texture, and it presents the
 * filtered result onto this host's surface. The capture in between is this
 * class's only contribution, and it is inherited from [CapturedSubtreeView].
 *
 * Filter compilation is asynchronous, so no frame is captured until
 * `appliedFilterIsReady` answers `true`; the native state wakes this view when
 * that happens.
 */
@RequiresApi(VIEW_CAPTURE_MINIMUM_SDK)
@SuppressLint("ViewConstructor")
internal class AppliedFilterView(
    context: Context,
    filterPtr: Long,
    contentPtr: Long,
    env: WuiEnvironment,
    registry: RenderRegistry
) : CapturedSubtreeView(context, APPLIED_FILTER_LOG_TAG) {
    private var statePtr = 0L
    private var outputWidth = 0
    private var outputHeight = 0

    override val requiresReadyBeforeCapture: Boolean get() = true

    init {
        statePtr = NativeBindings.waterui_applied_filter_create(
            owner = this,
            filterPtr = filterPtr,
            wuiEnvPtr = env.raw()
        )
        beginPresenting(attachFilteredContent(context, contentPtr, env, registry))
    }

    override fun attachNative(
        surface: Surface,
        inputWidth: Int,
        inputHeight: Int,
        prefersHdr: Boolean
    ) {
        NativeBindings.waterui_applied_filter_attach(
            statePtr = statePtr,
            surface = surface,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            prefersHdr = prefersHdr
        )
        // Compiling the filter chain runs on the main-thread executor and
        // finishes by waking this view through its redraw callback.
        NativeBindings.waterui_applied_filter_setup(statePtr)
    }

    override fun detachNative() = NativeBindings.waterui_applied_filter_detach(statePtr)

    override fun isNativeReady(): Boolean =
        NativeBindings.waterui_applied_filter_is_ready(statePtr)

    override fun captureFormat(): Int =
        when (val format = NativeBindings.waterui_applied_filter_capture_format(statePtr)) {
            CAPTURE_FORMAT_RGBA8 -> PixelFormat.RGBA_8888
            CAPTURE_FORMAT_RGBA16F -> PixelFormat.RGBA_F16
            else -> error("AppliedFilter reported an unknown capture format $format")
        }

    override fun beginFrame(inputWidth: Int, inputHeight: Int) {
        val size = NativeBindings.waterui_applied_filter_resolve_output_size(
            statePtr = statePtr,
            inputWidth = inputWidth,
            inputHeight = inputHeight
        )
        outputWidth = size[0]
        outputHeight = size[1]
        NativeBindings.waterui_applied_filter_prepare_capture(
            statePtr = statePtr,
            width = inputWidth,
            height = inputHeight
        )
    }

    override fun setCaptureBuffer(buffer: HardwareBuffer): Long =
        NativeBindings.waterui_applied_filter_set_capture_hardware_buffer(statePtr, buffer)

    @Suppress("LongParameterList") // A destination rectangle crosses the ABI flattened.
    override fun compositeNestedSurface(
        surfaceStatePtr: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Float
    ) = NativeBindings.waterui_applied_filter_composite_gpu_surface(
        statePtr = statePtr,
        surfaceStatePtr = surfaceStatePtr,
        x = x,
        y = y,
        width = width,
        height = height,
        scale = scale
    )

    override fun renderCapturedFrame(): Boolean {
        val needsAnotherFrame = NativeBindings.waterui_applied_filter_render(
            statePtr = statePtr,
            width = outputWidth,
            height = outputHeight
        )
        revealOutput()
        return needsAnotherFrame
    }

    override fun dropNative() {
        NativeBindings.waterui_applied_filter_drop(statePtr)
        statePtr = 0L
    }

    private companion object {
        /** `waterui_applied_filter_capture_format`'s two answers. */
        const val CAPTURE_FORMAT_RGBA8 = 0
        const val CAPTURE_FORMAT_RGBA16F = 1
    }
}

/**
 * Inflates the filtered subtree into this host and reports its stretch axis.
 *
 * `AppliedFilter` is metadata: it is transparent to layout, so the host measures
 * and stretches exactly as the subtree underneath it would have.
 */
private fun <T : PassThroughFrameLayout> T.attachFilteredContent(
    context: Context,
    contentPtr: Long,
    env: WuiEnvironment,
    registry: RenderRegistry
): View {
    attachMetadataContent(context, contentPtr, env, registry)
    return getChildAt(0)
}

internal fun RegistryBuilder.registerWuiAppliedFilter() {
    registerMetadata({ metadataAppliedFilterTypeId }, appliedFilterRenderer)
}
