package dev.waterui.android.components

import android.widget.Space
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId

private val spacerTypeId: WuiTypeId by lazy { NativeBindings.waterui_spacer_id().toTypeId() }

private val spacerRenderer = WuiRenderer { context, _, _, _ ->
    Space(context)
}

internal fun RegistryBuilder.registerWuiSpacer() {
    register({ spacerTypeId }, spacerRenderer)
}
