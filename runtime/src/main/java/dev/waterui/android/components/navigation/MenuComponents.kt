package dev.waterui.android.components

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuCompat
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.MenuItemStruct
import dev.waterui.android.runtime.MenuItemTag
import dev.waterui.android.runtime.MenuStruct
import dev.waterui.android.runtime.MetadataContextMenuStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toModel

private val menuTypeId: WuiTypeId by lazy { NativeBindings.waterui_menu_id().toTypeId() }
private val metadataContextMenuTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_context_menu_id().toTypeId()
}

private const val MENU_ITEM_ID_BASE = 0x575500

private sealed interface AndroidMenuNode : AutoCloseable {
    data class Command(
        val label: CharSequence,
        val actionPtr: Long,
        val disabled: Boolean,
        val checkable: Boolean,
        val selected: Boolean,
        val shortcut: AndroidMenuShortcut?
    ) : AndroidMenuNode {
        override fun close() {
            if (actionPtr != 0L) {
                NativeBindings.waterui_drop_action(actionPtr)
            }
        }
    }

    data object Divider : AndroidMenuNode {
        override fun close() = Unit
    }

    data class NestedMenu(
        val label: CharSequence,
        val items: List<AndroidMenuNode>
    ) : AndroidMenuNode {
        override fun close() {
            items.forEach(AndroidMenuNode::close)
        }
    }
}

private data class AndroidMenuShortcut(
    val keyEquivalent: String,
    val command: Boolean,
    val shift: Boolean,
    val option: Boolean,
    val control: Boolean
)

internal interface AndroidMenuHandle : AutoCloseable {
    fun onMenuItemSelected(itemId: Int): Boolean
    fun clear(menu: Menu)
}

private class BuiltAndroidMenu(

    private val env: WuiEnvironment,
    private val nodes: List<AndroidMenuNode>
) : AndroidMenuHandle {
    private val actionPtrs = mutableMapOf<Int, Long>()
    private val topLevelItemIds = mutableListOf<Int>()
    private var nextItemId = MENU_ITEM_ID_BASE

    fun populate(menu: Menu) {
        MenuCompat.setGroupDividerEnabled(menu, true)
        append(menu, nodes, isTopLevel = true)
    }

    override fun clear(menu: Menu) {
        topLevelItemIds.forEach(menu::removeItem)
        topLevelItemIds.clear()
    }

    override fun onMenuItemSelected(itemId: Int): Boolean {
        val actionPtr = actionPtrs[itemId] ?: return false
        NativeBindings.waterui_call_action(actionPtr, env.raw())
        return true
    }

    override fun close() {
        actionPtrs.clear()
        topLevelItemIds.clear()
        nodes.forEach(AndroidMenuNode::close)
    }

    private fun append(menu: Menu, nodes: List<AndroidMenuNode>, isTopLevel: Boolean) {
        var groupId = 0
        var order = 0

        for (node in nodes) {
            when (node) {
                AndroidMenuNode.Divider -> {
                    groupId += 1
                }

                is AndroidMenuNode.Command -> {
                    val itemId = nextItemId++
                    val item = menu.add(groupId, itemId, order++, node.label)
                    item.isEnabled = !node.disabled && node.actionPtr != 0L
                    item.isCheckable = node.checkable
                    item.isChecked = node.selected
                    if (isTopLevel) {
                        topLevelItemIds += itemId
                    }
                    if (node.actionPtr != 0L) {
                        actionPtrs[itemId] = node.actionPtr
                    }
                }

                is AndroidMenuNode.NestedMenu -> {
                    val itemId = nextItemId++
                    val subMenu = menu.addSubMenu(groupId, itemId, order++, node.label)
                    MenuCompat.setGroupDividerEnabled(subMenu, true)
                    if (isTopLevel) {
                        topLevelItemIds += itemId
                    }
                    append(subMenu, node.items, isTopLevel = false)
                }
            }
        }
    }
}

private fun readMenuSnapshot(itemsPtr: Long, env: WuiEnvironment): List<AndroidMenuNode> {
    if (itemsPtr == 0L) {
        return emptyList()
    }

    return NativeBindings.waterui_read_computed_menu_items(itemsPtr)
        .map { item -> item.consumeToSnapshot(env) }
        .toList()
}

private fun MenuItemStruct.consumeToSnapshot(env: WuiEnvironment): AndroidMenuNode {
    val tag = MenuItemTag.fromInt(tag)

    return when (tag) {
        MenuItemTag.DIVIDER -> {
            dropPresentationPointers(includeItems = false)
            AndroidMenuNode.Divider
        }

        MenuItemTag.COMMAND -> {
            require(labelPtr != 0L) { "Menu command labelPtr is null" }
            val node = AndroidMenuNode.Command(
                label = readLabel(labelPtr, env),
                actionPtr = actionPtr,
                disabled = disabledPtr != 0L && NativeBindings.waterui_read_computed_bool(disabledPtr),
                checkable = selectedPtr != 0L,
                selected = selectedPtr != 0L && NativeBindings.waterui_read_computed_bool(selectedPtr),
                shortcut = keyEquivalent?.let {
                    AndroidMenuShortcut(
                        keyEquivalent = it,
                        command = command,
                        shift = shift,
                        option = option,
                        control = control
                    )
                }
            )
            dropPresentationPointers(includeItems = false)
            node
        }

        MenuItemTag.MENU -> {
            require(labelPtr != 0L) { "Nested menu labelPtr is null" }
            require(itemsPtr != 0L) { "Nested menu itemsPtr is null" }
            val node = AndroidMenuNode.NestedMenu(
                label = readLabel(labelPtr, env),
                items = readMenuSnapshot(itemsPtr, env)
            )
            dropPresentationPointers(includeItems = true)
            if (actionPtr != 0L) {
                NativeBindings.waterui_drop_action(actionPtr)
            }
            node
        }
    }
}

