package dev.waterui.android.reactive

import androidx.annotation.Keep
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.WuiAnimation

/**
 * Holder around native watcher metadata. Manages drop semantics.
 */
class WatcherGuard(pointer: Long) : NativePointer(pointer) {
    override fun release(ptr: Long) {
        NativeBindings.waterui_drop_watcher_guard(ptr)
    }
}

/** Borrowed watcher metadata that is valid only for the synchronous [WatcherCallback] call. */
@Keep
class WuiWatcherMetadata(val pointer: Long) {
    val animation: WuiAnimation get() = NativeBindings.waterui_get_animation(pointer)
}

@Keep
fun interface WatcherCallback<T> {
    fun onChanged(value: T, metadata: WuiWatcherMetadata)
}
