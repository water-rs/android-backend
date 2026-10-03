package dev.waterui.android.runtime

import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.reactive.WatcherRegistry

/**
 * Android counterpart to the WaterUI environment handle. Responsible for owning the native pointer.
 */
class WuiEnvironment(
    envPtr: Long,
    /**
     * The declared-font table the runtime owner built at bootstrap, inherited
     * by every environment cloned or wrapped from this one — the same
     * propagation rule as [pxPerSp]. An app that declares no fonts carries an
     * empty table, never `null`.
     */
    val fontTable: WaterUiFontTable
) : NativePointer(envPtr) {
    /**
     * Pixels per sp unit for the hosting activity, stamped by the root view
     * and inherited by every environment derived from it. Text sizes travel
     * through the theme bridge in sp; zero means the root never stamped it.
     */
    var pxPerSp: Float = 0f

    /**
     * Watcher registry owned by this runtime. Native code reaches it through
     * one global ref held in the watcher context, never by a static call.
     */
    val watcherRegistry = WatcherRegistry()

    /**
     * Native handle of this runtime's watcher context: JavaVM, the cached
     * value-class constructors and [watcherRegistry]'s method ids. Passed as
     * `contextPtr` to every `WatcherJni.create*Watcher` call.
     */
    val watcherContextPtr: Long = WatcherJni.initWatcherContext(watcherRegistry)

    companion object {
        fun create(fontTable: WaterUiFontTable): WuiEnvironment {
            val envPtr = NativeBindings.waterui_init()
            return WuiEnvironment(envPtr, fontTable)
        }
    }

    fun clone(): WuiEnvironment {
        val cloned = NativeBindings.waterui_clone_env(raw())
        return WuiEnvironment(cloned, fontTable).also {
            it.pxPerSp = pxPerSp
        }
    }

    fun requirePxPerSp(): Float {
        check(pxPerSp > 0f) {
            "WaterUI environment has no pxPerSp; the root view must stamp it before rendering"
        }
        return pxPerSp
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_env_drop(ptr)
        // env_drop runs watcher unregistration through the context, so the
        // context must outlive it.
        WatcherJni.dropWatcherContext(watcherContextPtr)
    }
}

/**
 * Process-scoped owner of WaterUI runtime initialization.
 *
 * Android can replace an Activity while keeping its Application process alive.
 * The Application initializes Rust once and hands each Activity an independent
 * environment cloned from that process-owned template.
 */
interface WaterUiRuntimeOwner {
    fun createWaterUiEnvironment(): WuiEnvironment
}
