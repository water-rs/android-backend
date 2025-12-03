package dev.waterui.android.components

import android.widget.Space
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId

private val emptyTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_empty_id().toTypeId()
}

private val emptyRenderer = WuiRenderer { context, _, _, _ ->
    Space(context)
}

internal fun RegistryBuilder.registerWuiEmptyView() {
    register({ emptyTypeId }, emptyRenderer)
}
