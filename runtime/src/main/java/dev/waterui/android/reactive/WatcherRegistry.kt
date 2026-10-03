package dev.waterui.android.reactive

import androidx.annotation.Keep

/**
 * Per-runtime ownership point for live watcher callbacks.
 *
 * Rust watchers used to retain their Kotlin callback as a JNI global
 * reference each, and ART caps the global reference table at 51,200 entries —
 * a few thousand animated views each watching several signals overflowed it
 * and killed the process. The table holds ordinary Java references instead:
 * Rust keeps the integer id, the map keeps the callback alive, and
 * [unregister] releases it when the watcher drops.
 *
 * Each `WuiEnvironment` owns one instance; native code reaches it through a
 * single global reference inside its JNI context, so no statics and no
 * process-wide state are involved.
 */
@Keep
class WatcherRegistry {
    private val callbacks = HashMap<Long, WatcherCallback<Any?>>()
    private var nextId = 1L

    /** Retains [callback] and returns the id native code uses to reach it. */
    fun register(callback: WatcherCallback<*>): Long {
        synchronized(callbacks) {
            val id = nextId++
            @Suppress("UNCHECKED_CAST")
            callbacks[id] = callback as WatcherCallback<Any?>
            return id
        }
    }

    /** Releases the callback [id] was registered under. */
    fun unregister(id: Long) {
        val removed = synchronized(callbacks) { callbacks.remove(id) }
        checkNotNull(removed) { "watcher unregister for unknown id $id" }
    }

    /** Forwards a native value change to the callback registered under [id]. */
    fun dispatch(id: Long, value: Any?, metadata: WuiWatcherMetadata) {
        // Resolve under the lock, invoke outside it: a callback may create or
        // drop watchers of its own and must not deadlock on the map. A lookup
        // miss means native code dispatched for an id that was never
        // registered or was already dropped — a lifecycle bug, not a race:
        // the callback reference is retained for the whole invocation, so
        // `watcher_drop` cannot run until it returns.
        val callback = synchronized(callbacks) { callbacks[id] }
            ?: error("watcher dispatch for unregistered id $id")
        callback.onChanged(value, metadata)
    }

    /** Number of live registrations; tests use it to check the table drains. */
    fun count(): Int = synchronized(callbacks) { callbacks.size }
}
