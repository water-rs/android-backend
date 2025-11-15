package dev.waterui.android.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Top-level composable that wires a [WuiEnvironment] into Compose and renders the root view.
 */
@Composable
fun WaterUIApplication(registry: RenderRegistry = RenderRegistry.default()) {
    val environment = remember {
        WuiEnvironment(NativeBindings.waterui_init())
    }

    val currentRegistry = rememberUpdatedState(registry)

    DisposableEffect(environment) {
        onDispose { environment.close() }
    }

    val rootPtr = remember { NativeBindings.waterui_main() }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        WuiAnyView(pointer = rootPtr, environment = environment, registry = currentRegistry.value)
    }
}
