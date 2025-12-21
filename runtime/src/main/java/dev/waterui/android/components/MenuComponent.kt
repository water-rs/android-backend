package dev.waterui.android.components

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.dp

private val menuTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_menu_id().toTypeId()
}

/**
 * Renderer for Menu component.
 *
 * Creates a button-like view that shows a dropdown menu when tapped.
 * On Android, this uses PopupMenu attached to a FrameLayout container.
 */
private val menuRenderer = WuiRenderer { context, node, env, registry ->
    val menuData = NativeBindings.waterui_force_as_menu(node.rawPtr)

    val envPtr = env.raw()

    // Create container with button-like styling
    val container = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true

        // Apply padding
        val horizontal = 16f.dp(context).toInt()
        val vertical = 8f.dp(context).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)

        // Apply ripple effect using accent color
        val accent = ThemeBridge.accent(env)
        accent.observe { color ->
            val colorInt = color.toColorInt()
            val cornerRadius = 8f.dp(context)

            // Create bordered background
            val border = GradientDrawable().apply {
                setStroke(1.5f.dp(context).toInt(), colorInt)
                setCornerRadius(cornerRadius)
                setColor(Color.TRANSPARENT)
            }
            background = border

            // Create ripple with mask
            val rippleColor = ColorStateList.valueOf(adjustAlpha(colorInt, 0.15f))
            val mask = GradientDrawable().apply {
                setCornerRadius(cornerRadius)
                setColor(Color.WHITE)
            }
            foreground = RippleDrawable(rippleColor, null, mask)
        }
        accent.attachTo(this)
    }

    // Inflate and add the label view
    if (menuData.labelPtr != 0L) {
        val labelView = inflateAnyView(context, menuData.labelPtr, env, registry)
        container.addView(labelView)
    }

    // Set stretch axis to None (content-sized)
    container.setTag(TAG_STRETCH_AXIS, StretchAxis.NONE)

    // Setup click listener to show menu
    if (menuData.itemsPtr != 0L) {
        container.setOnClickListener { view ->
            showMenu(view, menuData.itemsPtr, envPtr)
        }
    }

    // Cleanup
    container.disposeWith {
        if (menuData.itemsPtr != 0L) {
            NativeBindings.waterui_drop_computed_menu_items(menuData.itemsPtr)
        }
    }

    container
}

/**
 * Shows a popup menu with the specified items.
 */
private fun showMenu(anchor: View, itemsPtr: Long, envPtr: Long) {
    val items = NativeBindings.waterui_read_computed_menu_items(itemsPtr)
    if (items.isEmpty()) return

    val popup = PopupMenu(anchor.context, anchor)

    items.forEachIndexed { index, item ->
        // Read the styled text to get the label
        val styledStr = NativeBindings.waterui_read_computed_styled_str(item.labelPtr)
        val label = styledStr.chunks.joinToString("") { it.text }

        popup.menu.add(0, index, index, label)
    }

    popup.setOnMenuItemClickListener { menuItem ->
        val item = items.getOrNull(menuItem.itemId)
        if (item != null && item.actionPtr != 0L) {
            NativeBindings.waterui_call_shared_action(item.actionPtr, envPtr)
        }
        true
    }

    popup.show()
}

/**
 * Adjusts the alpha of a color.
 */
private fun adjustAlpha(color: Int, factor: Float): Int {
    val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}

internal fun RegistryBuilder.registerWuiMenu() {
    register({ menuTypeId }, menuRenderer)
}
