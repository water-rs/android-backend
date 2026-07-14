package dev.waterui.android.runtime

/**
 * Android counterpart to the WaterUI environment handle. Responsible for owning the native pointer.
 */
class WuiEnvironment(
    envPtr: Long
) : NativePointer(envPtr) {
    companion object {
        fun create(): WuiEnvironment {
            val envPtr = NativeBindings.waterui_init()
            return WuiEnvironment(envPtr)
        }
    }

    fun clone(): WuiEnvironment {
        val cloned = NativeBindings.waterui_clone_env(raw())
        return WuiEnvironment(cloned)
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_env_drop(ptr)
    }
}
