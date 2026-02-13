package dev.waterui.android.components

import android.widget.Space
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId


private val emptyTypeId: WuiTypeId by lazy {
    WatcherJni.emptyId().toTypeId()
}

private val emptyRenderer = WuiRenderer { context, _, _, _ ->
    Space(context)
}

internal fun RegistryBuilder.registerWuiEmptyView() {
    register({ emptyTypeId }, emptyRenderer)
}
