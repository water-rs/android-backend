package dev.waterui.android.reactive

import dev.waterui.android.runtime.WuiAnimation
import java.io.Closeable

private sealed interface StoredSignalValue<out T> {
    data object Empty : StoredSignalValue<Nothing>

    data class Present<T>(val value: T) : StoredSignalValue<T>
}

/** Installs a native watcher before reading the initial signal value. */
internal class NativeSignalSubscription<T>(
    private val read: () -> T,
    private val subscribe: ((T, WuiAnimation) -> Unit) -> Closeable,
    private val isOwnerReleased: () -> Boolean,
    private val releaseValue: (T) -> Unit,
    private val valuesEqual: (T, T) -> Boolean
) : Closeable {
    private var watcher: Closeable? = null
    private var watching = false
    private var observer: ((T, WuiAnimation) -> Unit)? = null
    private var observerHasValue = false
    private var current: StoredSignalValue<T> = StoredSignalValue.Empty

    val isWatching: Boolean get() = watching

    fun observe(onValue: (T, WuiAnimation) -> Unit) {
        check(!isOwnerReleased()) { "cannot observe a released WaterUI signal" }
        observer = onValue
        observerHasValue = false

        if (!watching) {
            watching = true
            val installedWatcher = subscribe(::accept)
            if (isOwnerReleased()) {
                installedWatcher.close()
                return
            }
            watcher = installedWatcher
            if (current is StoredSignalValue.Empty) {
                accept(read(), WuiAnimation.None)
            }
        } else {
            emitCurrent(WuiAnimation.None)
        }
    }

    fun clearObserver() {
        check(!isOwnerReleased()) { "cannot clear a released WaterUI signal observer" }
        observer = null
        observerHasValue = false
    }

    fun currentMatches(value: T): Boolean = when (val stored = current) {
        StoredSignalValue.Empty -> false
        is StoredSignalValue.Present -> valuesEqual(stored.value, value)
    }

    fun acceptLocal(value: T) {
        accept(value, WuiAnimation.None)
    }

    private fun accept(value: T, animation: WuiAnimation) {
        if (isOwnerReleased()) {
            releaseValue(value)
            return
        }

        val previous = current
        if (previous is StoredSignalValue.Present && valuesEqual(previous.value, value)) {
            releaseValue(value)
            if (!observerHasValue) {
                emitCurrent(animation)
            }
            return
        }

        current = StoredSignalValue.Present(value)
        try {
            val callback = observer
            if (callback != null) {
                observerHasValue = true
                callback(value, animation)
            }
        } finally {
            if (previous is StoredSignalValue.Present) {
                releaseValue(previous.value)
            }
        }
    }

    private fun emitCurrent(animation: WuiAnimation) {
        val stored = current
        check(stored is StoredSignalValue.Present) {
            "native signal watcher did not provide a value and the initial read was skipped"
        }
        val callback = observer ?: return
        observerHasValue = true
        callback(stored.value, animation)
    }

    override fun close() {
        observer = null
        observerHasValue = false
        watcher?.close()
        watcher = null
        watching = false
        val stored = current
        current = StoredSignalValue.Empty
        if (stored is StoredSignalValue.Present) {
            releaseValue(stored.value)
        }
    }
}
