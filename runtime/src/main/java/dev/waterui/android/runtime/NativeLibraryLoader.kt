package dev.waterui.android.runtime

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads the native WaterUI shared libraries exactly once per process.
 *
 * Consumers **must** call [configure] with the name of the Rust-generated library
 * (built by the CLI) before any WaterUI APIs are touched. That library is expected
 * to export the C ABI defined in `waterui.h`.
 *
 * This runtime ships its own JNI shim, `libwaterui_android.so`, which is loaded
 * after the application library so the dynamic linker can resolve WaterUI symbols.
 */
internal object NativeLibraryLoader {
    private val loaded = AtomicBoolean(false)
    private val nativeLibName = AtomicReference<String?>(null)
    private const val TAG = "WaterUI.NativeLoader"

    fun configure(libraryName: String) {
        nativeLibName.set(libraryName)
    }

    fun ensureLoaded() {
        if (loaded.compareAndSet(false, true)) {
            val appLib = nativeLibName.get()
                ?: error("WaterUI native library not configured. Call configureWaterUiNativeLibrary(\"<library>\") before using the runtime.")
            runCatching {
                System.loadLibrary(appLib)
                System.loadLibrary("waterui_android")
            }.onFailure { error ->
                loaded.set(false)
                Log.e(TAG, "Unable to load WaterUI native libraries", error)
                throw error
            }
        }
    }
}
