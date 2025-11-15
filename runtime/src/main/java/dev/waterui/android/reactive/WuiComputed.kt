package dev.waterui.android.reactive

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.WatcherStruct
import dev.waterui.android.runtime.WuiEnvironment
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
    private val stateDelegate = mutableStateOf(currentValue)
    private var watcherGuard: WatcherGuard? = null

    val state: State<T> get() = stateDelegate

    fun watch() {
        if (watcherGuard != null || isReleased) return
        val watcher = watcherFactory(raw()) { value, metadata ->
            val previous = currentValue
            currentValue = value
            stateDelegate.value = value
            valueReleaser(previous)
            // TODO: Handle animation metadata (metadata.animation)
        }
        watcherGuard = WatcherGuard(watcherRegistrar(raw(), watcher))
    }

    override fun close() {
        if (!isReleased) {
            valueReleaser(currentValue)
        }
        super.close()
        watcherGuard?.close()
        watcherGuard = null
    }

    override fun release(ptr: Long) {
        dropper(ptr)
    }

    companion object {
        fun double(ptr: Long, env: WuiEnvironment): WuiComputed<Double> =
            WuiComputed(
                computedPtr = ptr,
                reader = NativeBindings::waterui_read_computed_f64,
                watcherFactory = { _, callback -> WatcherStructFactory.double(callback) },
                watcherRegistrar = NativeBindings::waterui_watch_computed_f64,
                dropper = NativeBindings::waterui_drop_computed_f64,
                env = env
            )

        fun styledString(ptr: Long, env: WuiEnvironment): WuiComputed<WuiStyledStr> =
            WuiComputed(
                computedPtr = ptr,
                reader = { computed ->
                    NativeBindings.waterui_read_computed_styled_str(computed).toModel()
                },
                watcherFactory = { _, callback ->
                    WatcherStructFactory.styledString { struct, metadata ->
                        callback.onChanged(struct.toModel(), metadata)
                    }
                },
                watcherRegistrar = NativeBindings::waterui_watch_computed_styled_str,
                dropper = NativeBindings::waterui_drop_computed_styled_str,
                env = env,
                valueReleaser = { it.close() }
            )

        fun resolvedColor(colorPtr: Long, env: WuiEnvironment): WuiComputed<ResolvedColorStruct> {
            val computedPtr = NativeBindings.waterui_resolve_color(colorPtr, env.raw())
            return WuiComputed(
                computedPtr = computedPtr,
                reader = NativeBindings::waterui_read_computed_resolved_color,
                watcherFactory = { _, callback -> WatcherStructFactory.resolvedColor(callback) },
                watcherRegistrar = NativeBindings::waterui_watch_computed_resolved_color,
                dropper = NativeBindings::waterui_drop_computed_resolved_color,
                env = env
            )
        }

        fun int(ptr: Long, env: WuiEnvironment): WuiComputed<Int> =
            WuiComputed(
                computedPtr = ptr,
                reader = NativeBindings::waterui_read_computed_i32,
                watcherFactory = { _, callback -> WatcherStructFactory.int(callback) },
                watcherRegistrar = NativeBindings::waterui_watch_computed_i32,
                dropper = NativeBindings::waterui_drop_computed_i32,
                env = env
            )
    }
}
