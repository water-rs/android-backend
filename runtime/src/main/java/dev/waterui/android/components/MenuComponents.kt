package dev.waterui.android.components

import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuCompat
import androidx.core.view.MenuItemCompat
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.MenuItemStruct
import dev.waterui.android.runtime.MenuItemTag
import dev.waterui.android.runtime.MenuStruct
import dev.waterui.android.runtime.MetadataContextMenuStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativeViewCollection
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ReactiveStyledText
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView
import java.io.Closeable

private val menuTypeId: WuiTypeId by lazy { NativeBindings.waterui_menu_id().toTypeId() }
private val menuItemTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_menu_item_id().toTypeId()
}
private val metadataContextMenuTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_context_menu_id().toTypeId()
}

private const val MENU_ITEM_ID_BASE = 0x575500

private class MenuInvalidator {
    var listener: () -> Unit = {}
    private var mutationDepth = 0
    private var pending = false

    fun mutate(action: () -> Unit) {
        mutationDepth += 1
        try {
            action()
        } finally {
            mutationDepth -= 1
            if (mutationDepth == 0 && pending) {
                pending = false
                listener()
            }
        }
    }

    fun invalidate() {
        if (mutationDepth == 0) {
            listener()
        } else {
            pending = true
        }
    }
}

private data class AndroidMenuShortcut(
    val keyEquivalent: String,
    val command: Boolean,
    val shift: Boolean,
    val option: Boolean,
    val control: Boolean
) {
    fun applyTo(item: MenuItem) {
        val key = keyEquivalent.singleOrNull()
            ?: error("Android menu shortcuts require exactly one character: $keyEquivalent")
        var modifiers = 0
        if (command) modifiers = modifiers or KeyEvent.META_META_ON
        if (shift) modifiers = modifiers or KeyEvent.META_SHIFT_ON
        if (option) modifiers = modifiers or KeyEvent.META_ALT_ON
        if (control) modifiers = modifiers or KeyEvent.META_CTRL_ON
        MenuItemCompat.setAlphabeticShortcut(item, key, modifiers)
    }
}

private class MenuBuildState(
    val actions: MutableMap<Int, Long>,
    val topLevelItemIds: MutableList<Int>
) {
    private var nextItemId = MENU_ITEM_ID_BASE

    fun nextId(): Int = nextItemId++
}

private sealed interface ReactiveMenuNode : Closeable {
    fun append(menu: Menu, state: MenuBuildState, groupId: Int, order: Int, isTopLevel: Boolean)
    fun detach()

    class Command(
        labelPtr: Long,
        private val actionPtr: Long,
        disabledPtr: Long,
        selectedPtr: Long,
        private val shortcut: AndroidMenuShortcut?,
        env: WuiEnvironment
    ) : ReactiveMenuNode {
        private val label = ReactiveStyledText(labelPtr, env)
        private val disabled = disabledPtr.takeIf { it != 0L }?.let { WuiComputed.bool(it) }
        private val selected = selectedPtr.takeIf { it != 0L }?.let { WuiComputed.bool(it) }
        private var isDisabled = false
        private var isSelected = false
        private var item: MenuItem? = null

        init {
            disabled?.observe { value ->
                isDisabled = value
                item?.isEnabled = !value && actionPtr != 0L
            }
            selected?.observe { value ->
                isSelected = value
                item?.isChecked = value
            }
        }

        override fun append(
            menu: Menu,
            state: MenuBuildState,
            groupId: Int,
            order: Int,
            isTopLevel: Boolean
        ) {
            val itemId = state.nextId()
            val created = menu.add(groupId, itemId, order, "")
            item = created
            label.attach { created.title = it }
            created.isEnabled = !isDisabled && actionPtr != 0L
            created.isCheckable = selected != null
            created.isChecked = isSelected
            shortcut?.applyTo(created)
            if (actionPtr != 0L) {
                state.actions[itemId] = actionPtr
            }
            if (isTopLevel) {
                state.topLevelItemIds += itemId
            }
        }

        override fun detach() {
            label.detach()
            item = null
        }

        override fun close() {
            detach()
            label.close()
            disabled?.close()
            selected?.close()
            if (actionPtr != 0L) {
                NativeBindings.waterui_drop_shared_action(actionPtr)
            }
        }
    }

