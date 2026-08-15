package dev.waterui.android.runtime

/**
 * Android counterpart to the WaterUI environment handle. Responsible for owning the native pointer.
 */
class WuiEnvironment(
    envPtr: Long
) : NativePointer(envPtr) {
    /**
     * Pixels per sp unit for the hosting activity, stamped by the root view
     * and inherited by every environment derived from it. Text sizes travel
     * through the theme bridge in sp; zero means the root never stamped it.
     */
    var pxPerSp: Float = 0f

    companion object {
        fun create(): WuiEnvironment {
            val envPtr = NativeBindings.waterui_init()
            return WuiEnvironment(envPtr)
        }
    }

    fun clone(): WuiEnvironment {
        val cloned = NativeBindings.waterui_clone_env(raw())
        return WuiEnvironment(cloned).also { it.pxPerSp = pxPerSp }
    }

    fun requirePxPerSp(): Float {
        check(pxPerSp > 0f) {
            "WaterUI environment has no pxPerSp; the root view must stamp it before rendering"
        }
        return pxPerSp
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_env_drop(ptr)
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
