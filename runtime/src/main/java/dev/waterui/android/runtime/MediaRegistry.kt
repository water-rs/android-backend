package dev.waterui.android.runtime

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe registry for pending media selections.
 * Maps selection IDs to Android Uri objects.
 */
object MediaRegistry {
    private val nextId = AtomicInteger(1)
    private val pendingUris = ConcurrentHashMap<Int, Uri>()

    /**
     * Register a Uri and return its unique ID.
     */
    fun register(uri: Uri): Int {
        val id = nextId.getAndIncrement()
        pendingUris[id] = uri
        return id
    }

    /**
     * Get and remove a Uri by ID.
     */
    fun take(id: Int): Uri? {
        return pendingUris.remove(id)
    }
}
