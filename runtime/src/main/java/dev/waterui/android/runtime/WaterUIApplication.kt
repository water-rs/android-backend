package dev.waterui.android.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

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

    CenteredRoot {
        WuiAnyView(pointer = rootPtr, environment = environment, registry = currentRegistry.value)
    }
}

@Composable
private fun CenteredRoot(content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        }

        val childConstraints = Constraints(
            minWidth = 0,
            minHeight = 0,
            maxWidth = Constraints.Infinity,
            maxHeight = Constraints.Infinity
        )

        val placeable = measurables.first().measure(childConstraints)
        val hasBoundedWidth = constraints.maxWidth != Constraints.Infinity
        val hasBoundedHeight = constraints.maxHeight != Constraints.Infinity

        val desiredWidth = if (hasBoundedWidth) constraints.maxWidth else placeable.width
        val desiredHeight = if (hasBoundedHeight) constraints.maxHeight else placeable.height

        val finalWidth = desiredWidth
            .coerceAtLeast(constraints.minWidth)
            .coerceAtLeast(placeable.width)
        val finalHeight = desiredHeight
            .coerceAtLeast(constraints.minHeight)
            .coerceAtLeast(placeable.height)

        val offsetX = ((finalWidth - placeable.width) / 2).coerceAtLeast(0)
        val offsetY = ((finalHeight - placeable.height) / 2).coerceAtLeast(0)

        layout(finalWidth, finalHeight) {
            placeable.placeRelative(offsetX, offsetY)
        }
    }
}
