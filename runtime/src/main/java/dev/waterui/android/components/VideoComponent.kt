package dev.waterui.android.components

import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val videoTypeId: WuiTypeId by lazy { WatcherJni.videoId().toTypeId() }

/**
 * Video (raw) component renderer.
 *
 * Uses WuiVideoTextureView for video playback with proper aspect ratio support.
 * This is the raw video component - no native playback controls.
 * For video with controls, use VideoPlayer.
 *
 * Features:
 * - Reactive URL updates via Computed<String>
 * - Reactive volume control via Binding<f32>
 * - Aspect ratio modes: Fit, Fill, Stretch
 * - Loop support
 * - No native playback controls (raw video surface)
 */
private val videoRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsVideo(node.rawPtr)

    // Create the shared video view without native controls
    val videoView = WuiVideoTextureView(
        context = context,
        aspectRatioMode = struct.aspectRatio,
        showControls = false, // Raw video has no controls
        loops = struct.loops
    )

    // Set up source computed signal (Computed<Str> URL)
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

internal fun RegistryBuilder.registerWuiVideo() {
    register({ videoTypeId }, videoRenderer)
}
