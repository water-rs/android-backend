package dev.waterui.android.components

/**
 * @deprecated MediaPicker component is now a composite Button in Rust.
 *
 * MediaPicker wraps a Button internally, so this native component renderer is no longer needed.
 * The view tree will naturally resolve to ButtonComponent instead.
 *
 * This file is kept for reference but the component is not registered.
 */

import android.app.Activity
import android.content.Intent
import dev.waterui.android.runtime.RegistryBuilder

// MediaPicker component renderer is deprecated - MediaPicker is now a composite Button in Rust
// Keeping this stub to prevent compile errors

/**
 * Request code for media picker result.
 */
const val REQUEST_CODE_PICK_MEDIA = 0x4D50 // "MP" in hex

/**
 * Handle activity result from media picker.
 * Should be called from Activity.onActivityResult.
 *
 * @deprecated This function is no longer needed since MediaPicker is handled in Rust.
 */
@Suppress("UNUSED_PARAMETER")
fun handleMediaPickerResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    // No-op: MediaPicker is now a composite Button in Rust
    return false
}

internal fun RegistryBuilder.registerWuiMediaPicker() {
    // No-op: MediaPicker is now a composite Button in Rust
    // The view tree will naturally resolve to Button which has its own renderer
}
