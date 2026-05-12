package dev.waterui.android.runtime

import java.io.Closeable
import dev.waterui.android.reactive.WatcherGuard

/**
 * Canonical wrapper for native pointers obtained via JNI. Kotlin treats them as opaque [Long] values.
 * The pointer stays valid until [close] is invoked.
 */
abstract class NativePointer(
    protected var handle: Long
) : Closeable {

    /** Returns the raw native handle for JNI calls. */
    fun raw(): Long = handle

    /** Whether the pointer has already been released. */
    val isReleased: Boolean get() = handle == 0L

    override fun close() {
        if (!isReleased) {
            release(handle)
            handle = 0L
        }
    }

    /**
     * Release hook implemented by subclasses to drop native resources via JNI.
     * Implementations must tolerate repeated invocations with the same handle.
     */
    protected abstract fun release(ptr: Long)
}

/**
 * Helper that scopes a native pointer for the duration of [block] and releases it afterwards.
 */
inline fun <T : NativePointer, R> T.usePointer(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        close()
    }
}

class NativeAnyViews(handle: Long) : NativePointer(handle) {
    fun size(): Int = if (isReleased) 0 else NativeBindings.waterui_any_views_len(raw())

    fun viewAt(index: Int): Long {
        if (isReleased) return 0L
        return NativeBindings.waterui_any_views_get_view(raw(), index)
    }

    fun toList(): List<Long> {
        val result = ArrayList<Long>(size())
        val count = size()
        for (i in 0 until count) {
            val ptr = viewAt(i)
            if (ptr != 0L) {
                result += ptr
            }
        }
        return result
    }

    fun ids(start: Int = 0, end: Int = -1): IntArray {
        if (isReleased) return IntArray(0)
        val resolvedEnd = if (end >= 0) end else size()
        return NativeBindings.waterui_any_views_get_ids(raw(), start, resolvedEnd)
    }

    fun watch(onChanged: () -> Unit): WatcherGuard {
        check(!isReleased) { "watch called on released NativeAnyViews" }
        val guard = NativeBindings.waterui_any_views_watch(raw(), Runnable(onChanged))
        check(guard != 0L) { "waterui_any_views_watch returned null guard" }
        return WatcherGuard(guard)
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_drop_any_views(ptr)
    }
}
