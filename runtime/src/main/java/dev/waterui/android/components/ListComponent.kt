package dev.waterui.android.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativeViewCollection
import dev.waterui.android.runtime.NativeViewItem
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWuiTree
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import java.io.Closeable

private val listTypeId: WuiTypeId by lazy { NativeBindings.waterui_list_id().toTypeId() }
private val listItemTypeId: WuiTypeId by lazy { NativeBindings.waterui_list_item_id().toTypeId() }
private const val THEME_PAYLOAD = "waterui.list.theme"

private class ListItemModel(
    contentPtr: Long,
    deletablePtr: Long,
    val sectionLabel: String?,
    val sectionFooter: String?,
    private val context: Context,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry
) : Closeable {
    private var contentPtr = contentPtr
    private var contentView: View? = null
    private var contentDisposed = false
    private val deletable = WuiComputed.bool(deletablePtr)
    var isDeletable = false
        private set

    init {
        deletable.observe { value ->
            isDeletable = value
        }
    }

    fun takeContentView(): View {
        val view = contentView ?: inflateAnyView(context, contentPtr, env, registry).also {
            contentPtr = 0L
            it.disposeWith { contentDisposed = true }
            contentView = it
        }
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    override fun close() {
        deletable.close()
        val view = contentView
        if (view == null) {
            NativeBindings.waterui_drop_any_view(contentPtr)
        } else {
            (view.parent as? ViewGroup)?.removeView(view)
            if (!contentDisposed) {
                view.disposeWuiTree()
            }
        }
        contentPtr = 0L
        contentView = null
    }
}

private class ListItemHolder(
    val card: MaterialCardView,
    val header: TextView,
    val content: FrameLayout,
    val footer: TextView
) : RecyclerView.ViewHolder(card)

private class WuiListAdapter(
    private val context: Context,
    contentsPtr: Long,
    editingPtr: Long,
    private val onDeletePtr: Long,
    private val onMovePtr: Long,
    private val env: WuiEnvironment,
    registry: RenderRegistry
) : RecyclerView.Adapter<ListItemHolder>(), Closeable {
    private val editing = WuiComputed.bool(editingPtr)
    private val surface = ThemeBridge.surface(env)
    private val border = ThemeBridge.border(env)
    private val mutedForeground = ThemeBridge.mutedForeground(env)
    private val source = NativeViewCollection(
        handle = contentsPtr,
        expectedType = listItemTypeId
    ) { viewPtr ->
        val item = NativeBindings.waterui_force_as_list_item(viewPtr)
        ListItemModel(
            contentPtr = item.contentPtr,
            deletablePtr = item.deletablePtr,
            sectionLabel = item.sectionLabel,
            sectionFooter = item.sectionFooter,
            context = context,
            env = env,
            registry = registry
        )
    }
    private var items = emptyList<NativeViewItem<ListItemModel>>()
    private var footerAfter = emptyMap<Int, String>()
    private var isEditing = false
    private var surfaceColor = 0
    private var borderColor = 0
    private var sectionTextColor = 0

    init {
        setHasStableIds(true)
        source.observe(::updateItems)
        editing.observe { value ->
            isEditing = value
        }
        surface.observe { color ->
            surfaceColor = color.toColorInt()
            notifyItemRangeChanged(0, itemCount, THEME_PAYLOAD)
        }
        border.observe { color ->
            borderColor = color.toColorInt()
            notifyItemRangeChanged(0, itemCount, THEME_PAYLOAD)
        }
        mutedForeground.observe { color ->
            sectionTextColor = color.toColorInt()
            notifyItemRangeChanged(0, itemCount, THEME_PAYLOAD)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListItemHolder {
        val horizontalMargin = 12f.dp(context).toInt()
        val verticalMargin = 4f.dp(context).toInt()
        val card = MaterialCardView(context).apply {
            radius = 12f.dp(context)
            strokeWidth = 1f.dp(context).toInt()
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin)
            }
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val header = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setPadding(
                16f.dp(context).toInt(),
                12f.dp(context).toInt(),
                16f.dp(context).toInt(),
                4f.dp(context).toInt()
            )
        }
        val content = FrameLayout(context).apply {
            minimumHeight = 48f.dp(context).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val footer = TextView(context).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setPadding(
                16f.dp(context).toInt(),
                4f.dp(context).toInt(),
                16f.dp(context).toInt(),
                12f.dp(context).toInt()
            )
        }
        column.addView(header)
        column.addView(content)
        column.addView(footer)
        card.addView(column)
        return ListItemHolder(card, header, content, footer)
    }

    override fun onBindViewHolder(holder: ListItemHolder, position: Int) {
        val item = items[position]
        bindTheme(holder)
        bindSectionText(holder.header, item.value.sectionLabel)
        bindSectionText(holder.footer, footerAfter[position])
        holder.content.removeAllViews()
        holder.content.addView(
            item.value.takeContentView(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    override fun onBindViewHolder(
        holder: ListItemHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
        } else {
            bindTheme(holder)
        }
    }

    override fun onViewRecycled(holder: ListItemHolder) {
        holder.content.removeAllViews()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id.toLong()

    fun movementFlags(position: Int): Int {
        val drag = if (isEditing && onMovePtr != 0L) {
            ItemTouchHelper.UP or ItemTouchHelper.DOWN
        } else {
            0
        }
        val swipe = if (onDeletePtr != 0L && items[position].value.isDeletable) {
            ItemTouchHelper.START or ItemTouchHelper.END
        } else {
            0
        }
        return ItemTouchHelper.Callback.makeMovementFlags(drag, swipe)
    }

    fun move(from: Int, to: Int): Boolean {
        if (onMovePtr == 0L || from == to) return false
        val reordered = items.toMutableList()
        val moved = reordered.removeAt(from)
        reordered.add(to, moved)
        items = reordered
        footerAfter = computeFooters(items)
        notifyItemMoved(from, to)
        NativeBindings.waterui_call_move_action(onMovePtr, env.raw(), from, to)
        return true
    }

    fun delete(position: Int) {
        val id = items[position].id
        NativeBindings.waterui_call_index_action(onDeletePtr, env.raw(), position)
        val currentPosition = items.indexOfFirst { it.id == id }
        if (currentPosition >= 0) notifyItemChanged(currentPosition)
    }

    override fun close() {
        source.close()
        items = emptyList()
        footerAfter = emptyMap()
        editing.close()
        surface.close()
        border.close()
        mutedForeground.close()
        if (onDeletePtr != 0L) NativeBindings.waterui_drop_index_action(onDeletePtr)
        if (onMovePtr != 0L) NativeBindings.waterui_drop_move_action(onMovePtr)
    }

    private fun bindTheme(holder: ListItemHolder) {
        holder.card.setCardBackgroundColor(surfaceColor)
        holder.card.strokeColor = borderColor
        holder.header.setTextColor(sectionTextColor)
        holder.footer.setTextColor(sectionTextColor)
    }

    private fun bindSectionText(view: TextView, value: String?) {
        view.text = value.orEmpty()
        view.visibility = if (value == null) View.GONE else View.VISIBLE
    }

    private fun updateItems(next: List<NativeViewItem<ListItemModel>>) {
        val previous = items
        items = next
        footerAfter = computeFooters(next)

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].id == next[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].id == next[newItemPosition].id
        })
        diff.dispatchUpdatesTo(this)
    }

    private fun computeFooters(values: List<NativeViewItem<ListItemModel>>): Map<Int, String> {
        val footers = mutableMapOf<Int, String>()
        var activeFooter: String? = null
        values.forEachIndexed { index, item ->
            if (item.value.sectionLabel != null || item.value.sectionFooter != null) {
                if (index > 0 && activeFooter != null) footers[index - 1] = activeFooter
                activeFooter = item.value.sectionFooter
            }
        }
        if (values.isNotEmpty() && activeFooter != null) footers[values.lastIndex] = activeFooter
        return footers
    }
}

private class ListTouchCallback(
    private val adapter: WuiListAdapter
) : ItemTouchHelper.Callback() {
    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int = adapter.movementFlags(viewHolder.bindingAdapterPosition)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.delete(viewHolder.bindingAdapterPosition)
    }
}

private val listRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_list(node.rawPtr)
    val adapter = WuiListAdapter(
        context = context,
        contentsPtr = struct.contentsPtr,
        editingPtr = struct.editingPtr,
        onDeletePtr = struct.onDeletePtr,
        onMovePtr = struct.onMovePtr,
        env = env,
        registry = registry
    )
    RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        this.adapter = adapter
        ItemTouchHelper(ListTouchCallback(adapter)).attachToRecyclerView(this)
        val background = ThemeBridge.background(env)
        background.observe { color -> setBackgroundColor(color.toColorInt()) }
        disposeWith(background)
        disposeWith {
            this.adapter = null
            adapter.close()
        }
    }
}

internal fun RegistryBuilder.registerWuiList() {
    register({ listTypeId }, listRenderer)
}
