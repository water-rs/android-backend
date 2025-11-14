package dev.waterui.android.components

import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId
import dev.waterui.android.reactive.WuiComputed

private val textTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_text_id().toTypeId()
}

private val textRenderer = WuiRenderer { node, env ->
    val struct = remember(node) { NativeBindings.waterui_force_as_text(node.rawPtr) }
    val computed = remember(struct.contentPtr, env) {
        WuiComputed.styledString(struct.contentPtr, env).also { it.watch() }
    }
    val textState = computed.state

    DisposableEffect(computed) {
        onDispose { computed.close() }
    }

    Text(text = textState.value)
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiText() {
    register({ textTypeId }, textRenderer)
}
