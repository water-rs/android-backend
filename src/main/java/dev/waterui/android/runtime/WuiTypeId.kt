package dev.waterui.android.runtime

/**
 * Kotlin representation of `WuiTypeId` (string identifiers exposed by the Rust backend).
 */
@JvmInline
value class WuiTypeId(val raw: String) {
    companion object {
        val ANY_VIEW: WuiTypeId = WuiTypeId("waterui.AnyView")
    }
}

fun String.toTypeId(): WuiTypeId = WuiTypeId(this)
