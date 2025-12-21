package dev.waterui.android.reactive

import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.NativePointer

/**
 * Simple wrapper for a computed bool pointer.
 * Provides read-only access to the boolean value.
 */
class WuiComputedBool(computedPtr: Long) : NativePointer(computedPtr) {

    /** Current value of the computed bool */
    val value: Boolean
        get() = if (isReleased) false else WatcherJni.readBindingBool(raw())

    /** Dispose and release the native pointer */
    fun dispose() {
        close()
    }

    override fun release(ptr: Long) {
        WatcherJni.dropBindingBool(ptr)
    }
}
