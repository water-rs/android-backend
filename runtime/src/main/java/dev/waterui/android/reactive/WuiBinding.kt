package dev.waterui.android.reactive

import android.os.Looper
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.DateStruct
import dev.waterui.android.runtime.DateTimeStruct
import dev.waterui.android.runtime.WatcherStruct
import dev.waterui.android.runtime.WuiAnimation
import dev.waterui.android.runtime.WuiEnvironment

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

    /** The value the Rust side currently holds. */
    fun get(): T = reader(raw())

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
        fun bool(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Boolean> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingBool(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingBool(ptr, value) },
                watcherFactory = { cb -> WatcherJni.createBoolWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingBool(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingBool(ptr) }
            )

        fun int(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Int> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingInt(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingInt(ptr, value) },
                watcherFactory = { cb -> WatcherJni.createIntWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingInt(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingInt(ptr) }
            )

        /// `Binding<AnchorEdge>` crosses as the `WuiAnchorEdge` ordinal: an
        /// `Int` on the Kotlin side with the int watcher shape.
        fun anchorEdge(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Int> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingAnchorEdge(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingAnchorEdge(ptr, value) },
                watcherFactory = { cb -> WatcherJni.createIntWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingAnchorEdge(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingAnchorEdge(ptr) }
            )

        fun id(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Int> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = WatcherJni::readBindingId,
                writer = WatcherJni::setBindingId,
                watcherFactory = { cb -> WatcherJni.createIdWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = WatcherJni::watchBindingId,
                dropper = WatcherJni::dropBindingId
            )

        /// `Binding<Set<Id>>` crosses as an erased-id vector, so the Kotlin
        /// side sees a plain `IntArray` of `WuiId`s.
        fun idVec(bindingPtr: Long, env: WuiEnvironment): WuiBinding<IntArray> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = WatcherJni::readBindingIdVec,
                writer = WatcherJni::setBindingIdVec,
                watcherFactory = { cb -> WatcherJni.createIdVecWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = WatcherJni::watchBindingIdVec,
                dropper = WatcherJni::dropBindingIdVec,
                valuesEqual = IntArray::contentEquals
            )

        fun double(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Double> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingDouble(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingDouble(ptr, value) },
                watcherFactory = { cb -> WatcherJni.createDoubleWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingDouble(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingDouble(ptr) }
            )

        fun str(bindingPtr: Long, env: WuiEnvironment): WuiBinding<String> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingStr(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingStr(ptr, value) },
                watcherFactory = { cb -> WatcherJni.createStringWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingStr(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingStr(ptr) }
            )

        fun styledPlain(bindingPtr: Long, env: WuiEnvironment): WuiBinding<String> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = WatcherJni::readBindingStyledStrPlain,
                writer = WatcherJni::setBindingStyledStrPlain,
                watcherFactory = { cb -> WatcherJni.createStyledStrPlainWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = WatcherJni::watchBindingStyledStr,
                dropper = WatcherJni::dropBindingStyledStr
            )

        fun secure(bindingPtr: Long, env: WuiEnvironment): WuiBinding<String> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = WatcherJni::readBindingSecure,
                writer = WatcherJni::setBindingSecure,
                watcherFactory = { cb -> WatcherJni.createSecureWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = WatcherJni::watchBindingSecure,
                dropper = WatcherJni::dropBindingSecure
            )

        fun color(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Long> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingColor(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingColor(ptr, value) },
                watcherFactory = { cb -> WatcherJni.createColorWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingColor(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingColor(ptr) },
                valueReleaser = WatcherJni::dropColor,
                valuesEqual = { _, _ -> false },
                writerConsumesValue = true
            )

        fun dateTime(bindingPtr: Long, env: WuiEnvironment): WuiBinding<DateTimeStruct> =
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
                watcherFactory = { cb -> WatcherJni.createDateTimeWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingDateTime(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingDateTime(ptr) }
            )

        fun dateVec(bindingPtr: Long, env: WuiEnvironment): WuiBinding<Array<DateStruct>> =
            WuiBinding(
                bindingPtr = bindingPtr,
                reader = { ptr -> WatcherJni.readBindingDateVec(ptr) },
                writer = { ptr, value -> WatcherJni.setBindingDateVec(ptr, value) },
                watcherFactory = { cb -> WatcherJni.createDateVecWatcher(env.watcherContextPtr, cb) },
                watcherRegistrar = { ptr, watcher -> WatcherJni.watchBindingDateVec(ptr, watcher) },
                dropper = { ptr -> WatcherJni.dropBindingDateVec(ptr) },
                valuesEqual = { left, right -> left.contentEquals(right) }
            )
    }
}
