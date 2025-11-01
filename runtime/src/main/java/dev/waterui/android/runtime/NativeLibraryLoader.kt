package dev.waterui.android.runtime

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the native WaterUI shared libraries exactly once per process.
 *
 * We expect distributables to bundle two libraries:
 * 1. `libwaterui_ffi.so` – produced by the Rust FFI crate.
 * 2. `libwaterui_android.so` – a thin JNI shim that translates between JVM and the C ABI.
 *
 * Consumers should call [ensureLoaded] before invoking any functions on [NativeBindings].
 */
internal object NativeLibraryLoader {
    private val loaded = AtomicBoolean(false)
    private const val TAG = "WaterUI.NativeLoader"

    fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            runCatching {
                System.loadLibrary("waterui_ffi")
                System.loadLibrary("waterui_android")
            }.onFailure { error ->
                loaded.set(false)
                Log.e(TAG, "Unable to load WaterUI native libraries", error)
                throw error
            }
        }
    }
}
