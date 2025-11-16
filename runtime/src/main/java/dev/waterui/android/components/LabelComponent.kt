package dev.waterui.android.components

import android.widget.TextView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId

private val labelTypeId: WuiTypeId by lazy { NativeBindings.waterui_plain_id().toTypeId() }

private val labelRenderer = WuiRenderer { context, node, _, _ ->
    val struct = NativeBindings.waterui_force_as_plain(node.rawPtr)
    TextView(context).apply {
        text = struct.textBytes.decodeToString()
    }
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiPlain() {
    register({ labelTypeId }, labelRenderer)
}
