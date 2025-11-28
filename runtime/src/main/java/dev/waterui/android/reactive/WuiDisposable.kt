package dev.waterui.android.reactive

/**
 * A disposable resource that can be cleaned up.
 * 
 * Used for managing watcher subscriptions and other resources
 * that need explicit cleanup.
 */
fun interface WuiDisposable {
    fun dispose()
    
    companion object {
        /** A no-op disposable that does nothing when disposed */
        val EMPTY = WuiDisposable { }
    }
}

/**
 * Internal state holder for Kotlin-created signals.
 * 
 * When Kotlin creates a WuiComputed via `waterui_new_computed_*`, it passes
 * callbacks that Rust will invoke. This class holds those callbacks and
 * manages their lifecycle.
 * 
 * @param getValue Called by Rust to get the current value
 * @param watchValue Called by Rust to register a watcher, returns a disposable
 * @param onDrop Called by Rust when the signal is dropped
 */
class KotlinState<T>(
    private val getValue: () -> T,
    private val watchValue: (onChanged: (T) -> Unit) -> WuiDisposable,
    private val onDrop: () -> Unit
) {
    private val watchers = mutableListOf<WuiDisposable>()
    
    /** Called by Rust via FFI to get the current value */
    fun get(): T = getValue()
    
    /** Called by Rust via FFI to register a watcher */
    fun watch(onChanged: (T) -> Unit): WuiDisposable {
        val disposable = watchValue(onChanged)
        watchers.add(disposable)
        return disposable
    }
    
    /** Called by Rust via FFI when the signal is dropped */
    fun drop() {
        // Dispose all watchers
        watchers.forEach { it.dispose() }
        watchers.clear()
        // Call user-provided cleanup
        onDrop()
    }
}

