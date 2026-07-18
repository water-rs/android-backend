package dev.waterui.android.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.annotation.Keep
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import java.util.concurrent.CompletableFuture

private val androidVideoSurfaceHostTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_android_video_surface_host_id().toTypeId()
}

private val androidVideoSurfaceHostRenderer = WuiRenderer { context, node, env, registry ->
    val host = NativeBindings.waterui_force_as_android_video_surface_host(node.rawPtr)
    val content = inflateAnyView(context, host.contentPtr, env, registry)
    AndroidVideoSurfaceHost(context, host.bridgePtr).apply {
        attachContent(content)
        setTag(TAG_STRETCH_AXIS, content.getWuiStretchAxis())
    }
}

@Keep
private class AndroidVideoSurfaceHost(
    context: Context,
    private val bridgePtr: Long
) : PassThroughFrameLayout(context), SurfaceHolder.Callback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearSurface = createSurfaceView(secure = false)
    private val protectedSurface = createSurfaceView(secure = true)
    private var request: SurfaceRequest? = null
    private var disposed = false

    private data class SurfaceRequest(
        val view: SurfaceView,
        val future: CompletableFuture<Surface>
    )

    private fun createSurfaceView(secure: Boolean) = SurfaceView(context).apply {
        setSecure(secure)
        setZOrderMediaOverlay(true)
        visibility = View.GONE
        holder.addCallback(this@AndroidVideoSurfaceHost)
    }

    init {
        NativeBindings.waterui_android_video_surface_host_attach(bridgePtr, this)
        disposeWith(::disposeNativeBridge)
    }

    fun attachContent(content: View) {
        check(childCount == 0) { "video surface host content may only be attached once" }
        addView(content)
        addSurfaceView(clearSurface)
        addSurfaceView(protectedSurface)
    }

    private fun addSurfaceView(surface: SurfaceView) {
        addView(
            surface,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    @Keep
    fun acquireVideoSurface(secure: Boolean): Surface {
        check(Looper.myLooper() !== Looper.getMainLooper()) {
            "video Surface acquisition must run on the decoder thread"
        }
        val pending = SurfaceRequest(
            if (secure) protectedSurface else clearSurface,
            CompletableFuture()
        )
        check(mainHandler.post { activateVideoSurface(pending) }) {
            "Android main looper rejected video Surface activation"
        }
        return pending.future.get()
    }

    @Keep
    fun deactivateVideoSurface() {
        check(mainHandler.post(::deactivateOnMainThread)) {
            "Android main looper rejected video Surface deactivation"
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        completeSurfaceRequest(holder)
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        require(width > 0 && height > 0) {
            "video Surface dimensions must be positive: ${width}x$height format=$format"
        }
        completeSurfaceRequest(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val pending = request?.takeIf { it.view.holder === holder } ?: return
        request = null
        if (!pending.future.isDone) {
            pending.future.completeExceptionally(
                IllegalStateException("video Surface was destroyed before activation")
            )
            return
        }
        NativeBindings.waterui_android_video_surface_host_surface_destroyed(bridgePtr)
    }

    private fun activateVideoSurface(pending: SurfaceRequest) {
        if (disposed) {
            pending.future.completeExceptionally(
                IllegalStateException("video surface host was disposed before activation")
            )
            return
        }
        check(request == null) { "video surface host already owns an active decoder" }
        request = pending
        pending.view.visibility = View.VISIBLE
        completeSurfaceRequest(pending.view.holder)
    }

    private fun completeSurfaceRequest(holder: SurfaceHolder) {
        val pending = request?.takeIf { it.view.holder === holder } ?: return
        if (holder.surface.isValid) {
            pending.future.complete(holder.surface)
        }
    }

    private fun deactivateOnMainThread() {
        request = null
        clearSurface.visibility = View.GONE
        protectedSurface.visibility = View.GONE
    }

    private fun disposeNativeBridge() {
        disposed = true
        request?.future?.completeExceptionally(
            IllegalStateException("video surface host was disposed during activation")
        )
        request = null
        clearSurface.holder.removeCallback(this)
        protectedSurface.holder.removeCallback(this)
        NativeBindings.waterui_android_video_surface_host_drop(bridgePtr)
    }
}

internal fun RegistryBuilder.registerWuiAndroidVideoSurfaceHost() {
    register({ androidVideoSurfaceHostTypeId }, androidVideoSurfaceHostRenderer)
}
