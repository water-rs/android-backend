package dev.waterui.android.reactive

import android.os.Looper
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.DateStruct
import dev.waterui.android.runtime.DateTimeStruct
import dev.waterui.android.runtime.WatcherStruct
import dev.waterui.android.runtime.WuiAnimation

/**
 * Generic binding wrapper translated from the Swift implementation. Exposes
 * callback-based observation for Android views.
 */
class WuiBinding<T>(
    bindingPtr: Long,
    private val reader: (Long) -> T,
    private val writer: (Long, T) -> Unit,
    private val watcherFactory: (WatcherCallback<T>) -> WatcherStruct,
    private val watcherRegistrar: (Long, WatcherStruct) -> Long,
    private val dropper: (Long) -> Unit,
    private val valueReleaser: (T) -> Unit = {},
    private val valuesEqual: (T, T) -> Boolean = { left, right -> left == right },
    private val writerConsumesValue: Boolean = false
) : NativePointer(bindingPtr) {
    private val subscription = NativeSignalSubscription(
        read = { reader(raw()) },
        subscribe = { onValue ->
            val watcher = watcherFactory { value, metadata ->
                check(Looper.myLooper() === Looper.getMainLooper()) {
                    "WaterUI binding updates must run on the Android main thread"
                }
                onValue(value, metadata.animation)
            }
            val guardHandle = watcherRegistrar(raw(), watcher)
            check(guardHandle != 0L) {
                "WaterUI binding watcher registration returned a null guard"
            }
            WatcherGuard(guardHandle)
        },
        isOwnerReleased = { isReleased },
        releaseValue = valueReleaser,
        valuesEqual = valuesEqual
    )

    fun observe(onValue: (T) -> Unit) {
        observeWithAnimation { value, _ -> onValue(value) }
    }

    fun observeWithAnimation(onValue: (T, WuiAnimation) -> Unit) {
        subscription.observe(onValue)
    }

    fun clearObserver() {
        subscription.clearObserver()
    }

    fun set(value: T) {
        check(!isReleased) { "cannot update a released WaterUI binding" }
        if (subscription.isWatching && subscription.currentMatches(value)) return

        writer(raw(), value)
        if (!writerConsumesValue && !isReleased && !subscription.isWatching) {
            subscription.acceptLocal(value)
        }
    }

    override fun close() {
        val bindingPtr = takeRaw()
        subscription.close()
        release(bindingPtr)
    }

    override fun release(ptr: Long) {
        dropper(ptr)
    }

    companion object {
        fun bool(bindingPtr: Long): WuiBinding<Boolean> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingBool(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingBool(ptr, value) },
                watcherFactory = WatcherJni::createBoolWatcher,
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingBool(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingBool(ptr) }
            )

        fun int(bindingPtr: Long): WuiBinding<Int> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingInt(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingInt(ptr, value) },
                watcherFactory = WatcherJni::createIntWatcher,
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingInt(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingInt(ptr) }
            )

        fun id(bindingPtr: Long): WuiBinding<Int> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = WatcherJni::readBindingId,
                writer = WatcherJni::setBindingId,
                watcherFactory = WatcherJni::createIdWatcher,
                watcherRegistrar = WatcherJni::watchBindingId,
                dropper = WatcherJni::dropBindingId
            )

        fun double(bindingPtr: Long): WuiBinding<Double> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingDouble(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingDouble(ptr, value) },
                watcherFactory = WatcherJni::createDoubleWatcher,
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingDouble(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingDouble(ptr) }
            )

        fun str(bindingPtr: Long): WuiBinding<String> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingStr(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingStr(ptr, value) },
                watcherFactory = WatcherJni::createStringWatcher,
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingStr(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingStr(ptr) }
            )

        fun styledPlain(bindingPtr: Long): WuiBinding<String> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = WatcherJni::readBindingStyledStrPlain,
                writer = WatcherJni::setBindingStyledStrPlain,
                watcherFactory = WatcherJni::createStyledStrPlainWatcher,
                watcherRegistrar = WatcherJni::watchBindingStyledStr,
                dropper = WatcherJni::dropBindingStyledStr
            )

        fun secure(bindingPtr: Long): WuiBinding<String> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = WatcherJni::readBindingSecure,
                writer = WatcherJni::setBindingSecure,
                watcherFactory = WatcherJni::createSecureWatcher,
                watcherRegistrar = WatcherJni::watchBindingSecure,
                dropper = WatcherJni::dropBindingSecure
            )

        fun color(bindingPtr: Long): WuiBinding<Long> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingColor(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingColor(ptr, value) },
                watcherFactory = WatcherJni::createColorWatcher,
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingColor(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingColor(ptr) },
                valueReleaser = WatcherJni::dropColor,
                valuesEqual = { _, _ -> false },
                writerConsumesValue = true
            )

        fun dateTime(bindingPtr: Long): WuiBinding<DateTimeStruct> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingDateTime(ptr) },
                writer = { ptr, value ->
                    WatcherJni.setBindingDateTime(
                        ptr,
                        value.year,
                        value.month,
                        value.day,
                        value.hour,
                        value.minute,
                        value.second
                    )
                },
                watcherFactory = WatcherJni::createDateTimeWatcher,
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingDateTime(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingDateTime(ptr) }
            )

        fun dateVec(bindingPtr: Long): WuiBinding<Array<DateStruct>> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingDateVec(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingDateVec(ptr, value) },
                watcherFactory = WatcherJni::createDateVecWatcher,
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingDateVec(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingDateVec(ptr) },
                valuesEqual = { left, right -> left.contentEquals(right) }
            )
    }
}
