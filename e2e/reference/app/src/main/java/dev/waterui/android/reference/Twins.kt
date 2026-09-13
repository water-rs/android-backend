package dev.waterui.android.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Twin registry and shared translation helpers.
//
// Each twin is a hand-written Compose + Material 3 reproduction of a waterui
// example's first screen. Layout follows WaterUI's SwiftUI-derived semantics —
// it is intentionally NOT Compose's; only component behavior and styling must
// match MD3:
//
//   vstack/hstack bare            -> Column/Row spacedBy 10.dp (WaterUI default)
//   .padding()                     -> padding(14.dp) (WaterUI default padding)
//   .padding_with(all(n))          -> padding(n.dp)
//   .padding_with(symmetric(v,h))  -> padding(vertical=v.dp, horizontal=h.dp)
//   .width/.height                 -> width/height(n.dp)
//   scroll(...)                    -> verticalScroll + inner Column
//   Divider                        -> HorizontalDivider()
//   spacer()                       -> Spacer(weight) — bare spacer expands
//
// Text styles map SwiftUI's semantic slots onto the nearest MD3 typography
// role (documented per call site; divergences from the backend's own mapping
// are findings, not things to paper over):
//
//   .title()                       -> headlineMedium   (SwiftUI 28pt ≈ 28sp)
//   .headline()                    -> titleMedium      (semibold ≈ medium 16sp)
//   plain text / body              -> bodyLarge        (SwiftUI 17pt ≈ 16sp)
//
//   Srgb::from_hex("#RRGGBB")      -> Color(0xFFRRGGBB)
//   .with_opacity(x)               -> copy(alpha = x)

/** The twin for `example`, or null when none is registered. */
fun twinFor(example: String): (@Composable () -> Unit)? =
    when (example) {
        "gesture" -> ({ GestureTwin() })
        else -> null
    }

private val TapColor = Color(0xFF2196F3)
private val DoubleTapColor = Color(0xFF4CAF50)
private val LongPressColor = Color(0xFFFF9800)
private val DragColor = Color(0xFF9C27B0)
private val ChainedColor = Color(0xFFF44336)
private val OnTapColor = Color(0xFF00BCD4)

private const val WATERUI_SPACING = 10
private const val WATERUI_PADDING = 14

/** `vstack` — WaterUI's bare stack spacing, centered like the backend lays out. */
@Composable
private fun VStack(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(WATERUI_SPACING.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

/** `text("Tap Me!").padding().background(color.with_opacity(0.3))` */
@Composable
private fun GestureBox(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(color.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 16.sp,
            modifier = Modifier.padding(WATERUI_PADDING.dp),
        )
    }
}

@Composable
private fun GestureSection(
    title: String,
    caption: String,
    counter: String,
    box: @Composable () -> Unit,
) {
    VStack {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(caption, style = MaterialTheme.typography.bodyLarge)
        Text(counter, style = MaterialTheme.typography.bodyLarge)
        box()
    }
}

@Composable
fun GestureTwin() {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VStack {
            Text(
                "WaterUI Gesture Examples",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                "Demonstrating gesture recognition and handling",
                style = MaterialTheme.typography.bodyLarge,
            )
            HorizontalDivider()

            GestureSection(
                title = "Tap Gesture",
                caption = "Tap the box below to increment the counter",
                counter = "Tap count: 0",
            ) { GestureBox("Tap Me!", TapColor) }
            HorizontalDivider()

            GestureSection(
                title = "Double Tap Gesture",
                caption = "Double-tap the box to increment",
                counter = "Double tap count: 0",
            ) { GestureBox("Double Tap Me!", DoubleTapColor) }
            HorizontalDivider()

            GestureSection(
                title = "Long Press Gesture",
                caption = "Press and hold for 500ms",
                counter = "Long press count: 0",
            ) { GestureBox("Long Press Me!", LongPressColor) }
            HorizontalDivider()

            GestureSection(
                title = "Drag Gesture",
                caption = "Drag within the box (min 5pt)",
                counter = "Drag events: 0",
            ) {
                GestureBox(
                    "Drag Here",
                    DragColor,
                    Modifier.width(200.dp).height(100.dp),
                )
            }
            HorizontalDivider()

            GestureSection(
                title = "Chained Gesture",
                caption = "Tap first, then long press to complete",
                counter = "Waiting for tap...",
            ) { GestureBox("Tap then Long Press", ChainedColor) }
            HorizontalDivider()

            GestureSection(
                title = "on_tap Shorthand",
                caption = "Convenient method for simple tap handlers",
                counter = "This uses the same counter as Section 1",
            ) { GestureBox("Simple Tap", OnTapColor) }
        }
    }
}
