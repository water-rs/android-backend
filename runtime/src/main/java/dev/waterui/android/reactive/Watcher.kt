package dev.waterui.android.reactive

import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.WuiAnimation

/**
 * Holder around native watcher metadata. Manages drop semantics.
 */
class WatcherGuard(pointer: Long) : NativePointer(pointer) {
    override fun release(ptr: Long) {
        WatcherJni.dropWatcherGuard(ptr)
    }
}

data class WuiWatcherMetadata(val pointer: Long) {
    val animation: WuiAnimation get() = decodeAnimation(pointer)
}

private fun decodeAnimation(metadataPtr: Long): WuiAnimation {
    val kindDurationPacked = WatcherJni.getAnimationKindDurationPacked(metadataPtr)
    val params12Packed = WatcherJni.getAnimationParams12Packed(metadataPtr)
    val params34Packed = WatcherJni.getAnimationParams34Packed(metadataPtr)
    return WuiAnimation.fromNative(kindDurationPacked, params12Packed, params34Packed)
}

fun interface WatcherCallback<T> {
    fun onChanged(value: T, metadata: WuiWatcherMetadata)
}
