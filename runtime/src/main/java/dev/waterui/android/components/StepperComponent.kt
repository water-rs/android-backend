package dev.waterui.android.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiAnyView
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId

private val stepperTypeId: WuiTypeId by lazy { NativeBindings.waterui_stepper_id().toTypeId() }

private val stepperRenderer = WuiRenderer { node, env ->
    val struct = remember(node) { NativeBindings.waterui_force_as_stepper(node.rawPtr) }
    val binding = remember(struct.bindingPtr) {
        WuiBinding.int(struct.bindingPtr, env).also { it.watch() }
    }
    val stepComputed = remember(struct.stepPtr, env) {
        struct.stepPtr.takeIf { it != 0L }?.let {
            WuiComputed.int(it, env).also { computed -> computed.watch() }
        }
    }
    val valueState = binding.state
    val stepValue = stepComputed?.state?.value ?: 1
    val rangeStart = struct.rangeStart
    val rangeEnd = struct.rangeEnd

    DisposableEffect(binding) {
        onDispose { binding.close() }
    }
    DisposableEffect(stepComputed) {
        onDispose { stepComputed?.close() }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (struct.labelPtr != 0L) {
            WuiAnyView(pointer = struct.labelPtr, environment = env)
            Spacer(modifier = Modifier.width(8.dp))
        }
        TextButton(onClick = {
            val newValue = (valueState.value - stepValue).coerceAtLeast(rangeStart)
            binding.set(newValue)
        }) {
            Text("-")
        }
        Text("${'$'}{valueState.value}")
        TextButton(onClick = {
            val newValue = (valueState.value + stepValue).coerceAtMost(rangeEnd)
            binding.set(newValue)
        }) {
            Text("+")
        }
    }
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiStepper() {
    register({ stepperTypeId }, stepperRenderer)
}
