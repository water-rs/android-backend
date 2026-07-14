package dev.waterui.android.runtime

import android.os.Looper
import androidx.annotation.Keep
import java.io.Closeable
import dev.waterui.android.reactive.WatcherGuard

@Keep
fun interface NativeAnyViewsWatcher {
    fun onChanged(ids: IntArray)
}

/**
 * Canonical wrapper for native pointers obtained via JNI. Kotlin treats them as opaque [Long] values.
 * The pointer stays valid until [close] is invoked.
 */
abstract class NativePointer(
    protected var handle: Long
) : Closeable {
    init {
        require(handle != 0L) { "native pointer must not be null" }
    }

    /** Returns the raw native handle for JNI calls. */
    fun raw(): Long {
        check(!isReleased) { "native pointer ownership was already transferred or released" }
        return handle
    }

    /** Whether the pointer has already been released. */
    val isReleased: Boolean get() = handle == 0L

    /** Transfers ownership of this pointer to another native owner. */
    fun takeRaw(): Long {
        check(!isReleased) { "native pointer ownership was already transferred or released" }
        return handle.also { handle = 0L }
    }

    override fun close() {
        release(takeRaw())
    }

    /**
     * Release hook implemented by subclasses to drop native resources via JNI.
     * [NativePointer] invokes this exactly once for an owned handle.
     */
    protected abstract fun release(ptr: Long)
}

class NativeAnyViews(handle: Long) : NativePointer(handle) {
    fun size(): Int = NativeBindings.waterui_any_views_len(raw())

    fun viewAt(index: Int): Long = NativeBindings.waterui_any_views_get_view(raw(), index)

    fun ids(start: Int = 0, end: Int = -1): IntArray {
        val resolvedEnd = if (end >= 0) end else size()
        return NativeBindings.waterui_any_views_get_ids(raw(), start, resolvedEnd)
    }

    fun watch(onChanged: (IntArray) -> Unit): WatcherGuard {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "NativeAnyViews watchers must be registered on the Android main thread"
        }
        val callback = NativeAnyViewsWatcher { ids ->
            check(Looper.myLooper() === Looper.getMainLooper()) {
                "NativeAnyViews watcher callback left the Android main thread"
            }
            onChanged(ids)
        }
        val guard = NativeBindings.waterui_any_views_watch(raw(), callback)
        check(guard != 0L) { "waterui_any_views_watch returned null guard" }
        return WatcherGuard(guard)
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_drop_any_views(ptr)
    }
}
