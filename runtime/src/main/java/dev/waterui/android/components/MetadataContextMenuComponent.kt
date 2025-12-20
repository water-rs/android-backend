package dev.waterui.android.components

import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

private val metadataContextMenuTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_context_menu_id().toTypeId()
}

/**
 * Renderer for Metadata<ContextMenu>.
 *
 * Attaches a context menu to the wrapped content view.
 * On Android, this uses PopupMenu which is shown on long-press.
 */
private val metadataContextMenuRenderer = WuiRenderer { context, node, env, registry ->
    val menuData = NativeBindings.waterui_force_as_metadata_context_menu(node.rawPtr)

    val container = FrameLayout(context)
    val envPtr = env.raw()

    // Inflate the content
    if (menuData.contentPtr != 0L) {
        val child = inflateAnyView(context, menuData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Setup long-press listener to show context menu
    if (menuData.itemsPtr != 0L) {
        container.setOnLongClickListener { view ->
            showContextMenu(view, menuData.itemsPtr, envPtr)
            true
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

private fun showContextMenu(anchor: View, itemsPtr: Long, envPtr: Long) {
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

internal fun RegistryBuilder.registerWuiContextMenu() {
    registerMetadata({ metadataContextMenuTypeId }, metadataContextMenuRenderer)
}
