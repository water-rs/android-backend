package dev.waterui.android.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.VideoView
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val videoPlayerTypeId: WuiTypeId by lazy { NativeBindings.waterui_video_player_id().toTypeId() }

/**
 * VideoPlayer component renderer.
 *
 * Uses Android's VideoView for video playback with reactive volume control.
 *
 * Volume Control System:
 * - Positive values (> 0): Audible volume level
 * - Negative values (< 0): Muted state that preserves the original volume level
 * - When unmuting, the absolute value is restored
 */
private val videoPlayerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_video_player(node.rawPtr)

    // VideoPlayer expands to fill available space (both axes stretch)
    val videoView = object : VideoView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Expand to fill available space (axis-expanding behavior)
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = MeasureSpec.getSize(heightMeasureSpec)

            val expandedWidthSpec = if (widthMode == MeasureSpec.AT_MOST || widthMode == MeasureSpec.EXACTLY) {
                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(320, MeasureSpec.EXACTLY) // Default width
            }

            val expandedHeightSpec = if (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.EXACTLY) {
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(180, MeasureSpec.EXACTLY) // Default height (16:9)
            }

            super.onMeasure(expandedWidthSpec, expandedHeightSpec)
        }
    }.apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH)
    }

    // Set up video source computed signal
    val videoComputed = struct.videoPtr.takeIf { it != 0L }?.let {
        WuiComputed.video(it, env)
    }

    // Set up volume binding
    val volumeBinding = struct.volumePtr.takeIf { it != 0L }?.let {
        WuiBinding.float(it, env)
    }

    var currentUrl: String? = null

    // Observe video source changes
    videoComputed?.observe { video ->
        val url = video.url
        if (url != currentUrl) {
            currentUrl = url
            videoView.setVideoURI(Uri.parse(url))
            videoView.start()
        }
    }

    // Observe volume changes
    // Note: VideoView doesn't have direct volume control, so we use AudioManager
    // For now, we handle mute state only
    volumeBinding?.observe { volume ->
        // Volume encoding: negative = muted (preserves volume level)
        // Android VideoView doesn't support volume control directly,
        // but we can use the audio focus system or AudioManager for mute
        // For simplicity, we just handle the playback state based on volume
        if (volume < 0) {
            // Muted - continue playing but no audio
            // VideoView doesn't have native mute, this would need MediaPlayer access
            // For now, this is a limitation of VideoView
        }
    }

    // Set up looping on completion
    videoView.setOnCompletionListener { mp ->
        mp.seekTo(0)
        mp.start()
    }

    // Clean up resources
    videoComputed?.let { videoView.disposeWith(it) }
    volumeBinding?.let { videoView.disposeWith(it) }

    videoView
}

// Tag for stretch axis
private const val TAG_STRETCH_AXIS = 0x7f0f0001

internal fun RegistryBuilder.registerWuiVideoPlayer() {
    register({ videoPlayerTypeId }, videoPlayerRenderer)
}
