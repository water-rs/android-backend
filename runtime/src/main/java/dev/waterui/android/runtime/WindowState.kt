package dev.waterui.android.runtime

/**
 * Window state enum matching Rust WindowState.
 *
 * Values must match the FFI `WuiWindowState`:
 * - Normal = 0
 * - Closed = 1
 * - Minimized = 2
 * - Fullscreen = 3
 */
enum class WindowState(val value: Int) {
    NORMAL(0),
    CLOSED(1),
    MINIMIZED(2),
    FULLSCREEN(3);

    companion object {
        fun fromInt(value: Int): WindowState =
            entries.firstOrNull { it.value == value } ?: NORMAL
    }
}

