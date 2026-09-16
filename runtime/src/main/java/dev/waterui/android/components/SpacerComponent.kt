package dev.waterui.android.components

import android.content.Context
import android.widget.Space
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_LAYOUT_PRIORITY
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId


private val spacerTypeId: WuiTypeId by lazy { NativeBindings.waterui_spacer_id().toTypeId() }

private val spacerRenderer = WuiRenderer { context, _, _, _ ->
    wuiSpacer(context)
}

/**
 * The Android stand-in for `Spacer`.
 *
 * Carries `Spacer::DEFAULT_LAYOUT_PRIORITY` from the core ABI
 * (`Spacer_DEFAULT_LAYOUT_PRIORITY`, `INT32_MIN` in waterui.h): a spacer asks
 * for every point of room its container can spare, so its priority sits below
 * every view that names one — including below the default an untagged view
 * reports.
 */
internal fun wuiSpacer(context: Context): Space = Space(context).apply {
    setTag(TAG_LAYOUT_PRIORITY, SPACER_DEFAULT_LAYOUT_PRIORITY)
}

/** `Spacer_DEFAULT_LAYOUT_PRIORITY` from waterui.h (`INT32_MIN`). */
internal const val SPACER_DEFAULT_LAYOUT_PRIORITY: Int = Int.MIN_VALUE

internal fun RegistryBuilder.registerWuiSpacer() {
    register({ spacerTypeId }, spacerRenderer)
}
