package dev.waterui.android.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import dev.waterui.android.runtime.MediaFilterType
import dev.waterui.android.runtime.MediaLoader
import dev.waterui.android.runtime.MediaPickerStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val mediaPickerTypeId: WuiTypeId by lazy { NativeBindings.waterui_media_picker_id().toTypeId() }

/**
 * Stores the onSelection callback for the current picker session.
 */
private var currentOnSelectionPtr: Long = 0
private var currentOnSelectionDataPtr: Long = 0

/**
 * MediaPicker component renderer.
 *
 * Uses Android's Photo Picker (PickVisualMedia) for selecting media.
 * Falls back to a simple button that launches the picker.
 */
@Suppress("UNUSED_PARAMETER")
private val mediaPickerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_media_picker(node.rawPtr)
    val filterType = struct.filterType()

    // Initialize MediaLoader with context
    MediaLoader.init(context)

    // Create a Material 3 button to launch the picker
    val button = MaterialButton(context).apply {
        text = when (filterType) {
            MediaFilterType.IMAGE -> "Select Image"
            MediaFilterType.VIDEO -> "Select Video"
            MediaFilterType.LIVE_PHOTO -> "Select Live Photo"
            MediaFilterType.ALL -> "Select Media"
        }
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // Find the activity to register the picker launcher
    val activity = context.findActivity()

    if (activity is ComponentActivity) {
        // Set up click listener to launch the picker
        button.setOnClickListener {
            launchPickerFallback(activity, filterType, struct)
        }
    } else if (activity != null) {
        // Regular Activity - use fallback
        button.setOnClickListener {
            launchPickerFallback(activity, filterType, struct)
        }
    } else {
        // No activity found - disable button
        button.isEnabled = false
        button.text = "Media Picker (No Activity)"
    }

    button
}

/**
 * Fallback method to launch media picker using traditional intents.
 */
private fun launchPickerFallback(
    activity: Activity,
    filterType: MediaFilterType,
    struct: MediaPickerStruct
) {
    val mimeType = when (filterType) {
        MediaFilterType.IMAGE -> "image/*"
        MediaFilterType.VIDEO -> "video/*"
        MediaFilterType.LIVE_PHOTO -> "image/*"
        MediaFilterType.ALL -> "*/*"
    }

    // Store callback info for onActivityResult
    currentOnSelectionPtr = struct.onSelectionCallPtr()
    currentOnSelectionDataPtr = struct.onSelectionDataPtr()

    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
        type = mimeType
        addCategory(Intent.CATEGORY_OPENABLE)
        if (filterType == MediaFilterType.ALL) {
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
    }

    try {
        activity.startActivityForResult(intent, REQUEST_CODE_PICK_MEDIA)
    } catch (e: Exception) {
        // Handle case where no picker is available
        android.widget.Toast.makeText(
            activity,
            "No media picker available",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

/**
 * Handle activity result from media picker.
 * Should be called from Activity.onActivityResult.
 */
fun handleMediaPickerResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    if (requestCode != REQUEST_CODE_PICK_MEDIA) return false

    if (resultCode == Activity.RESULT_OK && data?.data != null) {
        val uri = data.data!!
        val mimeType = data.type

        // Register the URI and get ID
        val id = MediaLoader.register(uri, mimeType)

        // Notify Rust of selection
        if (currentOnSelectionPtr != 0L && currentOnSelectionDataPtr != 0L) {
            NativeBindings.callOnSelection(currentOnSelectionDataPtr, currentOnSelectionPtr, id)
        }
    }

    // Clear stored callback
    currentOnSelectionPtr = 0
    currentOnSelectionDataPtr = 0
    return true
}

/**
 * Request code for media picker result.
 */
const val REQUEST_CODE_PICK_MEDIA = 0x4D50 // "MP" in hex

/**
 * Extension to find the Activity from a Context.
 */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) {
            return ctx
        }
        ctx = ctx.baseContext
    }
    return null
}

internal fun RegistryBuilder.registerWuiMediaPicker() {
    register({ mediaPickerTypeId }, mediaPickerRenderer)
}
