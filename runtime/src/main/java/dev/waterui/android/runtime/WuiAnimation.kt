package dev.waterui.android.runtime

/**
 * Animation type parsed from FFI tagged union.
 * Provides full fidelity animation parameters from Rust.
 */
sealed class WuiAnimation {
    /** No animation - changes apply immediately */
    data object None : WuiAnimation()

    /** Default animation (0.25s ease-in-out) */
    data object Default : WuiAnimation()

    /** Linear animation with constant velocity */
    data class Linear(val duration: Long) : WuiAnimation()

    /** Ease-in animation that starts slow and accelerates */
    data class EaseIn(val duration: Long) : WuiAnimation()

    /** Ease-out animation that starts fast and decelerates */
    data class EaseOut(val duration: Long) : WuiAnimation()

    /** Ease-in-out animation that starts and ends slowly */
    data class EaseInOut(val duration: Long) : WuiAnimation()

    /** Spring animation with physics-based movement */
    data class Spring(val stiffness: Float, val damping: Float) : WuiAnimation()

    /** Returns duration in milliseconds for timed animations */
    val durationMs: Long
        get() = when (this) {
            is Linear -> duration
            is EaseIn -> duration
            is EaseOut -> duration
            is EaseInOut -> duration
            is Default -> 250L
            else -> 0L
        }

    /** Returns true if this is an animation that should be applied */
    val shouldAnimate: Boolean
        get() = this !is None

    companion object {
        /**
         * Constructs animation from FFI tag and parameters.
         * Tag values must match WuiAnimation_Tag enum in C header.
         */
        fun fromNative(tag: Int, duration: Long, stiffness: Float, damping: Float): WuiAnimation =
            when (tag) {
                0 -> None       // WuiAnimation_None
                1 -> Default    // WuiAnimation_Default
                2 -> Linear(duration)      // WuiAnimation_Linear
                3 -> EaseIn(duration)      // WuiAnimation_EaseIn
                4 -> EaseOut(duration)     // WuiAnimation_EaseOut
                5 -> EaseInOut(duration)   // WuiAnimation_EaseInOut
                6 -> Spring(stiffness, damping) // WuiAnimation_Spring
                else -> None
            }

        /**
         * Legacy compatibility - constructs from tag only (uses defaults for other params).
         */
        @Deprecated("Use fromNative(tag, durationMs, stiffness, damping) instead")
        fun fromNative(value: Int): WuiAnimation =
            fromNative(value, 250L, 0f, 0f)
    }
}
