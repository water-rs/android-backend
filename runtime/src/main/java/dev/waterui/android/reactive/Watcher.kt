package dev.waterui.android.reactive

import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativePointer

/**
 * Holder around native watcher metadata. Manages drop semantics.
 */
class WatcherGuard(pointer: Long) : NativePointer(pointer) {
    override fun release(ptr: Long) {
        NativeBindings.waterui_drop_watcher_guard(ptr)
    }
}

data class WuiWatcherMetadata(val pointer: Long) {
    val animation: Int get() = NativeBindings.waterui_get_animation(pointer)
}

fun interface WatcherCallback<T> {
    fun onChanged(value: T, metadata: WuiWatcherMetadata)
}
