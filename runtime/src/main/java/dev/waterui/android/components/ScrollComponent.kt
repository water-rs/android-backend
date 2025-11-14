package dev.waterui.android.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiAnyView
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId

private val scrollTypeId: WuiTypeId by lazy { NativeBindings.waterui_scroll_view_id().toTypeId() }

private const val AXIS_HORIZONTAL = 0
private const val AXIS_VERTICAL = 1
private const val AXIS_ALL = 2

private val scrollRenderer = WuiRenderer { node, env ->
    val struct = remember(node) { NativeBindings.waterui_force_as_scroll(node.rawPtr) }
    val verticalState = rememberScrollState()
    val horizontalState = rememberScrollState()
    val modifier = when (struct.axis) {
        AXIS_HORIZONTAL -> androidx.compose.ui.Modifier.horizontalScroll(horizontalState)
        AXIS_VERTICAL -> androidx.compose.ui.Modifier.verticalScroll(verticalState)
        AXIS_ALL -> androidx.compose.ui.Modifier
            .verticalScroll(verticalState)
            .horizontalScroll(horizontalState)
        else -> androidx.compose.ui.Modifier.verticalScroll(verticalState)
    }

    Box(modifier = modifier) {
        WuiAnyView(pointer = struct.contentPtr, environment = env)
    }
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiScroll() {
    register({ scrollTypeId }, scrollRenderer)
}
