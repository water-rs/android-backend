package dev.waterui.android.components

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Aspect ratio modes matching WuiAspectRatio enum.
 */
object AspectRatioMode {
    const val FIT = 0    // Fit within bounds, maintaining aspect ratio (letterbox/pillarbox)
    const val FILL = 1   // Fill bounds, cropping excess (scale to fill, clip)
    const val STRETCH = 2 // Stretch to fill bounds, ignoring aspect ratio
}

/**
 * A video view using Media3 ExoPlayer with Material Design 3 controls.
 *
 * Supports:
 * - Fit: Video fits within bounds, maintaining aspect ratio (letterbox)
 * - Fill: Video scales to fill bounds, cropping excess
 * - Stretch: Video stretches to fill bounds, ignoring aspect ratio
 * - Material Design 3 player controls
 * - Looping
 * - Volume control
 * - Better streaming support (HLS, DASH, etc.)
 */
@OptIn(UnstableApi::class)
class WuiVideoTextureView(
    context: Context,
    private val aspectRatioMode: Int = AspectRatioMode.FIT,
    private val showControls: Boolean = false,
    private val loops: Boolean = true
) : PlayerView(context) {

    private var exoPlayer: ExoPlayer? = null
    private var currentVolume = 1f
    private var currentUrl: String? = null

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Set up aspect ratio mode
        resizeMode = when (aspectRatioMode) {
            AspectRatioMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatioMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        // Configure controls visibility
        useController = showControls

        // Create and attach ExoPlayer
        createPlayer()
    }

    private fun createPlayer() {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            repeatMode = if (loops) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            volume = if (currentVolume < 0) 0f else currentVolume.coerceIn(0f, 1f)
        }
        player = exoPlayer
    }

    /**
     * Set the video URL to play.
     */
    fun setVideoUrl(url: String) {
        if (url == currentUrl) return
        currentUrl = url

        exoPlayer?.let { player ->
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }
    }

    /**
     * Set the volume level.
     * @param volume 0.0 to 1.0 for volume, negative values mean muted
     */
    fun setVolume(volume: Float) {
        currentVolume = volume
        exoPlayer?.volume = if (volume < 0) 0f else volume.coerceIn(0f, 1f)
    }

    /**
     * Release all resources. Call this when the view is no longer needed.
     */
    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        player = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Recreate player if it was released
        if (exoPlayer == null) {
            createPlayer()
            // Restore URL if we had one
            currentUrl?.let { url ->
                currentUrl = null // Reset so setVideoUrl will process it
                setVideoUrl(url)
            }
        }
    }
}
