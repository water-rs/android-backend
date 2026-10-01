package dev.waterui.android.reactive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The registry is what lets thousands of live watchers exist at all.
 *
 * Watchers used to hold one JNI global reference to their callback each, and
 * ART's global reference table caps at 51,200 entries — the 6400-rect
 * capacity workload's ~8 watchers per rect overflowed it and aborted the
 * process. The callbacks now live in this plain table behind integer ids, so
 * the only JNI-side state per watcher is a long.
 */
class WatcherRegistryTest {
    private companion object {
        /** Past ART's 51,200 global-reference ceiling. */
        const val WATCHERS = 52_000
    }

    private fun recordableCallback(): Pair<WatcherCallback<Any?>, MutableList<Any?>> {
        val seen = mutableListOf<Any?>()
        val callback = WatcherCallback<Any?> { value, _ -> seen.add(value) }
        return callback to seen
    }

    @Test
    fun dispatchResolvesTheRegisteredCallback() {
        val (callback, seen) = recordableCallback()
        val id = WatcherRegistry.register(callback)
        try {
            WatcherRegistry.dispatch(id, "value", WuiWatcherMetadata(0))
            assertEquals(listOf("value"), seen)
        } finally {
            WatcherRegistry.unregister(id)
        }
    }

    @Test
    fun dispatchToAnUnregisteredIdIsANoOp() {
        // A watcher dropped between the native signal firing and the dispatch
        // reaching the table must not crash or leak the call.
        WatcherRegistry.dispatch(Long.MAX_VALUE, "value", WuiWatcherMetadata(0))
    }

    @Test
    fun moreThanTheGlobalReferenceCeilingCanLiveAndAllAreReleased() {
        val (callback, _) = recordableCallback()
        val ids = LongArray(WATCHERS) { WatcherRegistry.register(callback) }
        val baseline = WatcherRegistry.count()
        try {
            assertEquals(WATCHERS.toLong(), ids.toSet().size.toLong())
            assertEquals(WATCHERS, baseline)
        } finally {
            ids.forEach(WatcherRegistry::unregister)
        }
        // The table drains fully: every registration released, nothing held.
        assertEquals(0, WatcherRegistry.count())
        WatcherRegistry.dispatch(ids[0], "value", WuiWatcherMetadata(0))
    }
}
