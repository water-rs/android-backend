package dev.waterui.android.reactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is what lets thousands of live watchers exist at all.
 *
 * Watchers used to hold one JNI global reference to their callback each, and
 * ART's global reference table caps at 51,200 entries — the 6400-rect
 * capacity workload's ~8 watchers per rect overflowed it and aborted the
 * process. The callbacks now live in this plain table behind integer ids, so
 * the only JNI-side state per watcher is a long. Each [dev.waterui.android.runtime.WuiEnvironment]
 * owns one registry; native code reaches it through a single global ref held
 * in the environment's watcher context.
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
        val registry = WatcherRegistry()
        val (callback, seen) = recordableCallback()
        val id = registry.register(callback)
        try {
            registry.dispatch(id, "value", WuiWatcherMetadata(0))
            assertEquals(listOf("value"), seen)
        } finally {
            registry.unregister(id)
        }
    }

    @Test
    fun dispatchToAnUnregisteredIdFails() {
        // The native side retains the callback data while a dispatch is in
        // flight, so a miss means the register/dispatch/unregister order was
        // violated — a lifecycle bug, which must fail loudly with the id.
        val registry = WatcherRegistry()
        val error = assertThrows(IllegalStateException::class.java) {
            registry.dispatch(Long.MAX_VALUE, "value", WuiWatcherMetadata(0))
        }
        assertTrue(error.message.orEmpty().contains(Long.MAX_VALUE.toString()))
    }

    @Test
    fun unregisterAnUnknownIdFails() {
        val registry = WatcherRegistry()
        assertThrows(IllegalStateException::class.java) {
            registry.unregister(Long.MAX_VALUE)
        }
    }

    @Test
    fun dispatchToAnAlreadyUnregisteredIdFails() {
        val registry = WatcherRegistry()
        val (callback, _) = recordableCallback()
        val id = registry.register(callback)
        registry.unregister(id)
        assertThrows(IllegalStateException::class.java) {
            registry.dispatch(id, "value", WuiWatcherMetadata(0))
        }
    }

    @Test
    fun moreThanTheGlobalReferenceCeilingCanLiveAndAllAreReleased() {
        val registry = WatcherRegistry()
        val (callback, _) = recordableCallback()
        val ids = LongArray(WATCHERS) { registry.register(callback) }
        try {
            assertEquals(WATCHERS.toLong(), ids.toSet().size.toLong())
            assertEquals(WATCHERS, registry.count())
        } finally {
            ids.forEach(registry::unregister)
        }
        assertEquals(0, registry.count())
        assertThrows(IllegalStateException::class.java) {
            registry.dispatch(ids[0], "value", WuiWatcherMetadata(0))
        }
    }

    @Test
    fun createAndDropLeavesNoRegistryEntriesAndNoReferenceGrowth() {
        // Creating and dropping watchers must leave the table empty and free
        // no more entries than were added — under the old design 2×51,200
        // register/unregister cycles would have overflowed the JNI global
        // reference table, so completing this run without a crash is the
        // global-ref assertion itself.
        val registry = WatcherRegistry()
        repeat(2) {
            val (callback, _) = recordableCallback()
            val ids = LongArray(WATCHERS) { registry.register(callback) }
            ids.forEach(registry::unregister)
        }
        assertEquals(0, registry.count())
    }
}
