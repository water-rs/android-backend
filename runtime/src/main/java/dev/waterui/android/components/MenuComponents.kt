package dev.waterui.android.components

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuCompat
import androidx.core.view.MenuItemCompat
import com.google.android.material.color.MaterialColors
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.CommandRole
import dev.waterui.android.runtime.MenuItemStruct
import dev.waterui.android.runtime.MenuItemTag
import dev.waterui.android.runtime.MenuStruct
import dev.waterui.android.runtime.MetadataContextMenuStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativeViewCollection
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ReactiveStyledText
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.disposeWuiTree
import dev.waterui.android.runtime.inflateAnyView
import java.io.Closeable
import androidx.appcompat.R as AppCompatR
import com.google.android.material.R as MaterialR

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
    val topLevelItemIds: MutableList<Int>,
    private val context: Context
) {
    private var nextItemId = MENU_ITEM_ID_BASE

    fun nextId(): Int = nextItemId++

    val destructiveColor: Int by lazy {
        MaterialColors.getColor(
            context,
            AppCompatR.attr.colorError,
            "WaterUI menus require a Material colorError for destructive commands"
        )
    }
    val subtitleColor: Int by lazy {
        MaterialColors.getColor(
            context,
            MaterialR.attr.colorOnSurfaceVariant,
            "WaterUI menus require a Material colorOnSurfaceVariant for subtitles"
        )
    }
}

private const val SUBTITLE_SIZE_RATIO = 0.85f

/**
 * The rendered title of one command row: the label, tinted in the Material
 * error colour when the command is destructive, followed by the subtitle on a
 * second, de-emphasized line when the command carries one.
 */
