package dev.waterui.android.runtime

import androidx.annotation.Keep

/** Receives ownership of an asynchronously-created Rust GPU runtime. */
@Keep
fun interface GpuRuntimeReadyCallback {
    /**
     * Called from the Rust main-thread local executor that drove GPU runtime
     * creation, not from a dedicated GPU thread: `waterui_gpu_runtime_create`
     * spawns the creation onto that executor and completes on it.
     */
    @Keep
    fun onReady(runtimePtr: Long)
}
