package dev.waterui.android.runtime

import java.io.Closeable

@JvmInline
value class WuiPointer(val raw: Long) {
    init {
        require(raw != 0L) { "WaterUI pointer must not be null" }
    }
}

internal fun Long.asPointerOrNull(): WuiPointer? =
    if (this == 0L) null else WuiPointer(this)

class WuiEnvironment private constructor(private val pointer: WuiPointer) : Closeable {
    val rawPtr: Long get() = pointer.raw

    companion object {
        fun create(): WuiEnvironment {
            val ptr = NativeBindings.waterui_init().asPointerOrNull()
                ?: error("waterui_init returned null")
            return WuiEnvironment(ptr)
        }
    }

    fun cloneEnvironment(): WuiEnvironment {
        val ptr = NativeBindings.waterui_clone_env(pointer.raw).asPointerOrNull()
            ?: error("Failed to clone WaterUI environment")
        return WuiEnvironment(ptr)
    }

    override fun close() {
        NativeBindings.waterui_drop_env(pointer.raw)
    }
}

class WuiAnyView internal constructor(
    private var raw: Long
) : Closeable {
    private var consumed = false
    val rawPtr: Long
        get() {
            check(!consumed) { "WuiAnyView pointer has been consumed" }
            return raw
        }

    companion object {
        fun fromRaw(raw: Long): WuiAnyView {
            raw.asPointerOrNull() ?: error("Expected non-null AnyView pointer")
            return WuiAnyView(raw)
        }
    }

    fun viewId(): String {
        check(!consumed) { "Cannot read id from a consumed WuiAnyView" }
        return NativeBindings.waterui_view_id(raw)
    }

    fun body(env: WuiEnvironment): WuiAnyView? {
        check(!consumed) { "Cannot extract body from a consumed WuiAnyView" }
        val bodyRaw = NativeBindings.waterui_view_body(raw, env.rawPtr)
        consumed = true
        raw = 0L
        return if (bodyRaw == 0L) null else fromRaw(bodyRaw)
    }

    override fun close() {
        if (!consumed) {
            NativeBindings.waterui_drop_anyview(raw)
            consumed = true
            raw = 0L
        }
    }
}
