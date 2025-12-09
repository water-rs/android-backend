package dev.waterui.android.components

import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.media.MediaPlayer
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.VideoStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import java.io.Closeable

private val videoTypeId: WuiTypeId by lazy { NativeBindings.waterui_video_id().toTypeId() }

// Tag for stretch axis
private const val TAG_STRETCH_AXIS = 0x7f0f0001

/**
 * Video (raw) component renderer.
 *
 * Uses SurfaceView + MediaPlayer for video playback without native controls.
 * This is the raw video component - for video with controls, use VideoPlayer.
 *
 * Features:
 * - Reactive URL updates via Computed<Video>
 * - Reactive volume control via Binding<f32>
 * - Aspect ratio modes: Fit, Fill, Stretch
 * - Loop support
 * - No native playback controls (raw video surface)
 */
private val videoRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_video(node.rawPtr)
    val loops = struct.loops

    // Set up source computed signal (uses video Computed which returns VideoStruct with url)
    val sourceComputed = struct.sourcePtr.takeIf { it != 0L }?.let {
        WuiComputed.video(it, env)
    }

    // Set up volume binding
    val volumeBinding = struct.volumePtr.takeIf { it != 0L }?.let {
        WuiBinding.float(it, env)
    }

    // Track resources that need cleanup
    val resources = mutableListOf<Closeable>()
    sourceComputed?.let { resources.add(it) }
    volumeBinding?.let { resources.add(it) }

    val surfaceView = object : SurfaceView(context), SurfaceHolder.Callback {
        private var mediaPlayer: MediaPlayer? = null
        private var currentUrl: String? = null
        private var surfaceReady = false

        init {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH)
            holder.addCallback(this)

            // Observe source changes
            sourceComputed?.observe { video: VideoStruct ->
                if (video.url != currentUrl) {
                    currentUrl = video.url
                    if (surfaceReady) {
                        loadVideo(video.url)
                    }
                }
            }

            // Observe volume changes
            volumeBinding?.observe { volume: Float ->
                mediaPlayer?.let { player ->
                    // Volume encoding: negative = muted (preserves volume level)
                    if (volume < 0) {
                        player.setVolume(0f, 0f)
                    } else {
                        val normalizedVolume = volume.coerceIn(0f, 1f)
                        player.setVolume(normalizedVolume, normalizedVolume)
                    }
                }
            }
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            currentUrl?.let { loadVideo(it) }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
            releasePlayer()
        }

        private fun loadVideo(url: String) {
            releasePlayer()

            mediaPlayer = MediaPlayer().apply {
                setDisplay(holder)
                setDataSource(context, Uri.parse(url))
                isLooping = loops

                // Apply current volume
                volumeBinding?.current()?.let { volume ->
                    if (volume < 0) {
                        setVolume(0f, 0f)
                    } else {
                        val normalizedVolume = volume.coerceIn(0f, 1f)
                        setVolume(normalizedVolume, normalizedVolume)
                    }
                }

                setOnPreparedListener { mp ->
                    // TODO: Emit ReadyToPlay event
                    mp.start()
                }

                setOnCompletionListener { mp ->
                    // TODO: Emit Ended event
                    if (loops) {
                        mp.seekTo(0)
                        mp.start()
                    }
                }

                setOnErrorListener { _, _, _ ->
                    // TODO: Emit Error event
                    true
                }

                prepareAsync()
            }
        }

        private fun releasePlayer() {
            mediaPlayer?.apply {
                stop()
                release()
            }
            mediaPlayer = null
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            releasePlayer()
            // Clean up resources
            resources.forEach { it.close() }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
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
    }

    surfaceView
}

internal fun RegistryBuilder.registerWuiVideo() {
    register({ videoTypeId }, videoRenderer)
}
