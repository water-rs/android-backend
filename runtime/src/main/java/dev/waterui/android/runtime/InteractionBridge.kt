package dev.waterui.android.runtime

import dev.waterui.android.reactive.WuiComputed

/**
 * Reads scoped interaction state out of a WaterUI environment.
 *
 * Interaction state such as `disabled` is a scoped subtree attribute installed
 * by `.disabled(...)`, never a field on an individual control's configuration.
 * Controls read it from the environment they are already handed, exactly the way
 * they read a theme color through [ThemeBridge].
 */
object InteractionBridge {

    /**
     * Returns the disabled signal in force at this point in the view tree.
     *
     * The signal reads `false` when no enclosing scope disables the subtree.
     */
    fun disabled(env: WuiEnvironment): WuiComputed<Boolean> {
        val ptr = NativeBindings.waterui_env_disabled(env.raw())
        check(ptr != 0L) { "WaterUI environment has no disabled signal" }
        return WuiComputed.bool(ptr)
    }
}