    class Nested(
        labelPtr: Long,
        itemsPtr: Long,
        env: WuiEnvironment,
        invalidator: MenuInvalidator
    ) : ReactiveMenuNode {
        private val label = ReactiveStyledText(labelPtr, env)
        private val children = ReactiveMenuGroup(itemsPtr, env, invalidator)

        override fun append(
            menu: Menu,
            state: MenuBuildState,
            groupId: Int,
            order: Int,
            isTopLevel: Boolean
        ) {
            val itemId = state.nextId()
            val subMenu = menu.addSubMenu(groupId, itemId, order, "")
            MenuCompat.setGroupDividerEnabled(subMenu, true)
            label.attach { subMenu.item.title = it }
            if (isTopLevel) {
                state.topLevelItemIds += itemId
            }
            children.append(subMenu, state, isTopLevel = false)
        }

        override fun detach() {
            label.detach()
            children.detach()
        }

        override fun close() {
            detach()
            label.close()
            children.close()
        }
    }

    data object Divider : ReactiveMenuNode {
        override fun append(
            menu: Menu,
            state: MenuBuildState,
            groupId: Int,
            order: Int,
            isTopLevel: Boolean
        ) = Unit

        override fun detach() = Unit
        override fun close() = Unit
    }

    companion object {
        fun consume(
            item: MenuItemStruct,
            env: WuiEnvironment,
            invalidator: MenuInvalidator
        ): ReactiveMenuNode = when (MenuItemTag.fromInt(item.tag)) {
            MenuItemTag.COMMAND -> {
                require(item.labelPtr != 0L) { "menu command label pointer is null" }
                require(item.itemsPtr == 0L) { "menu command unexpectedly contains nested items" }
                Command(
                    labelPtr = item.labelPtr,
                    actionPtr = item.actionPtr,
                    disabledPtr = item.disabledPtr,
                    selectedPtr = item.selectedPtr,
                    shortcut = item.keyEquivalent?.let { key ->
                        AndroidMenuShortcut(
                            keyEquivalent = key,
                            command = item.command,
                            shift = item.shift,
                            option = item.option,
                            control = item.control
                        )
                    },
                    env = env
                )
            }

            MenuItemTag.DIVIDER -> {
                require(
                    item.labelPtr == 0L && item.actionPtr == 0L &&
                        item.disabledPtr == 0L && item.selectedPtr == 0L && item.itemsPtr == 0L
                ) { "menu divider unexpectedly owns presentation pointers" }
                Divider
            }

            MenuItemTag.MENU -> {
                require(item.labelPtr != 0L) { "nested menu label pointer is null" }
                require(item.itemsPtr != 0L) { "nested menu items pointer is null" }
                require(
                    item.actionPtr == 0L && item.disabledPtr == 0L && item.selectedPtr == 0L
                ) { "nested menu unexpectedly owns command state" }
                Nested(item.labelPtr, item.itemsPtr, env, invalidator)
            }
        }
    }
}

private class ReactiveMenuGroup(
    itemsPtr: Long,
    private val env: WuiEnvironment,
    private val invalidator: MenuInvalidator
) : Closeable {
    private val items = NativeViewCollection(
        handle = itemsPtr,
        expectedType = menuItemTypeId
    ) { viewPtr ->
        ReactiveMenuNode.consume(
            NativeBindings.waterui_force_as_menu_item(viewPtr),
            env,
            invalidator
        )
    }
    private var nodes: List<ReactiveMenuNode> = emptyList()

    init {
        items.observe { values ->
            invalidator.mutate {
                nodes = values.map { it.value }
                invalidator.invalidate()
            }
        }
    }

    val isEmpty: Boolean
        get() = nodes.isEmpty()

    fun append(menu: Menu, state: MenuBuildState, isTopLevel: Boolean) {
        var groupId = 0
        var order = 0
        nodes.forEach { node ->
            if (node === ReactiveMenuNode.Divider) {
                groupId += 1
            } else {
                node.append(menu, state, groupId, order++, isTopLevel)
            }
        }
    }

    fun detach() {
        nodes.forEach(ReactiveMenuNode::detach)
    }

    override fun close() {
        detach()
        nodes = emptyList()
        items.close()
    }
}

