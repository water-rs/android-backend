package dev.waterui.android.components

import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val videoPlayerTypeId: WuiTypeId by lazy { NativeBindings.waterui_video_player_id().toTypeId() }

/**
 * VideoPlayer component renderer.
 *
 * Uses WuiVideoTextureView for video playback with proper aspect ratio support.
 * VideoPlayer has native controls enabled.
 *
 * Aspect Ratio Modes:
 * - Fit (0): Video fits within bounds, maintaining aspect ratio (may show letterbox)
 * - Fill (1): Video scales to fill bounds completely, cropping excess (like iOS resizeAspectFill)
 * - Stretch (2): Video stretches to fill bounds, ignoring aspect ratio
 */
private val videoPlayerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_video_player(node.rawPtr)

    // Create the shared video view with native controls enabled
    val videoView = WuiVideoTextureView(
        context = context,
        aspectRatioMode = struct.aspectRatio,
        showControls = struct.showControls,
        loops = true // VideoPlayer loops by default
    )

    // Set up video source computed signal (Computed<Str> - a URL string)
    val sourceComputed = struct.sourcePtr.takeIf { it != 0L }?.let {
        WuiComputed.string(it, env)
    }

    // Set up volume binding
    val volumeBinding = struct.volumePtr.takeIf { it != 0L }?.let {
        WuiBinding.float(it, env)
    }

    var currentUrl: String? = null

    // Observe video source changes
    sourceComputed?.observe { url ->
        if (url != currentUrl && url.isNotEmpty()) {
            currentUrl = url
            videoView.setVideoUrl(url)
        }
    }

    // Observe volume changes
    volumeBinding?.observe { volume ->
        videoView.setVolume(volume)
    }

    // Clean up resources
    videoView.disposeWith {
        videoView.release()
    }
    sourceComputed?.let { videoView.disposeWith(it) }
    volumeBinding?.let { videoView.disposeWith(it) }

    videoView
}

internal fun RegistryBuilder.registerWuiVideoPlayer() {
    register({ videoPlayerTypeId }, videoPlayerRenderer)
}
