package dev.waterui.android.runtime

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Manages presenting the Android photo picker and handling results.
 * This is called from native code via JNI.
 */
object MediaPickerManager {
    private const val TAG = "MediaPickerManager"

    private var currentActivity: ComponentActivity? = null
    private var pickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null
    private var pendingCallbackData: Long = 0
    private var pendingCallbackFn: Long = 0

    /**
     * Initialize the media picker for the given activity.
     * Must be called during Activity onCreate before the activity is started.
     */
    fun initialize(activity: ComponentActivity) {
        currentActivity = activity

        // Register the photo picker launcher
        pickerLauncher = activity.registerForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                // Register the URI and get a unique ID
                val id = MediaRegistry.register(uri)

                // Call the native callback
                if (pendingCallbackFn != 0L) {
                    nativeCompletePresentCallback(pendingCallbackData, pendingCallbackFn, id)
                    pendingCallbackData = 0
                    pendingCallbackFn = 0
                }
            } else {
                Log.d(TAG, "No media selected")
                // Clear pending callback
                pendingCallbackData = 0
                pendingCallbackFn = 0
            }
        }
    }

    /**
     * Present the media picker with the given filter.
     * Called from native code via JNI.
     *
     * @param filter The media filter type (0=LivePhoto, 1=Video, 2=Image, 3=All)
     * @param callbackData Opaque pointer to callback data
     * @param callbackFn Function pointer to call when selection is made
     */
    @JvmStatic
    fun presentPicker(filter: Int, callbackData: Long, callbackFn: Long) {
        val launcher = pickerLauncher
        if (launcher == null) {
            Log.e(TAG, "MediaPicker not initialized - call initialize() in Activity.onCreate()")
            return
        }

        // Store callback for later
        pendingCallbackData = callbackData
        pendingCallbackFn = callbackFn

        // Map filter to Android photo picker type
        val mediaType = when (filter) {
            0 -> ActivityResultContracts.PickVisualMedia.ImageOnly // LivePhoto -> Image
            1 -> ActivityResultContracts.PickVisualMedia.VideoOnly
            2 -> ActivityResultContracts.PickVisualMedia.ImageOnly
            3 -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
            else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }

        // Launch the picker
        launcher.launch(PickVisualMediaRequest(mediaType))
    }

    /**
     * Native method to complete the present callback.
     * This calls the Rust callback with the selected media ID.
     */
    private external fun nativeCompletePresentCallback(
        callbackData: Long,
        callbackFn: Long,
        selectedId: Int
    )
}
