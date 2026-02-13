package dev.waterui.android.runtime

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.Window

internal enum class WuiDynamicRangeMode {
    STANDARD,
    HIGH
}

internal const val TAG_DYNAMIC_RANGE_MODE = 0x57554903 // "WUI\x03"
private const val TAG_HDR_REFCOUNT = 0x57554904 // "WUI\x04"
private const val TAG_HDR_PREV_COLOR_MODE = 0x57554905 // "WUI\x05"

internal fun View.setWuiDynamicRangeMode(mode: WuiDynamicRangeMode) {
    setTag(TAG_DYNAMIC_RANGE_MODE, mode)
}

internal fun View.resolveWuiDynamicRangeModeOrNull(): WuiDynamicRangeMode? {
    var current: View? = this
    while (current != null) {
        val mode = current.getTag(TAG_DYNAMIC_RANGE_MODE) as? WuiDynamicRangeMode
        if (mode != null) return mode
        current = current.parent as? View
    }
    return null
}

internal fun View.resolveWuiDynamicRangeMode(): WuiDynamicRangeMode {
    return resolveWuiDynamicRangeModeOrNull() ?: WuiDynamicRangeMode.HIGH
}

internal fun applyWindowDynamicRangePolicyOnAttach(
    view: View,
    context: Context,
    mode: WuiDynamicRangeMode,
    requireCapability: Boolean = true
) {
    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        private var isApplied = false

        override fun onViewAttachedToWindow(v: View) {
            if (isApplied) return
            val activity = context.findActivity() ?: error("DynamicRange policy requires an Activity context")
            if (mode == WuiDynamicRangeMode.HIGH) {
                activateHdrWindowMode(activity.window, requireCapability)
            }
            isApplied = true
        }

        override fun onViewDetachedFromWindow(v: View) {
            if (!isApplied) return
            val activity = context.findActivity()
            if (mode == WuiDynamicRangeMode.HIGH) {
                deactivateHdrWindowMode(activity?.window)
            }
            isApplied = false
        }
    })
}

internal fun activateHdrWindowMode(window: Window?, requireCapability: Boolean = true) {
    val w = window ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        if (requireCapability) {
            error("HDR rendering requires Android 8.0+ (API 26)")
        }
        return
    }

    if (requireCapability) {
        val config = w.context.resources.configuration
        if (!config.isScreenHdr) {
            error("HDR view requested but current display is not HDR-capable")
        }
        if (!config.isScreenWideColorGamut) {
            error("HDR view requested but current display does not support wide color gamut")
        }
    }

    val decor = w.decorView ?: return
    val current = (decor.getTag(TAG_HDR_REFCOUNT) as? Int) ?: 0
    if (current == 0) {
        decor.setTag(TAG_HDR_PREV_COLOR_MODE, w.colorMode)
        w.colorMode = ActivityInfo.COLOR_MODE_HDR
    }
    decor.setTag(TAG_HDR_REFCOUNT, current + 1)
}

internal fun deactivateHdrWindowMode(window: Window?) {
    val w = window ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return
    }

    val decor = w.decorView ?: return
    val current = (decor.getTag(TAG_HDR_REFCOUNT) as? Int) ?: 0
    val next = current - 1
    if (next <= 0) {
        decor.setTag(TAG_HDR_REFCOUNT, 0)
        val previous = (decor.getTag(TAG_HDR_PREV_COLOR_MODE) as? Int) ?: ActivityInfo.COLOR_MODE_DEFAULT
        w.colorMode = previous
        decor.setTag(TAG_HDR_PREV_COLOR_MODE, null)
    } else {
        decor.setTag(TAG_HDR_REFCOUNT, next)
    }
}
