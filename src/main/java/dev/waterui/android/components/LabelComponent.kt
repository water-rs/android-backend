package dev.waterui.android.components

import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId

private val labelTypeId: WuiTypeId by lazy { NativeBindings.waterui_plain_id().toTypeId() }

private val labelRenderer = WuiRenderer { node, _ ->
    val label = remember(node) {
        val struct = NativeBindings.waterui_force_as_plain(node.rawPtr)
        struct.textBytes.decodeToString()
    }
    Text(label)
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiPlain() {
    register({ labelTypeId }, labelRenderer)
}
