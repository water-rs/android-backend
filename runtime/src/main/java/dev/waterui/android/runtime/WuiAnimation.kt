package dev.waterui.android.runtime

import dev.waterui.android.ffi.WatcherJni

sealed interface WuiAnimation {
    data object None : WuiAnimation

    data class Bezier(
        val durationMillis: Long,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    ) : WuiAnimation

    data class Spring(val stiffness: Float, val damping: Float) : WuiAnimation

    companion object {
        fun fromNative(metadataPtr: Long): WuiAnimation {
            val kindDuration = WatcherJni.getAnimationKindDurationPacked(metadataPtr)
            return when (kindDuration.toInt()) {
                0 -> None
                1 -> {
                    val params12 = WatcherJni.getAnimationParams12Packed(metadataPtr)
                    val params34 = WatcherJni.getAnimationParams34Packed(metadataPtr)
                    Bezier(
                        durationMillis = kindDuration ushr Int.SIZE_BITS,
                        x1 = params12.lowFloat(),
                        y1 = params12.highFloat(),
                        x2 = params34.lowFloat(),
                        y2 = params34.highFloat()
                    )
                }
                2 -> {
                    val params = WatcherJni.getAnimationParams12Packed(metadataPtr)
                    Spring(stiffness = params.lowFloat(), damping = params.highFloat())
                }
                else -> error("unknown WaterUI animation kind: ${kindDuration.toInt()}")
            }
        }

        private fun Long.lowFloat(): Float = Float.fromBits(toInt())

        private fun Long.highFloat(): Float = Float.fromBits((this ushr Int.SIZE_BITS).toInt())
    }
}
