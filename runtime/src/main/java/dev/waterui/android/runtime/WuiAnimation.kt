package dev.waterui.android.runtime

enum class WuiAnimation {
    NONE,
    DEFAULT;

    companion object {
        fun fromNative(value: Int): WuiAnimation =
            when (value) {
                0 -> DEFAULT
                else -> NONE
            }
    }
}
