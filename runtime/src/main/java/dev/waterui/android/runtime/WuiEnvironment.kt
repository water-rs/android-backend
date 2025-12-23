package dev.waterui.android.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Android counterpart to the WaterUI environment handle. Responsible for owning the native pointer
 * and providing a lifecycle-aware coroutine scope for reactive updates.
 *
 * ## Ownership Modes
 *
 * - **Owned**: Created via [create] or public constructor. Will drop the native pointer on [close].
 * - **Borrowed**: Created via [borrowed]. Will NOT drop the native pointer on [close].
 *
 * Use [borrowed] when you need a WuiEnvironment wrapper for a pointer that is owned elsewhere
 * (e.g., the App's returned environment pointer which is dropped explicitly).
 */
class WuiEnvironment private constructor(
    envPtr: Long,
    private val isOwned: Boolean
) : NativePointer(envPtr) {

    /**
     * Public constructor for owned environments.
     * Creates a WuiEnvironment that WILL drop the native pointer on close.
     */
    constructor(envPtr: Long) : this(envPtr, true)

    companion object {
        /**
         * Creates a new owned environment via waterui_init().
         * The returned environment will drop the native pointer on close.
         */
        fun create(): WuiEnvironment {
            val envPtr = NativeBindings.waterui_init()
            // Install media loader so Selected::load() works
            NativeBindings.waterui_env_install_media_picker_manager(envPtr)
            NativeBindings.waterui_env_install_webview_controller(envPtr)
            return WuiEnvironment(envPtr, isOwned = true)
        }

        /**
         * Creates a borrowed view of an environment pointer.
         * The returned environment will NOT drop the native pointer on close.
         *
         * Use this when wrapping a pointer that is owned elsewhere, such as
         * the App's returned environment pointer which is dropped explicitly.
         */
        fun borrowed(envPtr: Long): WuiEnvironment {
            return WuiEnvironment(envPtr, isOwned = false)
        }
    }

    /** Coroutine scope tied to the environment lifetime for asynchronous watchers. */
    // Use Dispatchers.Main (not .immediate) to avoid deadlocks when JNI callbacks
    // arrive on background threads - .immediate blocks if not on main thread
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun clone(): WuiEnvironment {
        val cloned = NativeBindings.waterui_clone_env(raw())
        return WuiEnvironment(cloned, isOwned = true)
    }

    override fun close() {
        super.close()
        scope.cancel()
    }

    override fun release(ptr: Long) {
        // Only drop if we own the pointer
        if (isOwned) {
            NativeBindings.waterui_env_drop(ptr)
        }
    }
}
