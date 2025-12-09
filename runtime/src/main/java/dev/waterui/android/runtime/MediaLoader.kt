package dev.waterui.android.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Media type constants matching Rust's MediaLoadResult.media_type.
 */
object MediaType {
    const val IMAGE: Byte = 0
    const val VIDEO: Byte = 1
    const val MOTION_PHOTO: Byte = 2  // Maps to LivePhoto in Rust
}

/**
 * Registry and loader for media selected from MediaPicker.
 *
 * This singleton manages:
 * 1. Registration of selected media URIs with unique IDs
 * 2. Loading media from content:// URIs to temp files
 * 3. Motion Photo detection and splitting
 * 4. Callback to Rust when loading completes
 */
object MediaLoader {
    private val nextId = AtomicInteger(1)
    private val pendingMedia = ConcurrentHashMap<Int, PendingMedia>()
    private val executor = Executors.newCachedThreadPool()

    private var appContext: Context? = null

    /**
     * Data class holding pending media info.
     */
    data class PendingMedia(
        val uri: Uri,
        val mimeType: String?
    )

    /**
     * Result of loading media, potentially with both image and video components.
     */
    data class LoadResult(
        val imageUrl: String,
        val videoUrl: String?,
        val mediaType: Byte
    )

    /**
     * Initialize with application context.
     * Must be called before any media loading.
     */
    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Register a content URI and return its unique ID.
     * Called when user selects media from the picker.
     */
    @JvmStatic
    fun register(uri: Uri, mimeType: String? = null): Int {
        val id = nextId.getAndIncrement()
        pendingMedia[id] = PendingMedia(uri, mimeType)
        return id
    }

    /**
     * Load media by ID and invoke callback when done.
     * Called from JNI when Rust's Selected::load() is invoked.
     *
     * @param id The media selection ID
     * @param callbackData Opaque pointer to callback data
     * @param callFnPtr Function pointer to invoke callback
     */
    @JvmStatic
    fun loadMedia(id: Int, callbackData: Long, callFnPtr: Long) {
        val pending = pendingMedia.remove(id)
            ?: error("MediaLoader.loadMedia: no media found for id $id")

        val context = appContext
            ?: error("MediaLoader.loadMedia: context not initialized")

        // Load asynchronously
        executor.execute {
            val result = loadMediaSync(context, pending)
            nativeCompleteMediaLoad(
                callbackData,
                callFnPtr,
                result.imageUrl,
                result.videoUrl,
                result.mediaType
            )
        }
    }

    /**
     * Synchronously load media, handling Motion Photos specially.
     */
    private fun loadMediaSync(context: Context, pending: PendingMedia): LoadResult {
        val resolvedMime = pending.mimeType ?: context.contentResolver.getType(pending.uri)

        // Check if it's a Motion Photo (Google Photos' Live Photo equivalent)
        if (isMotionPhoto(context, pending.uri)) {
            return loadMotionPhoto(context, pending.uri, resolvedMime)
        }

        // Regular image or video
        val fileUrl = copyToTempFile(context, pending.uri, resolvedMime)
        val mediaType = detectMediaType(resolvedMime)
        return LoadResult(fileUrl, null, mediaType)
    }

    /**
     * Check if a URI points to a Motion Photo.
     * Motion Photos have embedded video in the image file.
     */
    private fun isMotionPhoto(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false
        }

