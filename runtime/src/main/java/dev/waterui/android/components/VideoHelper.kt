package dev.waterui.android.components

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
import dev.waterui.android.layout.PassThroughFrameLayout

/**
 * Aspect ratio modes matching WuiAspectRatio enum.
 */
object AspectRatioMode {
    const val FIT = 0    // Fit within bounds, maintaining aspect ratio (letterbox/pillarbox)
    const val FILL = 1   // Fill bounds, cropping excess (scale to fill, clip)
    const val STRETCH = 2 // Stretch to fill bounds, ignoring aspect ratio
}

/**
 * A reusable video view that uses TextureView + MediaPlayer for proper aspect ratio support.
 *
 * Supports:
 * - Fit: Video fits within bounds, maintaining aspect ratio (letterbox)
 * - Fill: Video scales to fill bounds, cropping excess (like iOS resizeAspectFill)
 * - Stretch: Video stretches to fill bounds, ignoring aspect ratio
 * - Optional native controls via MediaController
 * - Looping
 * - Volume control
 */
class WuiVideoTextureView(
    context: Context,
    private val aspectRatioMode: Int = AspectRatioMode.FIT,
    private val showControls: Boolean = false,
    private val loops: Boolean = true
) : FrameLayout(context) {

    private val textureView = TextureView(context)
    private var mediaPlayer: MediaPlayer? = null
    private var mediaController: MediaController? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var surfaceReady = false
    private var pendingUrl: String? = null
    private var currentVolume = 1f

    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        addView(textureView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                surfaceReady = true
                pendingUrl?.let { url ->
                    pendingUrl = null
                    startPlayback(url, Surface(surface))
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                updateVideoTransform()
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                surfaceReady = false
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // Set up media controller if controls are enabled
        if (showControls) {
            // Create MediaController and set anchor view to this FrameLayout
            mediaController = MediaController(context).apply {
                setAnchorView(this@WuiVideoTextureView)
            }

            // Handle touch on the texture view area to toggle controls
            // Mark as wanting touches for iOS-like hit-testing
            textureView.setTag(PassThroughFrameLayout.TAG_WANTS_TOUCHES, true)
            textureView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    toggleMediaController()
                }
                true
            }
        }
    }

    private fun toggleMediaController() {
        mediaController?.let { mc ->
            if (mc.isShowing) {
                mc.hide()
            } else {
                mc.show()
            }
        }
    }

    /**
     * Set the video URL to play.
     */
    fun setVideoUrl(url: String) {
        if (surfaceReady) {
            textureView.surfaceTexture?.let { st ->
                startPlayback(url, Surface(st))
            }
        } else {
            pendingUrl = url
        }
    }

    /**
     * Set the volume level.
     * @param volume 0.0 to 1.0 for volume, negative values mean muted
     */
    fun setVolume(volume: Float) {
        currentVolume = volume
        mediaPlayer?.let { mp ->
            val actualVolume = if (volume < 0) 0f else volume.coerceIn(0f, 1f)
            mp.setVolume(actualVolume, actualVolume)
        }
    }

    /**
     * Release all resources. Call this when the view is no longer needed.
     */
    fun release() {
        releasePlayer()
        mediaController = null
    }

    private fun startPlayback(url: String, surface: Surface) {
        try {
            // Hide and detach media controller before releasing player
            mediaController?.hide()
            releasePlayer()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.parse(url))
                setSurface(surface)
                isLooping = loops
                setVolume(
                    if (currentVolume < 0) 0f else currentVolume.coerceIn(0f, 1f),
                    if (currentVolume < 0) 0f else currentVolume.coerceIn(0f, 1f)
                )
                setOnVideoSizeChangedListener { _, width, height ->
                    this@WuiVideoTextureView.videoWidth = width
                    this@WuiVideoTextureView.videoHeight = height
                    updateVideoTransform()
                }
                setOnPreparedListener { mp ->
                    // Attach media controller to player with safe wrapper
                    // All methods check if the player is still current and catch exceptions
                    mediaController?.let { mc ->
                        mc.setMediaPlayer(object : MediaController.MediaPlayerControl {
                            override fun start() {
                                try { if (mediaPlayer === mp) mp.start() } catch (_: Exception) {}
                            }
                            override fun pause() {
                                try { if (mediaPlayer === mp) mp.pause() } catch (_: Exception) {}
                            }
                            override fun getDuration(): Int {
                                return try { if (mediaPlayer === mp) mp.duration else 0 } catch (_: Exception) { 0 }
                            }
                            override fun getCurrentPosition(): Int {
                                return try { if (mediaPlayer === mp) mp.currentPosition else 0 } catch (_: Exception) { 0 }
                            }
                            override fun seekTo(pos: Int) {
                                try { if (mediaPlayer === mp) mp.seekTo(pos) } catch (_: Exception) {}
                            }
                            override fun isPlaying(): Boolean {
                                return try { mediaPlayer === mp && mp.isPlaying } catch (_: Exception) { false }
                            }
                            override fun getBufferPercentage() = 0
                            override fun canPause() = true
                            override fun canSeekBackward() = true
                            override fun canSeekForward() = true
                            override fun getAudioSessionId(): Int {
                                return try { if (mediaPlayer === mp) mp.audioSessionId else 0 } catch (_: Exception) { 0 }
                            }
                        })
                        // Show controls briefly when video starts
                        mc.show(3000)
                    }
                    mp.start()
                }
                setOnErrorListener { _, what, extra ->
                    android.util.Log.e("WaterUI.Video", "MediaPlayer error: what=$what extra=$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            android.util.Log.e("WaterUI.Video", "Failed to set video URL: $url", e)
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun updateVideoTransform() {
        if (videoWidth == 0 || videoHeight == 0) return

        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth == 0f || viewHeight == 0f) return

        val matrix = Matrix()

        when (aspectRatioMode) {
            AspectRatioMode.FIT -> {
                // Scale to fit within bounds, maintaining aspect ratio (letterbox)
                val videoAspect = videoWidth.toFloat() / videoHeight
                val viewAspect = viewWidth / viewHeight

                val scaleX: Float
                val scaleY: Float
                if (videoAspect > viewAspect) {
                    // Video is wider - fit to width
                    scaleX = 1f
                    scaleY = (viewWidth / videoAspect) / viewHeight
                } else {
                    // Video is taller - fit to height
                    scaleX = (viewHeight * videoAspect) / viewWidth
                    scaleY = 1f
                }

                matrix.setScale(scaleX, scaleY, viewWidth / 2, viewHeight / 2)
            }
            AspectRatioMode.FILL -> {
                // Scale to fill bounds, cropping excess (like iOS resizeAspectFill)
                val videoAspect = videoWidth.toFloat() / videoHeight
                val viewAspect = viewWidth / viewHeight

                val scaleX: Float
                val scaleY: Float
                if (videoAspect > viewAspect) {
                    // Video is wider - scale to fill height, crop width
                    scaleX = (viewHeight * videoAspect) / viewWidth
                    scaleY = 1f
                } else {
                    // Video is taller - scale to fill width, crop height
                    scaleX = 1f
                    scaleY = (viewWidth / videoAspect) / viewHeight
                }

                matrix.setScale(scaleX, scaleY, viewWidth / 2, viewHeight / 2)
            }
            AspectRatioMode.STRETCH -> {
                // No transform needed - video stretches to fill (default TextureView behavior)
                matrix.reset()
            }
        }

        textureView.setTransform(matrix)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}
