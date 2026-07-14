package dev.waterui.android.reactive

import android.os.Looper
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.DateStruct
import dev.waterui.android.runtime.NativePointer
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.ResolvedFontStruct
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
    private val watcherFactory: (WatcherCallback<T>) -> WatcherStruct,
    private val watcherRegistrar: (Long, WatcherStruct) -> Long,
    private val dropper: (Long) -> Unit,
    private val valueReleaser: (T) -> Unit = {}
) : NativePointer(computedPtr) {

    private val subscription = NativeSignalSubscription(
        read = { reader(raw()) },
        subscribe = { onValue ->
            val watcher = watcherFactory { value, metadata ->
                check(Looper.myLooper() === Looper.getMainLooper()) {
                    "WaterUI computed updates must run on the Android main thread"
                }
                onValue(value, metadata.animation)
            }
            val guardHandle = watcherRegistrar(raw(), watcher)
            check(guardHandle != 0L) {
                "WaterUI computed watcher registration returned a null guard"
            }
            WatcherGuard(guardHandle)
        },
        isOwnerReleased = { isReleased },
        releaseValue = valueReleaser,
        valuesEqual = { left, right -> left == right }
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

    override fun close() {
        val computedPtr = takeRaw()
        subscription.close()
        release(computedPtr)
    }

    override fun release(ptr: Long) {
        dropper(ptr)
    }

    companion object {
        fun bool(ptr: Long): WuiComputed<Boolean> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedBool(p) },
                watcherFactory = WatcherJni::createBoolWatcher,
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedBool(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedBool(p) }
            )

        fun double(ptr: Long): WuiComputed<Double> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedF64(p) },
                watcherFactory = WatcherJni::createDoubleWatcher,
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedF64(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedF64(p) }
            )

        fun float(ptr: Long): WuiComputed<Float> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedF32(p) },
                watcherFactory = WatcherJni::createFloatWatcher,
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedF32(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedF32(p) }
            )

        fun styledString(ptr: Long): WuiComputed<WuiStyledStr> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p ->
                    WatcherJni.readComputedStyledStr(p).toModel()
                },
                watcherFactory = { callback ->
                    WatcherJni.createStyledStrWatcher { struct, metadata ->
                        callback.onChanged(struct.toModel(), metadata)
                    }
                },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedStyledStr(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedStyledStr(p) },
                valueReleaser = { it.close() }
            )

        fun resolvedColor(colorPtr: Long, env: WuiEnvironment): WuiComputed<ResolvedColorStruct> {
            val computedPtr = WatcherJni.resolveColor(colorPtr, env.raw())
            return WuiComputed(
                computedPtr = computedPtr,
                reader = { p -> WatcherJni.readComputedResolvedColor(p) },
                watcherFactory = WatcherJni::createResolvedColorWatcher,
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedResolvedColor(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedResolvedColor(p) }
            )
        }

        fun dateVec(ptr: Long): WuiComputed<List<DateStruct>> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedDateVec(p).toList() },
                watcherFactory = { callback ->
                    WatcherJni.createDateVecWatcher { array, metadata ->
                        callback.onChanged(array.toList(), metadata)
                    }
                },
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedDateVec(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedDateVec(p) }
            )

        fun int(ptr: Long): WuiComputed<Int> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedI32(p) },
                watcherFactory = WatcherJni::createIntWatcher,
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedI32(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedI32(p) }
            )

        fun colorScheme(ptr: Long): WuiComputed<Int> =
            WuiComputed(
                computedPtr = ptr,
                reader = WatcherJni::readComputedColorScheme,
                watcherFactory = WatcherJni::createColorSchemeWatcher,
                watcherRegistrar = WatcherJni::watchComputedColorScheme,
                dropper = WatcherJni::dropComputedColorScheme
            )

        fun cursorStyle(ptr: Long): WuiComputed<Int> =
            WuiComputed(
                computedPtr = ptr,
                reader = WatcherJni::readComputedCursorStyle,
                watcherFactory = WatcherJni::createCursorStyleWatcher,
                watcherRegistrar = WatcherJni::watchComputedCursorStyle,
                dropper = WatcherJni::dropComputedCursorStyle
            )

        fun horizontalAlignment(ptr: Long): WuiComputed<Int> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedHorizontalAlignment(p) },
                watcherFactory = WatcherJni::createHorizontalAlignmentWatcher,
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedHorizontalAlignment(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedHorizontalAlignment(p) }
            )

        fun colorFromComputed(ptr: Long): WuiComputed<ResolvedColorStruct> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedResolvedColor(p) },
                watcherFactory = WatcherJni::createResolvedColorWatcher,
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedResolvedColor(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedResolvedColor(p) }
            )

        fun fontFromComputed(ptr: Long): WuiComputed<ResolvedFontStruct> =
            WuiComputed(
                computedPtr = ptr,
                reader = { p -> WatcherJni.readComputedResolvedFont(p) },
                watcherFactory = WatcherJni::createResolvedFontWatcher,
                watcherRegistrar = { p, watcher -> WatcherJni.watchComputedResolvedFont(p, watcher) },
                dropper = { p -> WatcherJni.dropComputedResolvedFont(p) }
            )

    }
}
