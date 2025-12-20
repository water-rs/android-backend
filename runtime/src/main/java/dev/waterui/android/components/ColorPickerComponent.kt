package dev.waterui.android.components

import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import dev.waterui.android.runtime.ColorPickerStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ResolvedColorStruct
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.ffi.WatcherJni

private val colorPickerTypeId: WuiTypeId by lazy { NativeBindings.waterui_color_picker_id().toTypeId() }

private val colorPickerRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_color_picker(node.rawPtr)

    val density = context.resources.displayMetrics.density
    val spacingPx = (8 * density).toInt()

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // Add label if present
    if (struct.labelPtr != 0L) {
        val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
        val labelParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ).apply {
            marginEnd = spacingPx
        }
        container.addView(labelView, labelParams)
    }

    // Color button that shows current color and opens picker
    val colorButton = MaterialButton(context).apply {
        minHeight = (44 * density).toInt()
        minWidth = (44 * density).toInt()
        text = ""
        cornerRadius = (8 * density).toInt()
    }

    container.addView(colorButton)

    // Read initial color from binding
    // Note: For color binding, we need to resolve through the theme system
    // Currently using a simplified approach with theme accent color as fallback
    fun resolveCurrentColor(): ResolvedColorStruct {
        // For now, use the theme accent color since color binding is complex
        // Full implementation would need waterui_read_binding_color JNI function
        val accentPtr = WatcherJni.themeColorAccent(env.raw())
        val resolved = WatcherJni.readComputedResolvedColor(accentPtr)
        // Don't drop - theme colors are cached
        return resolved
    }

    fun updateButtonColor() {
        val resolved = resolveCurrentColor()
        val color = resolved.toColorInt()
        colorButton.setBackgroundColor(color)
    }

    updateButtonColor()

    // Set up color picker dialog on click
    colorButton.setOnClickListener {
        val currentColor = resolveCurrentColor()
        val initialColor = currentColor.toColorInt()

        // Use Android's color picker dialog if available (API 33+)
        // For older versions, use a simple color grid or material color picker
        try {
            // Try to use Android 14+ PhotoPicker color API if available
            val colorPickerClass = Class.forName("android.graphics.ColorSpace")
            // For now, just show a simple toast or use a basic dialog
            showBasicColorPicker(context, initialColor, struct.supportAlpha) { selectedColor ->
                // Update binding with new color
                // Note: Full implementation requires waterui_set_binding_color with a new Color
                // For now, log the selection
                android.util.Log.d("WuiColorPicker", "Selected color: $selectedColor")
                updateButtonColor()
            }
        } catch (e: Exception) {
            // Fallback to basic color picker
            showBasicColorPicker(context, initialColor, struct.supportAlpha) { selectedColor ->
                android.util.Log.d("WuiColorPicker", "Selected color: $selectedColor")
                updateButtonColor()
            }
        }
    }

    // Apply theme colors
    val accent = ThemeBridge.accent(env)
    accent.observe { _ ->
        // Update accent styling if needed
    }
    accent.attachTo(container)

    container
}

private fun showBasicColorPicker(
    context: android.content.Context,
    initialColor: Int,
    supportAlpha: Boolean,
    onColorSelected: (Int) -> Unit
) {
    // Basic color selection dialog with preset colors
    val colors = intArrayOf(
        Color.RED, Color.GREEN, Color.BLUE,
        Color.YELLOW, Color.CYAN, Color.MAGENTA,
        Color.WHITE, Color.GRAY, Color.BLACK,
        Color.parseColor("#FF5722"), // Deep Orange
        Color.parseColor("#9C27B0"), // Purple
        Color.parseColor("#3F51B5"), // Indigo
    )

    val colorNames = arrayOf(
        "Red", "Green", "Blue",
        "Yellow", "Cyan", "Magenta",
        "White", "Gray", "Black",
        "Deep Orange", "Purple", "Indigo"
    )

    android.app.AlertDialog.Builder(context)
        .setTitle("Select Color")
        .setItems(colorNames) { _, which ->
            onColorSelected(colors[which])
        }
        .setNegativeButton("Cancel", null)
        .show()
}

internal fun RegistryBuilder.registerWuiColorPicker() {
    register({ colorPickerTypeId }, colorPickerRenderer)
}