        return try {
            // Query the media store for Motion Photo info
            // Column name "is_motion_photo" available since API 30
            val projection = arrayOf("is_motion_photo")
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex("is_motion_photo")
                    if (columnIndex >= 0) {
                        return@use cursor.getInt(columnIndex) == 1
                    }
                }
                false
            } ?: false
        } catch (e: Exception) {
            android.util.Log.w("MediaLoader", "Failed to check Motion Photo: ${e.message}")
            false
        }
    }

    /**
     * Load a Motion Photo, extracting both image and video components.
     */
    private fun loadMotionPhoto(context: Context, uri: Uri, mimeType: String?): LoadResult {
        // Copy the main image file
        val imageUrl = copyToTempFile(context, uri, mimeType, suffix = "_image")

        // Try to extract the video component
        val videoUrl = extractMotionPhotoVideo(context, uri)
            ?: error("MediaLoader: failed to extract video from Motion Photo")

        return LoadResult(imageUrl, videoUrl, MediaType.MOTION_PHOTO)
    }

    /**
     * Extract the embedded video from a Motion Photo.
     *
     * Motion Photos have video data embedded after the JPEG data.
     * On Android R+, we can use MediaStore to get the video offset.
     */
    private fun extractMotionPhotoVideo(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        try {
            // Query for video offset
            // Column name "motion_photo_offset" available since API 30
            val projection = arrayOf("motion_photo_offset")
            var videoOffset = -1L

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex("motion_photo_offset")
                    if (columnIndex >= 0) {
                        videoOffset = cursor.getLong(columnIndex)
                    }
                }
            }

            if (videoOffset <= 0) {
                android.util.Log.w("MediaLoader", "Motion Photo video offset not found or invalid: $videoOffset")
                return null
            }

            // Read the file and extract video portion
            context.contentResolver.openInputStream(uri)?.use { input ->
                val tempFile = File.createTempFile("motion_video_", ".mp4", context.cacheDir)

                // Skip to video offset
                val skipped = input.skip(videoOffset)
                if (skipped != videoOffset) {
                    android.util.Log.w("MediaLoader", "Failed to skip to video offset: skipped $skipped, expected $videoOffset")
                    return null
                }

                // Write remaining bytes to video file
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }

                return "file://${tempFile.absolutePath}"
            }
        } catch (e: Exception) {
            android.util.Log.e("MediaLoader", "Failed to extract Motion Photo video: ${e.message}", e)
        }

        return null
    }

    /**
     * Copy content:// URI to a temporary file and return file:// URL.
     */
    private fun copyToTempFile(
        context: Context,
        uri: Uri,
        mimeType: String?,
        suffix: String = ""
    ): String {
        val extension = getExtension(context, uri, mimeType)
        val tempFile = File.createTempFile("media${suffix}_", ".$extension", context.cacheDir)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: error("MediaLoader: cannot open input stream for $uri")

        return "file://${tempFile.absolutePath}"
    }

    /**
     * Get file extension from URI or MIME type.
     */
    private fun getExtension(context: Context, uri: Uri, mimeType: String?): String {
        // Try to get from content resolver
        val resolvedMime = mimeType ?: context.contentResolver.getType(uri)
        if (resolvedMime != null) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime)
            if (ext != null) return ext
        }

        // Try to get from URI path
        val path = uri.path
        if (path != null) {
            val lastDot = path.lastIndexOf('.')
            if (lastDot >= 0) {
                return path.substring(lastDot + 1)
            }
        }

        // Default based on MIME type prefix
        return when {
            resolvedMime?.startsWith("video/") == true -> "mp4"
            resolvedMime?.startsWith("image/") == true -> "jpg"
            else -> "bin"
        }
    }

    /**
     * Detect media type from MIME type.
     */
    private fun detectMediaType(mimeType: String?): Byte {
        return when {
            mimeType?.startsWith("video/") == true -> MediaType.VIDEO
            mimeType?.startsWith("image/") == true -> MediaType.IMAGE
            else -> MediaType.IMAGE
        }
    }

    /**
     * Native method to complete media loading.
     * Calls back into Rust with the result.
     *
     * @param callbackData Opaque pointer to callback data
     * @param callFnPtr Function pointer to invoke callback
     * @param imageUrl The image file URL (always present)
     * @param videoUrl The video file URL (only for Motion Photos)
     * @param mediaType The media type (0=Image, 1=Video, 2=MotionPhoto)
     */
    @JvmStatic
    private external fun nativeCompleteMediaLoad(
        callbackData: Long,
        callFnPtr: Long,
        imageUrl: String,
        videoUrl: String?,
        mediaType: Byte
    )
}
