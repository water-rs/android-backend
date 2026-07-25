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
import androidx.core.view.doOnLayout
import com.google.android.material.card.MaterialCardView
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.reactive.WatcherGuard
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.NativeAnyViews
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
) : RecyclerView.ViewHolder(card) {
    var model: ListItemModel? = null
    var ownsModel: Boolean = false
}

private class WuiListAdapter(
    private val context: Context,
    contentsPtr: Long,
    editingPtr: Long,
    private val usesSections: Boolean,
    private val onDeletePtr: Long,
    private val onMovePtr: Long,
    private val env: WuiEnvironment,
    registry: RenderRegistry
) : RecyclerView.Adapter<ListItemHolder>(), Closeable {
    private val editing = WuiComputed.bool(editingPtr)
    private val surface = ThemeBridge.surface(env)
    private val border = ThemeBridge.border(env)
    private val mutedForeground = ThemeBridge.mutedForeground(env)
    private val source = NativeAnyViews(contentsPtr)
    private val sourceWatcher: WatcherGuard
    private val sectionModels = linkedMapOf<Int, ListItemModel>()
    private val visibleFlatModels = mutableSetOf<ListItemModel>()
    private var itemIds = emptyList<Int>()
    private var footerAfter = emptyMap<Int, String>()

    private fun materialize(position: Int): ListItemModel {
        val viewPtr = source.viewAt(position)
        check(viewPtr != 0L) {
            "native List collection returned a null view at index $position"
        }
        val actualType = NativeBindings.waterui_view_id(viewPtr).toTypeId()
        check(actualType == listItemTypeId) {
            "native List collection expected $listItemTypeId at index $position, got $actualType"
        }
        val item = NativeBindings.waterui_force_as_list_item(viewPtr)
        return ListItemModel(
            contentPtr = item.contentPtr,
            deletablePtr = item.deletablePtr,
            sectionLabel = item.sectionLabel,
            sectionFooter = item.sectionFooter,
            context = context,
            env = env,
            registry = registry
        )
    }
    private var isEditing = false
    private var surfaceColor = 0
    private var borderColor = 0
    private var sectionTextColor = 0

    init {
        setHasStableIds(true)
        sourceWatcher = source.watch(::updateIds)
        updateIds(source.ids())
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
        releaseHolderModel(holder)
        val id = itemIds[position]
        val model = if (usesSections) {
            checkNotNull(sectionModels[id]) {
                "sectioned List row $id was not materialized"
            }
        } else {
            materialize(position).also(visibleFlatModels::add)
        }
        holder.model = model
        holder.ownsModel = !usesSections
        bindTheme(holder)
        bindSectionText(holder.header, model.sectionLabel)
        bindSectionText(holder.footer, footerAfter[position])
        holder.content.removeAllViews()
        holder.content.addView(
            model.takeContentView(),
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
        releaseHolderModel(holder)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = itemIds.size

    override fun getItemId(position: Int): Long = itemIds[position].toLong()

    fun movementFlags(holder: RecyclerView.ViewHolder): Int {
        val item = (holder as? ListItemHolder)?.model
            ?: error("List movement flags require a bound ListItemHolder")
        val drag = if (isEditing && onMovePtr != 0L) {
            ItemTouchHelper.UP or ItemTouchHelper.DOWN
        } else {
            0
        }
        val swipe = if (onDeletePtr != 0L && item.isDeletable) {
            ItemTouchHelper.START or ItemTouchHelper.END
        } else {
            0
        }
        return ItemTouchHelper.Callback.makeMovementFlags(drag, swipe)
    }

    fun move(from: Int, to: Int): Boolean {
        if (onMovePtr == 0L || from == to) return false
        val reordered = itemIds.toMutableList()
        val moved = reordered.removeAt(from)
        reordered.add(to, moved)
        itemIds = reordered
        footerAfter = computeFooters()
        notifyItemMoved(from, to)
        NativeBindings.waterui_call_move_action(onMovePtr, env.raw(), from, to)
        return true
    }

    fun delete(position: Int) {
        val id = itemIds[position]
        NativeBindings.waterui_call_index_action(onDeletePtr, env.raw(), position)
        val currentPosition = itemIds.indexOf(id)
        if (currentPosition >= 0) notifyItemChanged(currentPosition)
    }

    override fun close() {
        sourceWatcher.close()
        source.close()
        sectionModels.values.forEach(ListItemModel::close)
        sectionModels.clear()
        visibleFlatModels.toList().forEach(ListItemModel::close)
        visibleFlatModels.clear()
        itemIds = emptyList()
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

    private fun releaseHolderModel(holder: ListItemHolder) {
        val model = holder.model ?: return
        if (holder.ownsModel) {
            visibleFlatModels.remove(model)
            model.close()
        }
        holder.model = null
        holder.ownsModel = false
    }

    private fun updateIds(nextIds: IntArray) {
        val previous = itemIds
        val next = nextIds.toList()
        if (usesSections) {
            val previousModels = LinkedHashMap(sectionModels)
            sectionModels.clear()
            next.forEachIndexed { index, id ->
                check(!sectionModels.containsKey(id)) {
                    "native List collection contains duplicate id $id"
                }
                sectionModels[id] = previousModels.remove(id) ?: materialize(index)
            }
            previousModels.values.forEach(ListItemModel::close)
        }
        itemIds = next
        footerAfter = computeFooters()

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = previous.size
            override fun getNewListSize(): Int = next.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == next[newItemPosition]
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition] == next[newItemPosition]
        })
        diff.dispatchUpdatesTo(this)
    }

    private fun computeFooters(): Map<Int, String> {
        if (!usesSections) return emptyMap()
        val footers = mutableMapOf<Int, String>()
        var activeFooter: String? = null
        itemIds.forEachIndexed { index, id ->
            val item = checkNotNull(sectionModels[id]) {
                "sectioned List row $id was not materialized"
            }
            if (item.sectionLabel != null || item.sectionFooter != null) {
                if (index > 0 && activeFooter != null) footers[index - 1] = activeFooter
                activeFooter = item.sectionFooter
            }
        }
        if (itemIds.isNotEmpty() && activeFooter != null) footers[itemIds.lastIndex] = activeFooter
        return footers
    }
}

private class ListTouchCallback(
    private val adapter: WuiListAdapter
) : ItemTouchHelper.Callback() {
    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int = adapter.movementFlags(viewHolder)

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
        usesSections = struct.usesSections,
        onDeletePtr = struct.onDeletePtr,
        onMovePtr = struct.onMovePtr,
        env = env,
        registry = registry
    )
    val recyclerView = RecyclerView(context).apply {
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
    val controlled = struct.scrollGenerationPtr != 0L
    check((struct.targetIndexPtr != 0L) == controlled) {
        "WaterUI List controller pointers must be either both null or both non-null"
    }
    if (controlled) {
        val targetIndex = WuiComputed.int(struct.targetIndexPtr)
        val generation = WuiComputed.int(struct.scrollGenerationPtr)
        var target = 0
        targetIndex.observe { target = it }
        generation.observe { request ->
            if (request == 0) return@observe
            check(target in 0 until adapter.itemCount) {
                "List scroll target $target exceeds collection length ${adapter.itemCount}"
            }
            recyclerView.doOnLayout {
                val layout = recyclerView.layoutManager as LinearLayoutManager
                layout.scrollToPositionWithOffset(target, 0)
            }
        }
        recyclerView.disposeWith(targetIndex)
        recyclerView.disposeWith(generation)
    }
    recyclerView
}

internal fun RegistryBuilder.registerWuiList() {
    register({ listTypeId }, listRenderer)
}
