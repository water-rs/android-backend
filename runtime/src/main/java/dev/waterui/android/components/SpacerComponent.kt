package dev.waterui.android.components

import android.widget.Space
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId


private val spacerTypeId: WuiTypeId by lazy { WatcherJni.spacerId().toTypeId() }

private val spacerRenderer = WuiRenderer { context, _, _, _ ->
    Space(context)
}

internal fun RegistryBuilder.registerWuiSpacer() {
    register({ spacerTypeId }, spacerRenderer)
}
