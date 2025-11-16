package dev.waterui.android.reactive

import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.StyledStrStruct
import dev.waterui.android.runtime.WatcherStruct
import dev.waterui.android.runtime.WuiEnvironment

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
    @Suppress("unused") private val env: WuiEnvironment
) : NativePointer(bindingPtr) {

    private var watcherGuard: WatcherGuard? = null
    private var syncingFromRust = false
    private var observer: ((T) -> Unit)? = null
    private var currentValue: T = reader(bindingPtr)

    fun current(): T = currentValue

    fun observe(onValue: (T) -> Unit) {
        observer = onValue
        onValue(currentValue)
        ensureWatcher()
    }

    private fun ensureWatcher() {
        if (watcherGuard != null || isReleased) return
        val watcher = watcherFactory(raw()) { value, _ ->
            syncingFromRust = true
            currentValue = value
            observer?.invoke(value)
            syncingFromRust = false
        }
        watcherGuard = WatcherGuard(watcherRegistrar(raw(), watcher))
    }

    fun set(value: T) {
        currentValue = value
        observer?.invoke(value)
        if (!syncingFromRust) {
            writer(raw(), value)
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
                reader = NativeBindings::waterui_read_binding_bool,
                writer = { ptr, value -> NativeBindings.waterui_set_binding_bool(ptr, value) },
                watcherFactory = { _, callback -> WatcherStructFactory.bool(callback) },
                watcherRegistrar = NativeBindings::waterui_watch_binding_bool,
                dropper = NativeBindings::waterui_drop_binding_bool,
                env = env
            )

        fun int(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Int> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = NativeBindings::waterui_read_binding_int,
                writer = { ptr, value -> NativeBindings.waterui_set_binding_int(ptr, value) },
                watcherFactory = { _, callback -> WatcherStructFactory.int(callback) },
                watcherRegistrar = NativeBindings::waterui_watch_binding_int,
                dropper = NativeBindings::waterui_drop_binding_int,
                env = env
            )

        fun double(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Double> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = NativeBindings::waterui_read_binding_double,
                writer = { ptr, value -> NativeBindings.waterui_set_binding_double(ptr, value) },
                watcherFactory = { _, callback -> WatcherStructFactory.double(callback) },
                watcherRegistrar = NativeBindings::waterui_watch_binding_double,
                dropper = NativeBindings::waterui_drop_binding_double,
                env = env
            )

        fun str(bindingPtr: Long, env: WuiEnvironment): WuiBinding<String> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr ->
                    val bytes = NativeBindings.waterui_read_binding_str(ptr)
                    bytes.decodeToString()
                },
                writer = { ptr, value ->
                    NativeBindings.waterui_set_binding_str(ptr, value.encodeToByteArray())
                },
                watcherFactory = { _, callback -> WatcherStructFactory.string(callback) },
                watcherRegistrar = NativeBindings::waterui_watch_binding_str,
                dropper = NativeBindings::waterui_drop_binding_str,
                env = env
            )
    }
}

/**
 * Produces watcher struct handles backed by JNI trampolines.
 */
object WatcherStructFactory {
    fun bool(callback: WatcherCallback<Boolean>): WatcherStruct {
        return NativeBindings.waterui_create_bool_watcher(callback)
    }

    fun int(callback: WatcherCallback<Int>): WatcherStruct {
        return NativeBindings.waterui_create_int_watcher(callback)
    }

    fun double(callback: WatcherCallback<Double>): WatcherStruct {
        return NativeBindings.waterui_create_double_watcher(callback)
    }

    fun string(callback: WatcherCallback<String>): WatcherStruct {
        return NativeBindings.waterui_create_string_watcher(callback)
    }

    fun anyView(callback: WatcherCallback<Long>): WatcherStruct {
        return NativeBindings.waterui_create_any_view_watcher(callback)
    }

    fun styledString(callback: WatcherCallback<StyledStrStruct>): WatcherStruct {
        return NativeBindings.waterui_create_styled_str_watcher(callback)
    }

    fun resolvedColor(callback: WatcherCallback<ResolvedColorStruct>): WatcherStruct {
        return NativeBindings.waterui_create_resolved_color_watcher(callback)
    }
}
