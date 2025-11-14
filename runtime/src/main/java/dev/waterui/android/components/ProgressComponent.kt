package dev.waterui.android.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiAnyView
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId

private val progressTypeId: WuiTypeId by lazy { NativeBindings.waterui_progress_id().toTypeId() }

private val progressRenderer = WuiRenderer { node, env ->
    val struct = remember(node) { NativeBindings.waterui_force_as_progress(node.rawPtr) }
    val computed = remember(struct.valuePtr, env) {
        struct.valuePtr.takeIf { it != 0L }?.let {
            WuiComputed.double(it, env).also { computed -> computed.watch() }
        }
    }
    val valueState = computed?.state
    DisposableEffect(computed) {
        onDispose { computed?.close() }
    }

    val progressValue = valueState?.value?.toFloat()

    Column {
        if (struct.labelPtr != 0L) {
            WuiAnyView(pointer = struct.labelPtr, environment = env)
        }
        when (struct.style) {
            1 -> {
                if (progressValue != null && !progressValue.isNaN()) {
                    CircularProgressIndicator(progress = progressValue)
                } else {
                    CircularProgressIndicator()
                }
            }
            else -> {
                if (progressValue != null && !progressValue.isNaN()) {
                    LinearProgressIndicator(progress = progressValue)
                } else {
                    LinearProgressIndicator()
                }
            }
        }
        if (struct.valueLabelPtr != 0L) {
            WuiAnyView(pointer = struct.valueLabelPtr, environment = env)
        }
    }
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiProgress() {
    register({ progressTypeId }, progressRenderer)
}
