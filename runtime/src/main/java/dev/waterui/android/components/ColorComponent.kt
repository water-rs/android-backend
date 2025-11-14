package dev.waterui.android.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId

private val colorTypeId: WuiTypeId by lazy { NativeBindings.waterui_color_id().toTypeId() }

private val colorRenderer = WuiRenderer { node, env ->
    val struct = remember(node) { NativeBindings.waterui_force_as_color(node.rawPtr) }
    val computed = remember(struct.colorPtr, env) {
        WuiComputed.resolvedColor(struct.colorPtr, env).also { it.watch() }
    }
    val colorState = computed.state

    DisposableEffect(computed) {
        onDispose { computed.close() }
    }

    val color = androidx.compose.ui.graphics.Color(
        red = colorState.value.red,
        green = colorState.value.green,
        blue = colorState.value.blue,
        alpha = colorState.value.opacity
    )
    Box(
        modifier = androidx.compose.ui.Modifier
            .fillMaxSize()
            .background(color)
    )
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiColor() {
    register({ colorTypeId }, colorRenderer)
}
