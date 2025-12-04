package dev.waterui.android.reactive

import android.os.Handler
import android.os.Looper
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.PickerItemStruct
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.ResolvedFontStruct
import dev.waterui.android.runtime.VideoStruct
import dev.waterui.android.runtime.WatcherStruct
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiStyledStr
import dev.waterui.android.runtime.toModel

/**
 * Read-only reactive wrapper that mirrors WaterUI computed signals.
 */
class WuiComputed<T>(
    computedPtr: Long,
    private val reader: (Long) -> T,
    private val watcherFactory: (Long, WatcherCallback<T>) -> WatcherStruct,
    private val watcherRegistrar: (Long, WatcherStruct) -> Long,
    private val dropper: (Long) -> Unit,
    private val env: WuiEnvironment,
    private val valueReleaser: (T) -> Unit = {}
) : NativePointer(computedPtr) {

    private var currentValue: T = reader(computedPtr)
    private var watcherGuard: WatcherGuard? = null
    private var observer: ((T, WuiAnimation) -> Unit)? = null

    fun current(): T = currentValue

    fun observe(onValue: (T) -> Unit) {
        observeWithAnimation { value, _ -> onValue(value) }
    }

    fun observeWithAnimation(onValue: (T, WuiAnimation) -> Unit) {
        observer = onValue
        onValue(currentValue, WuiAnimation.NONE)
        ensureWatcher()
    }

    private fun ensureWatcher() {
        if (watcherGuard != null || isReleased) return
        android.util.Log.d("WaterUI.Computed", "ensureWatcher: creating watcher for ${this::class.simpleName}")
        // Use Handler to post to main thread - this ensures the callback returns immediately
        // even if called synchronously from Rust, preventing deadlocks
        val mainHandler = Handler(Looper.getMainLooper())
        val watcher = watcherFactory(raw()) { value, metadata ->
            android.util.Log.d("WaterUI.Computed", "ensureWatcher: watcher callback invoked on thread ${Thread.currentThread().name}")
            // IMPORTANT: Extract animation IMMEDIATELY before posting, because the metadata
            // pointer may become invalid after this callback returns to Rust
            val animation = metadata.animation
            
            // Post to main thread using Handler - this queues the message and returns immediately
            // This prevents deadlocks when Rust calls the callback synchronously during watch() registration
            mainHandler.post {
                android.util.Log.d("WaterUI.Computed", "ensureWatcher: handler posted, executing on main thread")
                val previous = currentValue
                currentValue = value
                observer?.invoke(value, animation)
                valueReleaser(previous)
                android.util.Log.d("WaterUI.Computed", "ensureWatcher: observer invoked")
            }
        }
        android.util.Log.d("WaterUI.Computed", "ensureWatcher: calling watcherRegistrar")
        val guardHandle = watcherRegistrar(raw(), watcher)
        android.util.Log.d("WaterUI.Computed", "ensureWatcher: watcherRegistrar returned $guardHandle")
        if (guardHandle != 0L) {
            watcherGuard = WatcherGuard(guardHandle)
        }
    }

    override fun close() {
        if (!isReleased) {
            valueReleaser(currentValue)
        }
        super.close()
        watcherGuard?.close()
        watcherGuard = null
        observer = null
    }

    override fun release(ptr: Long) {
        dropper(ptr)
    }

    companion object {
        fun double(ptr: Long, env: WuiEnvironment): WuiComputed<Double> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedF64(p) },
                watcherFactory = { _, callback -> WatcherStructFactory.double(callback) },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedF64(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedF64(p) },
                env = env
            )

        fun styledString(ptr: Long, env: WuiEnvironment): WuiComputed<WuiStyledStr> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p ->
                    WatcherJni.readComputedStyledStr(p).toModel()
                },
                watcherFactory = { _, callback ->
                    WatcherStructFactory.styledString { struct, metadata ->
                        callback.onChanged(struct.toModel(), metadata)
                    }
                },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedStyledStr(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedStyledStr(p) },
                env = env,
                valueReleaser = { it.close() }
            )

        fun resolvedColor(colorPtr: Long, env: WuiEnvironment): WuiComputed<ResolvedColorStruct> {
            val computedPtr = WatcherJni.resolveColor(colorPtr, env.raw())
            return WuiComputed(
                computedPtr = computedPtr,
                reader = { p -> WatcherJni.readComputedResolvedColor(p) },
                watcherFactory = { _, callback -> WatcherStructFactory.resolvedColor(callback) },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedResolvedColor(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedResolvedColor(p) },
                env = env
            )
        }

        fun pickerItems(ptr: Long, env: WuiEnvironment): WuiComputed<List<PickerItemStruct>> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p ->
                    WatcherJni.readComputedPickerItems(p).toList()
                },
                watcherFactory = { _, callback ->
                    WatcherStructFactory.pickerItems { array, metadata ->
                        callback.onChanged(array.toList(), metadata)
                    }
                },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedPickerItems(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedPickerItems(p) },
                env = env
            )

        fun int(ptr: Long, env: WuiEnvironment): WuiComputed<Int> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedI32(p) },
                watcherFactory = { _, callback -> WatcherStructFactory.int(callback) },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedI32(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedI32(p) },
                env = env
            )

        fun colorFromComputed(ptr: Long, env: WuiEnvironment): WuiComputed<ResolvedColorStruct> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedResolvedColor(p) },
                watcherFactory = { _, callback -> WatcherStructFactory.resolvedColor(callback) },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedResolvedColor(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedResolvedColor(p) },
                env = env
            )

        fun fontFromComputed(ptr: Long, env: WuiEnvironment): WuiComputed<ResolvedFontStruct> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedResolvedFont(p) },
                watcherFactory = { _, callback -> WatcherStructFactory.resolvedFont(callback) },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedResolvedFont(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedResolvedFont(p) },
                env = env
            )

        fun video(ptr: Long, env: WuiEnvironment): WuiComputed<VideoStruct> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedVideo(p) },
                watcherFactory = { _, callback -> WatcherStructFactory.video(callback) },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedVideo(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedVideo(p) },
                env = env
            )
    }
}