internal fun composeMenuItemTitle(
    label: CharSequence,
    subtitle: String?,
    destructiveColor: Int?,
    subtitleColor: Int
): CharSequence {
    if (destructiveColor == null && subtitle == null) {
        return label
    }
    val builder = SpannableStringBuilder(label)
    if (destructiveColor != null) {
        builder.setSpan(
            ForegroundColorSpan(destructiveColor),
            0,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    if (subtitle != null) {
        builder.append('\n')
        val start = builder.length
        builder.append(subtitle)
        builder.setSpan(
            RelativeSizeSpan(SUBTITLE_SIZE_RATIO),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            ForegroundColorSpan(subtitleColor),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    return builder
}

private sealed interface ReactiveMenuNode : Closeable {
    fun append(menu: Menu, state: MenuBuildState, groupId: Int, order: Int, isTopLevel: Boolean)
    fun detach()

    class Command(
        labelPtr: Long,
        private val actionPtr: Long,
        disabledPtr: Long,
        selectedPtr: Long,
        private val role: CommandRole,
        private val subtitle: String?,
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
            label.attach {
                created.title = composeMenuItemTitle(
                    label = it,
                    subtitle = subtitle,
                    destructiveColor = if (role == CommandRole.DESTRUCTIVE) {
                        state.destructiveColor
                    } else {
                        null
                    },
                    subtitleColor = state.subtitleColor
                )
            }
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
                    role = CommandRole.fromInt(item.role),
                    subtitle = item.subtitle,
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
                        item.disabledPtr == 0L && item.selectedPtr == 0L && item.itemsPtr == 0L &&
                        item.role == CommandRole.STANDARD.value && item.subtitle == null
                ) { "menu divider unexpectedly owns presentation pointers" }
                Divider
            }

            MenuItemTag.MENU -> {
                require(item.labelPtr != 0L) { "nested menu label pointer is null" }
                require(item.itemsPtr != 0L) { "nested menu items pointer is null" }
                require(
                    item.actionPtr == 0L && item.disabledPtr == 0L && item.selectedPtr == 0L &&
                        item.role == CommandRole.STANDARD.value && item.subtitle == null
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
) : ContextMenuSource, Closeable {
    private val invalidator = MenuInvalidator()
    private val root = ReactiveMenuGroup(itemsPtr, env, invalidator)
    private val actions = mutableMapOf<Int, Long>()
    private val topLevelItemIds = mutableListOf<Int>()
    private var activeMenu: Menu? = null
    private var bindContext: Context? = null

    init {
        invalidator.listener = ::rebuild
    }

    override var onRebuilt: (() -> Unit)? = null

    override val isEmpty: Boolean
        get() = root.isEmpty

    override fun bind(menu: Menu, context: Context) {
        activeMenu?.let(::clear)
        activeMenu = menu
        bindContext = context
        rebuild()
    }

    override fun unbind() {
        root.detach()
        actions.clear()
        topLevelItemIds.clear()
        activeMenu = null
        bindContext = null
    }

    override fun onMenuItemSelected(itemId: Int): Boolean {
        val actionPtr = actions[itemId] ?: return false
        NativeBindings.waterui_call_shared_action(actionPtr, env.raw())
        return true
    }

    private fun rebuild() {
        val menu = activeMenu ?: return
        val context = bindContext ?: return
        clear(menu)
        MenuCompat.setGroupDividerEnabled(menu, true)
        root.append(menu, MenuBuildState(actions, topLevelItemIds, context), isTopLevel = true)
        onRebuilt?.invoke()
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

private fun showPopupMenu(anchor: View, source: ReactiveAndroidMenu): PopupMenu? {
    if (source.isEmpty) {
        return null
    }
    val popup = PopupMenu(anchor.context, anchor)
    source.bind(popup.menu, anchor.context)
    popup.setOnMenuItemClickListener { item -> source.onMenuItemSelected(item.itemId) }
    popup.setOnDismissListener { source.unbind() }
    popup.show()
    return popup
}

private class SelectionMenuCallback(
    private val source: ReactiveAndroidMenu,
    private val context: Context
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (source.isEmpty) {
            return false
        }
        source.bind(menu, context)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        source.bind(menu, context)
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

private fun applyMenuTriggerFeedback(container: PassThroughFrameLayout) {
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

    // The trigger stands in its label's slot: PassThroughFrameLayout answers
    // probes with the label's own contract answer and lays the label out over
    // whatever frame the WaterUI parent allocates.
    PassThroughFrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        consumesTouches = true
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
    val dismissRequests = metadata.dismissRequestsPtr
        .takeIf { it != 0L }
        ?.let(::WuiDismissRequests)

    PassThroughFrameLayout(context).apply {
        consumesTouches = true
        setTag(PassThroughFrameLayout.TAG_WANTS_TOUCHES, true)
        isLongClickable = true
        addView(child)
        disposeWith(source)
        dismissRequests?.let(::disposeWith)

        if (metadata.previewPtr == 0L && metadata.accessoryPtr == 0L) {
            var popup: PopupMenu? = null
            setOnLongClickListener { anchor ->
                popup = showPopupMenu(anchor, source)
                true
            }
            dismissRequests?.watch { popup?.dismiss() }
        } else {
            val preview = OwnedWuiAnyView(metadata.previewPtr) { ptr ->
                inflateAnyView(context, ptr, env, registry)
            }
            val accessory = OwnedWuiAnyView(metadata.accessoryPtr) { ptr ->
                inflateAnyView(context, ptr, env, registry)
            }
            val presentation = ContextMenuPresentation(
                anchor = this,
                source = source,
                previewView = preview::view,
                accessoryView = accessory::view
            )
            dismissRequests?.watch(presentation::dismiss)
            setOnLongClickListener { presentation.show() }
            disposeWith(preview)
            disposeWith(accessory)
            disposeWith { presentation.dismiss() }
        }
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
    editText.customSelectionActionModeCallback = SelectionMenuCallback(source, editText.context)
    editText.disposeWith(source)
}

/**
 * Owns a raw `*mut WuiAnyView` handle received over JNI. The pointer is consumed
 * exactly once: either lazily inflated into a View the first time [view] is
 * called, or dropped through `waterui_drop_any_view` if the view was never
 * needed. An already-inflated view is disposed through the normal tree
 * disposal so its child pointers are released.
 */
private class OwnedWuiAnyView(
    private var ptr: Long,
    private val inflate: (Long) -> View
) : Closeable {
    private var inflated: View? = null

    fun view(): View? {
        if (inflated == null && ptr != 0L) {
            inflated = inflate(ptr)
            ptr = 0L
        }
        return inflated
    }

    override fun close() {
        inflated?.let { it.disposeWuiTree() }
        inflated = null
        if (ptr != 0L) {
            NativeBindings.waterui_drop_any_view(ptr)
            ptr = 0L
        }
    }
}

internal fun RegistryBuilder.registerWuiMenu() {
    register({ menuTypeId }, menuRenderer)
}

internal fun RegistryBuilder.registerWuiContextMenu() {
    registerMetadata({ metadataContextMenuTypeId }, metadataContextMenuRenderer)
}