private fun MenuItemStruct.dropPresentationPointers(includeItems: Boolean) {
    if (labelPtr != 0L) {
        NativeBindings.waterui_drop_computed_styled_str(labelPtr)
    }
    if (disabledPtr != 0L) {
        NativeBindings.waterui_drop_computed_bool(disabledPtr)
    }
    if (selectedPtr != 0L) {
        NativeBindings.waterui_drop_computed_bool(selectedPtr)
    }
    if (includeItems && itemsPtr != 0L) {
        NativeBindings.waterui_drop_computed_menu_items(itemsPtr)
    }
}

private fun readLabel(labelPtr: Long, env: WuiEnvironment): CharSequence {
    val styled = NativeBindings.waterui_read_computed_styled_str(labelPtr).toModel()
    return try {
        styled.toCharSequence(env)
    } finally {
        styled.close()
    }
}

private fun showPopupMenu(anchor: View, itemsPtr: Long, env: WuiEnvironment): Boolean {
    val nodes = readMenuSnapshot(itemsPtr, env)
    if (nodes.isEmpty()) {
        return false
    }

    val builtMenu = BuiltAndroidMenu(env, nodes)
    val popup = PopupMenu(anchor.context, anchor)
    builtMenu.populate(popup.menu)
    popup.setOnMenuItemClickListener { item -> builtMenu.onMenuItemSelected(item.itemId) }
    popup.setOnDismissListener { builtMenu.close() }
    popup.show()
    return true
}

private class SelectionMenuCallback(
    private val selectionMenuPtr: Long,
    private val env: WuiEnvironment
) : ActionMode.Callback {
    private var builtMenu: BuiltAndroidMenu? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        rebuild(menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        rebuild(menu)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val handled = builtMenu?.onMenuItemSelected(item.itemId) == true
        if (handled) {
            mode.finish()
        }
        return handled
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        builtMenu?.close()
        builtMenu = null
    }

    private fun rebuild(menu: Menu) {
        builtMenu?.clear(menu)
        builtMenu?.close()
        builtMenu = BuiltAndroidMenu(env, readMenuSnapshot(selectionMenuPtr, env)).also {
            it.populate(menu)
        }
    }
}

private fun applyMenuTriggerFeedback(container: FrameLayout) {
    val typedValue = android.util.TypedValue()
    if (container.context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)) {
        container.foreground = AppCompatResources.getDrawable(container.context, typedValue.resourceId)
    }
}

private val menuRenderer = WuiRenderer { context, node, env, registry ->
    val struct: MenuStruct = NativeBindings.waterui_force_as_menu(node.rawPtr)
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)

    FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        applyMenuTriggerFeedback(this)
        addView(labelView)
        val accessibilityLabel = installSemanticAccessibilityLabel(
            target = this,
            content = labelView,
            labelPtr = struct.accessibilityLabelPtr,
            env = env
        )
        setOnClickListener {
            showPopupMenu(this, struct.itemsPtr, env)
        }
        disposeWith {
            accessibilityLabel?.close()
            NativeBindings.waterui_drop_computed_menu_items(struct.itemsPtr)
        }
    }
}

private val metadataContextMenuRenderer = WuiRenderer { context, node, env, registry ->
    val metadata: MetadataContextMenuStruct = NativeBindings.waterui_force_as_metadata_context_menu(node.rawPtr)
    val container = PassThroughFrameLayout(context).apply {
        consumesTouches = true
        setTag(PassThroughFrameLayout.TAG_WANTS_TOUCHES, true)
        isLongClickable = true
    }

    require(metadata.contentPtr != 0L) { "MetadataContextMenu.contentPtr is null" }
    val child = inflateAnyView(context, metadata.contentPtr, env, registry)
    container.addView(child)
    container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    container.setOnLongClickListener {
        showPopupMenu(it, metadata.itemsPtr, env)
    }
    container.disposeWith {
        NativeBindings.waterui_drop_computed_menu_items(metadata.itemsPtr)
    }
    container
}

internal fun installTextSelectionMenu(
    editText: AppCompatEditText,
    selectionMenuPtr: Long,
    env: WuiEnvironment
) {
    if (selectionMenuPtr == 0L) {
        return
    }

    editText.customSelectionActionModeCallback = SelectionMenuCallback(selectionMenuPtr, env)
    editText.disposeWith {
        NativeBindings.waterui_drop_computed_menu_items(selectionMenuPtr)
    }
}

internal fun populateAndroidMenu(
    menu: Menu,
    itemsPtr: Long,
    env: WuiEnvironment
): AndroidMenuHandle? {
    val nodes = readMenuSnapshot(itemsPtr, env)
    if (nodes.isEmpty()) {
        return null
    }
    return BuiltAndroidMenu(env, nodes).also { it.populate(menu) }
}

internal fun RegistryBuilder.registerWuiMenu() {
    register({ menuTypeId }, menuRenderer)
}

internal fun RegistryBuilder.registerWuiContextMenu() {
    registerMetadata({ metadataContextMenuTypeId }, metadataContextMenuRenderer)
}
