package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Surface
import androidx.annotation.RequiresApi
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.ViewEffectStruct
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.inflateAnyView

private val viewEffectTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_view_effect_id().toTypeId()
}

private const val VIEW_EFFECT_LOG_TAG = "WaterUI.ViewEffect"

private val viewEffectRenderer = WuiRenderer { context, node, env, registry ->
    if (Build.VERSION.SDK_INT < VIEW_CAPTURE_MINIMUM_SDK) {
        error(viewCaptureUnsupported("ViewEffect"))
    } else {
        val effect = NativeBindings.waterui_force_as_view_effect(node.rawPtr)
        ViewEffectView(context, effect, env, registry)
    }
}

/**
 * A subtree handed to a WaterUI effect renderer as a GPU texture.
 *
 * Unlike a filter, an effect has no setup it can do before it has seen an input:
 * its input format is whatever the first captured buffer turns out to be. So the
 * first frame hands a buffer in, which starts the effect's asynchronous setup,
 * and the frame is presented once `viewEffectIsReady` answers `true` — the
 * native state wakes this view when it does.
 *
 * The effect's output size is fixed at construction and travels flattened across
 * the ABI, so the four fields go straight back to `viewEffectCreate` unread.
 */
@RequiresApi(VIEW_CAPTURE_MINIMUM_SDK)
@SuppressLint("ViewConstructor")
internal class ViewEffectView(
    context: Context,
    effect: ViewEffectStruct,
    env: WuiEnvironment,
    registry: RenderRegistry
) : CapturedSubtreeView(context, VIEW_EFFECT_LOG_TAG) {
    private var statePtr = 0L

    /** Whether an input already handed over is still waiting on effect setup. */
    private var frameAwaitingSetup = false

    override val requiresReadyBeforeCapture: Boolean get() = false

    init {
        statePtr = NativeBindings.waterui_view_effect_create(
            owner = this,
            effectPtr = effect.effectPtr,
            outputSizeKind = effect.outputSizeKind,
            outputWidth = effect.outputWidth,
            outputHeight = effect.outputHeight,
            outputScale = effect.outputScale,
            wuiEnvPtr = env.raw()
        )
        val content = inflateAnyView(context, effect.contentPtr, env, registry)
        addView(content)
        beginPresenting(content)
    }

    override fun attachNative(
        surface: Surface,
        inputWidth: Int,
        inputHeight: Int,
        prefersHdr: Boolean
    ) = NativeBindings.waterui_view_effect_attach(
        statePtr = statePtr,
        surface = surface,
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        prefersHdr = prefersHdr
    )

    override fun detachNative() = NativeBindings.waterui_view_effect_detach(statePtr)

    override fun isNativeReady(): Boolean = NativeBindings.waterui_view_effect_is_ready(statePtr)

    /**
     * The effect adopts whatever layout the buffer has, so the format is this
     * view's own presentation choice rather than something the effect dictates.
     */
    override fun captureFormat(): Int =
        if (prefersHdr()) PixelFormat.RGBA_F16 else PixelFormat.RGBA_8888

    override fun setCaptureBuffer(buffer: HardwareBuffer): Long =
        NativeBindings.waterui_view_effect_set_input_hardware_buffer(statePtr, buffer)

    @Suppress("LongParameterList") // A destination rectangle crosses the ABI flattened.
    override fun compositeNestedSurface(
        surfaceStatePtr: Long,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        scale: Float
    ) = NativeBindings.waterui_view_effect_composite_gpu_surface(
        statePtr = statePtr,
        surfaceStatePtr = surfaceStatePtr,
        x = x,
        y = y,
        width = width,
        height = height,
        scale = scale
    )

    override fun renderCapturedFrame(): Boolean {
        if (!isNativeReady()) {
            // The buffer just handed over started the effect's setup; its redraw
            // callback presents this same input once that setup completes.
            frameAwaitingSetup = true
            return false
        }
        return presentEffectFrame()
    }

    override fun onNativeRedraw() {
        if (frameAwaitingSetup && isNativeReady()) {
            frameAwaitingSetup = false
            if (presentEffectFrame()) {
                requestFrame()
            }
            return
        }
        super.onNativeRedraw()
    }

    override fun dropNative() {
        NativeBindings.waterui_view_effect_drop(statePtr)
        statePtr = 0L
    }

    private fun presentEffectFrame(): Boolean {
        val needsAnotherFrame = NativeBindings.waterui_view_effect_render(statePtr)
        revealOutput()
        return needsAnotherFrame
    }
}

internal fun RegistryBuilder.registerWuiViewEffect() {
    register({ viewEffectTypeId }, viewEffectRenderer)
}
