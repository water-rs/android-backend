package dev.waterui.android.runtime

/**
 * Animation type parsed from FFI tagged union.
 * Provides full fidelity animation parameters from Rust.
 */
sealed class WuiAnimation {
    /** No animation - changes apply immediately */
    data object None : WuiAnimation()

    /** Timed cubic bezier animation. */
    data class Bezier(
        val duration: Long,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    ) : WuiAnimation()

    /** Spring animation with physics-based movement */
    data class Spring(val stiffness: Float, val damping: Float) : WuiAnimation()

    /** Returns duration in milliseconds for timed animations */
    val durationMs: Long
        get() = when (this) {
            is Bezier -> duration
            else -> 0L
        }

    /** Returns true if this is an animation that should be applied */
    val shouldAnimate: Boolean
        get() = this !is None

    companion object {
        // Must match ffi/src/jni/components.rs.
        private const val TAG_NONE = 0
        private const val TAG_BEZIER = 1
        private const val TAG_SPRING = 2

        /**
         * Constructs animation from packed native values.
         *
         * kindDurationPacked: low 32 bits = tag, high 32 bits = duration_ms
         * params12Packed: low/high 32 bits = p1/p2 (f32 bits)
         * params34Packed: low/high 32 bits = p3/p4 (f32 bits)
         */
        fun fromNative(
            kindDurationPacked: Long,
            params12Packed: Long,
            params34Packed: Long,
        ): WuiAnimation {
            val tag = unpackLowU32(kindDurationPacked)
            val duration = unpackHighU32(kindDurationPacked)
            val p1 = unpackLowF32(params12Packed)
            val p2 = unpackHighF32(params12Packed)
            val p3 = unpackLowF32(params34Packed)
            val p4 = unpackHighF32(params34Packed)

            return when (tag) {
                TAG_NONE -> None
                TAG_BEZIER -> Bezier(duration, p1, p2, p3, p4)
                TAG_SPRING -> Spring(p1, p2)
                else -> None
            }
        }

        private fun unpackLowU32(value: Long): Int = (value and 0xffff_ffffL).toInt()
        private fun unpackHighU32(value: Long): Long = (value ushr 32) and 0xffff_ffffL
        private fun unpackLowF32(value: Long): Float = Float.fromBits(unpackLowU32(value))
        private fun unpackHighF32(value: Long): Float = Float.fromBits((value ushr 32).toInt())

        /**
         * Legacy compatibility - constructs from tag only (uses defaults for other params).
         */
        @Deprecated("Use fromNative(kindDurationPacked, params12Packed, params34Packed) instead")
        fun fromNative(value: Int): WuiAnimation =
            when (value) {
                TAG_BEZIER -> Bezier(250L, 0.42f, 0f, 0.58f, 1f)
                TAG_SPRING -> Spring(100f, 10f)
                else -> None
            }
    }
}
