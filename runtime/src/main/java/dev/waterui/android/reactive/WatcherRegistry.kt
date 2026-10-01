package dev.waterui.android.reactive

import androidx.annotation.Keep

/**
 * Ownership point for live watcher callbacks.
 *
 * Rust watchers used to retain their Kotlin callback as a JNI global
 * reference each, and ART caps the global reference table at 51,200 entries —
 * a few thousand animated views each watching several signals overflowed it
 * and killed the process. The table holds ordinary Java references instead:
 * Rust keeps the integer id, the map keeps the callback alive, and
 * [unregister] releases it when the watcher drops.
 */
@Keep
object WatcherRegistry {
    private val callbacks = HashMap<Long, WatcherCallback<Any?>>()
    private var nextId = 1L

    /** Retains [callback] and returns the id native code uses to reach it. */
    @JvmStatic
    fun register(callback: WatcherCallback<*>): Long {
        synchronized(callbacks) {
            val id = nextId++
            @Suppress("UNCHECKED_CAST")
            callbacks[id] = callback as WatcherCallback<Any?>
            return id
        }
    }

    /** Releases the callback [id] was registered under. */
    @JvmStatic
    fun unregister(id: Long) {
        synchronized(callbacks) { callbacks.remove(id) }
    }

    /** Forwards a native value change to the callback registered under [id]. */
    @JvmStatic
    fun dispatch(id: Long, value: Any?, metadata: WuiWatcherMetadata) {
        // Resolve under the lock, invoke outside it: a callback may create or
        // drop watchers of its own and must not deadlock on the map.
        val callback = synchronized(callbacks) { callbacks[id] } ?: return
        callback.onChanged(value, metadata)
    }

    /** Number of live registrations; tests use it to check the table drains. */
    @JvmStatic
    fun count(): Int = synchronized(callbacks) { callbacks.size }
}