private class ReactiveAndroidMenu(
    itemsPtr: Long,
    private val env: WuiEnvironment
) : Closeable {
    private val invalidator = MenuInvalidator()
    private val root = ReactiveMenuGroup(itemsPtr, env, invalidator)
    private val actions = mutableMapOf<Int, Long>()
    private val topLevelItemIds = mutableListOf<Int>()
    private var activeMenu: Menu? = null

    init {
        invalidator.listener = ::rebuild
    }

    val isEmpty: Boolean
        get() = root.isEmpty

    fun bind(menu: Menu) {
        activeMenu?.let(::clear)
        activeMenu = menu
        rebuild()
    }

    fun unbind() {
        root.detach()
        actions.clear()
        topLevelItemIds.clear()
        activeMenu = null
    }

    fun onMenuItemSelected(itemId: Int): Boolean {
        val actionPtr = actions[itemId] ?: return false
        NativeBindings.waterui_call_shared_action(actionPtr, env.raw())
        return true
    }

    private fun rebuild() {
        val menu = activeMenu ?: return
        clear(menu)
        MenuCompat.setGroupDividerEnabled(menu, true)
        root.append(menu, MenuBuildState(actions, topLevelItemIds), isTopLevel = true)
    }

    private fun clear(menu: Menu) {
        root.detach()
        topLevelItemIds.forEach(menu::removeItem)
        topLevelItemIds.clear()
        actions.clear()
    }

    override fun close() {
        activeMenu?.let(::clear)
        activeMenu = null
        root.close()
    }
}

private fun showPopupMenu(anchor: View, source: ReactiveAndroidMenu): Boolean {
    if (source.isEmpty) {
        return false
    }
    val popup = PopupMenu(anchor.context, anchor)
    source.bind(popup.menu)
    popup.setOnMenuItemClickListener { item -> source.onMenuItemSelected(item.itemId) }
    popup.setOnDismissListener { source.unbind() }
    popup.show()
    return true
}

private class SelectionMenuCallback(
    private val source: ReactiveAndroidMenu
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (source.isEmpty) {
            return false
        }
        source.bind(menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        source.bind(menu)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val handled = source.onMenuItemSelected(item.itemId)
        if (handled) {
            mode.finish()
        }
        return handled
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        source.unbind()
    }
}

private fun applyMenuTriggerFeedback(container: FrameLayout) {
    val typedValue = android.util.TypedValue()
    if (container.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless,
            typedValue,
            true
        )
    ) {
        container.foreground = AppCompatResources.getDrawable(container.context, typedValue.resourceId)
    }
}

private val menuRenderer = WuiRenderer { context, node, env, registry ->
    val struct: MenuStruct = NativeBindings.waterui_force_as_menu(node.rawPtr)
    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    val source = ReactiveAndroidMenu(struct.itemsPtr, env)

    FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        applyMenuTriggerFeedback(this)
        addView(labelView)
        installSemanticAccessibilityLabel(
            target = this,
            content = labelView,
            labelPtr = struct.accessibilityLabelPtr,
            env = env
        )
        setOnClickListener { showPopupMenu(this, source) }
        disposeWith(source)
    }
}

private val metadataContextMenuRenderer = WuiRenderer { context, node, env, registry ->
    val metadata: MetadataContextMenuStruct =
        NativeBindings.waterui_force_as_metadata_context_menu(node.rawPtr)
    require(metadata.contentPtr != 0L) { "MetadataContextMenu.contentPtr is null" }
    val source = ReactiveAndroidMenu(metadata.itemsPtr, env)
    val child = inflateAnyView(context, metadata.contentPtr, env, registry)

    PassThroughFrameLayout(context).apply {
        consumesTouches = true
        setTag(PassThroughFrameLayout.TAG_WANTS_TOUCHES, true)
        isLongClickable = true
        addView(child)
        setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
        setOnLongClickListener { showPopupMenu(it, source) }
        disposeWith(source)
    }
}

internal fun installTextSelectionMenu(
    editText: AppCompatEditText,
    selectionMenuPtr: Long,
    env: WuiEnvironment
) {
    if (selectionMenuPtr == 0L) {
        return
    }
    val source = ReactiveAndroidMenu(selectionMenuPtr, env)
    editText.customSelectionActionModeCallback = SelectionMenuCallback(source)
    editText.disposeWith(source)
}

internal fun RegistryBuilder.registerWuiMenu() {
    register({ menuTypeId }, menuRenderer)
}

internal fun RegistryBuilder.registerWuiContextMenu() {
    registerMetadata({ metadataContextMenuTypeId }, metadataContextMenuRenderer)
}
