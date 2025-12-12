package dev.waterui.android.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Android counterpart to the WaterUI environment handle. Responsible for owning the native pointer
 * and providing a lifecycle-aware coroutine scope for reactive updates.
 */
class WuiEnvironment(
    envPtr: Long
) : NativePointer(envPtr) {
    companion object {
        fun create(): WuiEnvironment {
            val envPtr = NativeBindings.waterui_init()
            // Install media loader so Selected::load() works
            NativeBindings.waterui_env_install_media_loader(envPtr)
            return WuiEnvironment(envPtr)
        }
    }

    /** Coroutine scope tied to the environment lifetime for asynchronous watchers. */
    // Use Dispatchers.Main (not .immediate) to avoid deadlocks when JNI callbacks
    // arrive on background threads - .immediate blocks if not on main thread
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun clone(): WuiEnvironment {
        val cloned = NativeBindings.waterui_clone_env(raw())
        return WuiEnvironment(cloned)
    }

    override fun close() {
        super.close()
        scope.cancel()
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_env_drop(ptr)
    }
}
