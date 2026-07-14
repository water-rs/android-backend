package dev.waterui.android.runtime

import androidx.annotation.Keep

/** Receives ownership of an asynchronously-created Rust GPU runtime. */
@Keep
fun interface GpuRuntimeReadyCallback {
    /** Called on the GPU initialization thread. */
    @Keep
    fun onReady(runtimePtr: Long)
}
