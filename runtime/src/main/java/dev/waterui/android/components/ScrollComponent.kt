package dev.waterui.android.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
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
    val scrollModifier = when (struct.axis) {
        AXIS_HORIZONTAL -> Modifier.horizontalScroll(horizontalState)
        AXIS_VERTICAL -> Modifier.verticalScroll(verticalState)
        AXIS_ALL -> Modifier
            .verticalScroll(verticalState)
            .horizontalScroll(horizontalState)
        else -> Modifier.verticalScroll(verticalState)
    }
    val centerHorizontally = when (struct.axis) {
        AXIS_HORIZONTAL -> false
        else -> true
    }
    val centerVertically = when (struct.axis) {
        AXIS_VERTICAL -> false
        else -> true
    }

    CenteredScrollContainer(
        modifier = scrollModifier,
        centerHorizontally = centerHorizontally,
        centerVertically = centerVertically
    ) {
        WuiAnyView(pointer = struct.contentPtr, environment = env)
    }
}

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiScroll() {
    register({ scrollTypeId }, scrollRenderer)
}

@Composable
private fun CenteredScrollContainer(
    modifier: Modifier,
    centerHorizontally: Boolean,
    centerVertically: Boolean,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        CenteredContent(
            centerHorizontally = centerHorizontally,
            centerVertically = centerVertically,
            content = content
        )
    }
}

@Composable
private fun CenteredContent(
    centerHorizontally: Boolean,
    centerVertically: Boolean,
    content: @Composable () -> Unit
) {
    Layout(content = content) { measurables, constraints ->
        if (measurables.isEmpty()) {
            return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        }

        val childConstraints = Constraints(
            minWidth = 0,
            minHeight = 0,
            maxWidth = constraints.maxWidth,
            maxHeight = constraints.maxHeight
        )
        val placeable = measurables.first().measure(childConstraints)

        val width = resolveDimension(
            center = centerHorizontally,
            min = constraints.minWidth,
            max = constraints.maxWidth,
            child = placeable.width
        )
        val height = resolveDimension(
            center = centerVertically,
            min = constraints.minHeight,
            max = constraints.maxHeight,
            child = placeable.height
        )

        val offsetX = if (centerHorizontally) ((width - placeable.width) / 2).coerceAtLeast(0) else 0
        val offsetY = if (centerVertically) ((height - placeable.height) / 2).coerceAtLeast(0) else 0

        layout(width, height) {
            placeable.placeRelative(offsetX, offsetY)
        }
    }
}

private fun resolveDimension(center: Boolean, min: Int, max: Int, child: Int): Int {
    if (!center) {
        val upper = if (max == Constraints.Infinity) Int.MAX_VALUE else max
        return child.coerceIn(min, upper)
    }
    val bounded = max != Constraints.Infinity
    val base = if (bounded) max else child
    return base.coerceAtLeast(min).coerceAtLeast(child)
}
