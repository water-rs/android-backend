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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val androidVideoSurfaceHostTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_android_video_surface_host_id().toTypeId()
}

/** How long a decoder may take to hand back a Surface Android is tearing down. */
private const val SURFACE_RELEASE_TIMEOUT_MILLIS = 2_000L

/** How long the decoder thread waits for the host view to produce a Surface. */
private const val SURFACE_ACTIVATION_TIMEOUT_MILLIS = 5_000L

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

    /**
     * Latch a `surfaceDestroyed` on the main thread waits on until the decoder
     * acknowledges that it stopped using the Surface. Written on the main thread and
     * counted down from the decoder thread.
     */
    @Volatile
    private var pendingSurfaceRelease: CountDownLatch? = null

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
        // Reaching a fresh acquisition proves the decoder tore down whatever it was
        // feeding before, so it doubles as the release acknowledgement.
        acknowledgeSurfaceRelease()
        val pending = SurfaceRequest(
            if (secure) protectedSurface else clearSurface,
            CompletableFuture()
        )
        check(mainHandler.post { activateVideoSurface(pending) }) {
            "Android main looper rejected video Surface activation"
        }
        return awaitActivation(pending)
    }

    @Keep
    fun deactivateVideoSurface() {
        // Called from the decoder thread once it has released the Surface.
        acknowledgeSurfaceRelease()
        check(mainHandler.post(::deactivateOnMainThread)) {
            "Android main looper rejected video Surface deactivation"
        }
    }

    private fun awaitActivation(pending: SurfaceRequest): Surface = try {
        pending.future.get(SURFACE_ACTIVATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (timeout: TimeoutException) {
        throw IllegalStateException(
            "video surface host did not produce a Surface within ${SURFACE_ACTIVATION_TIMEOUT_MILLIS}ms",
            timeout
        )
    }

    private fun acknowledgeSurfaceRelease() {
        pendingSurfaceRelease?.countDown()
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

    /**
     * Android reclaims the Surface as soon as this returns, so it must not return
     * while a decoder still holds it — a `MediaCodec` writing into a reclaimed
     * Surface is a native use-after-free.
     *
     * `waterui_android_video_surface_host_surface_destroyed` is fire-and-forget: it
     * only queues a lifecycle event for the decoder thread. Until the FFI gains a
     * real acknowledgement (see the report accompanying this change), the closest
     * available signal is the decoder's next call back into this host —
     * `deactivateVideoSurface` when it shuts the pipeline down, or
     * `acquireVideoSurface` when it rebuilds one — both of which happen after it has
     * stopped feeding the destroyed Surface.
     */
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val pending = request?.takeIf { it.view.holder === holder } ?: return
        request = null
        if (!pending.future.isDone) {
            pending.future.completeExceptionally(
                IllegalStateException("video Surface was destroyed before activation")
            )
            return
        }
        val released = CountDownLatch(1)
        pendingSurfaceRelease = released
        NativeBindings.waterui_android_video_surface_host_surface_destroyed(bridgePtr)
        val acknowledged = released.await(SURFACE_RELEASE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        pendingSurfaceRelease = null
        check(acknowledged) {
            "video decoder did not release the destroyed Surface within ${SURFACE_RELEASE_TIMEOUT_MILLIS}ms"
        }
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
        // A disposed host owns no decoder, so nothing is left holding the Surface.
        acknowledgeSurfaceRelease()
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
