package dev.waterui.android.reactive

import android.os.Handler
import android.os.Looper
import dev.waterui.android.ffi.PointerHelper
import dev.waterui.android.ffi.WaterUILib
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.PickerItemStruct
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.ResolvedFontStruct
import dev.waterui.android.runtime.StyledStrStruct
import dev.waterui.android.runtime.WatcherStruct
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiAnimation

/**
 * Generic binding wrapper translated from the Swift implementation. Exposes
 * callback-based observation for Android views.
 */
class WuiBinding<T>(
    bindingPtr: Long,
    private val reader: (Long) -> T,
    private val writer: (Long, T) -> Unit,
    private val watcherFactory: (Long, WatcherCallback<T>) -> WatcherStruct,
    private val watcherRegistrar: (Long, WatcherStruct) -> Long,
    private val dropper: (Long) -> Unit,
    private val env: WuiEnvironment
) : NativePointer(bindingPtr) {

    private var watcherGuard: WatcherGuard? = null
    private var syncingFromRust = false
    private var observer: ((T, WuiAnimation) -> Unit)? = null
    private var currentValue: T = reader(bindingPtr)

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
        // Use Handler to post to main thread - this ensures the callback returns immediately
        // even if called synchronously from Rust, preventing deadlocks
        val mainHandler = Handler(Looper.getMainLooper())
        val watcher = watcherFactory(raw()) { value, metadata ->
            // IMPORTANT: Extract animation IMMEDIATELY before posting, because the metadata
            // pointer may become invalid after this callback returns to Rust
            val animation = metadata.animation
            
            // Post to main thread using Handler - this queues the message and returns immediately
            // This prevents deadlocks when Rust calls the callback synchronously during watch() registration
            mainHandler.post {
                // Skip if value hasn't changed (prevents unnecessary UI updates)
                if (currentValue == value) return@post
                
                syncingFromRust = true
                try {
                    currentValue = value
                    observer?.invoke(value, animation)
                } finally {
                    syncingFromRust = false
                }
            }
        }
        val guardHandle = watcherRegistrar(raw(), watcher)
        if (guardHandle != 0L) {
            watcherGuard = WatcherGuard(guardHandle)
        }
    }

    private var isSettingValue = false
    
    fun set(value: T) {
        // Prevent feedback loops:
        // 1. If we're currently syncing from Rust, don't write back
        // 2. If we're already in the middle of a set() call, don't recurse
        // 3. If value hasn't changed, skip
        if (syncingFromRust || isSettingValue || currentValue == value) return
        
        isSettingValue = true
        try {
            currentValue = value
            // Notify observer immediately so UI updates
            observer?.invoke(value, WuiAnimation.NONE)
            // Write to Rust
            writer(raw(), value)
        } finally {
            isSettingValue = false
        }
    }

    override fun close() {
        super.close()
        watcherGuard?.close()
        watcherGuard = null
        observer = null
    }

    override fun release(ptr: Long) {
        dropper(ptr)
    }

    companion object {
        fun bool(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Boolean> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WaterUILib.waterui_read_binding_bool(PointerHelper.fromAddress(ptr)) },
                writer = { ptr, value -> WaterUILib.waterui_set_binding_bool(PointerHelper.fromAddress(ptr), value) },
                watcherFactory = { _, callback -> WatcherStructFactory.bool(callback) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingBool(ptr, watcher) },
                dropper = { ptr -> WaterUILib.waterui_drop_binding_bool(PointerHelper.fromAddress(ptr)) },
                env = env
            )

        fun int(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Int> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WaterUILib.waterui_read_binding_i32(PointerHelper.fromAddress(ptr)) },
                writer = { ptr, value -> WaterUILib.waterui_set_binding_i32(PointerHelper.fromAddress(ptr), value) },
                watcherFactory = { _, callback -> WatcherStructFactory.int(callback) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingInt(ptr, watcher) },
                dropper = { ptr -> WaterUILib.waterui_drop_binding_i32(PointerHelper.fromAddress(ptr)) },
                env = env
            )

        fun double(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Double> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WaterUILib.waterui_read_binding_f64(PointerHelper.fromAddress(ptr)) },
                writer = { ptr, value -> WaterUILib.waterui_set_binding_f64(PointerHelper.fromAddress(ptr), value) },
                watcherFactory = { _, callback -> WatcherStructFactory.double(callback) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingDouble(ptr, watcher) },
                dropper = { ptr -> WaterUILib.waterui_drop_binding_f64(PointerHelper.fromAddress(ptr)) },
                env = env
            )

        fun str(bindingPtr: Long, env: WuiEnvironment): WuiBinding<String> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr ->
                    val bytes = WatcherJni.readBindingStr(ptr)
                    bytes.decodeToString()
                },
                writer = { ptr, value ->
                    WatcherJni.setBindingStr(ptr, value.encodeToByteArray())
                },
                watcherFactory = { _, callback -> WatcherStructFactory.string(callback) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingStr(ptr, watcher) },
                dropper = { ptr -> WaterUILib.waterui_drop_binding_str(PointerHelper.fromAddress(ptr)) },
                env = env
            )
    }
}

/**
 * Produces watcher struct handles backed by JNI trampolines.
 */
object WatcherStructFactory {
    fun bool(callback: WatcherCallback<Boolean>): WatcherStruct {
        return WatcherJni.createBoolWatcher(callback)
    }

    fun int(callback: WatcherCallback<Int>): WatcherStruct {
        return WatcherJni.createIntWatcher(callback)
    }

    fun double(callback: WatcherCallback<Double>): WatcherStruct {
        return WatcherJni.createDoubleWatcher(callback)
    }

    fun string(callback: WatcherCallback<String>): WatcherStruct {
        return WatcherJni.createStringWatcher(callback)
    }

    fun anyView(callback: WatcherCallback<Long>): WatcherStruct {
        return WatcherJni.createAnyViewWatcher(callback)
    }

    fun styledString(callback: WatcherCallback<StyledStrStruct>): WatcherStruct {
        return WatcherJni.createStyledStrWatcher(callback)
    }

    fun resolvedColor(callback: WatcherCallback<ResolvedColorStruct>): WatcherStruct {
        return WatcherJni.createResolvedColorWatcher(callback)
    }

    fun resolvedFont(callback: WatcherCallback<ResolvedFontStruct>): WatcherStruct {
        return WatcherJni.createResolvedFontWatcher(callback)
    }

    fun pickerItems(callback: WatcherCallback<Array<PickerItemStruct>>): WatcherStruct {
        return WatcherJni.createPickerItemsWatcher(callback)
    }
}
