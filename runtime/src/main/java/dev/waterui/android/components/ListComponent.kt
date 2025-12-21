package dev.waterui.android.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.waterui.android.reactive.WuiComputedBool
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.RenderRegistry

private val listTypeId: WuiTypeId by lazy { NativeBindings.waterui_list_id().toTypeId() }

/**
 * List component renderer.
 * Renders a scrollable list of items using RecyclerView.
 * Supports swipe-to-delete and drag-to-reorder via ItemTouchHelper.
 */
private val listRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_list(node.rawPtr)

    val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // Load items from WuiAnyViews
    val items = mutableListOf<ListItemData>()
    val contentsPtr = struct.contentsPtr
    if (contentsPtr != 0L) {
        val count = NativeBindings.waterui_any_views_len(contentsPtr)
        for (i in 0 until count) {
            val id = NativeBindings.waterui_any_views_get_id(contentsPtr, i)
            val viewPtr = NativeBindings.waterui_any_views_get_view(contentsPtr, i)
            if (viewPtr != 0L) {
                // Get the ListItem and extract its content
                val listItem = NativeBindings.waterui_force_as_list_item(viewPtr)

                // Create deletable computed if pointer exists
                val deletableComputed = if (listItem.deletablePtr != 0L) {
                    WuiComputedBool(listItem.deletablePtr)
                } else null

                items.add(ListItemData(id, listItem.contentPtr, deletableComputed))
            }
        }
    }

    val adapter = WuiListAdapter(context, items, env, registry)
    recyclerView.adapter = adapter

    // Setup editing state watcher if provided
    val editingComputed = if (struct.editingPtr != 0L) {
        WuiComputedBool(struct.editingPtr)
    } else null

    // Setup ItemTouchHelper for swipe-to-delete and drag-to-reorder
    val onDeletePtr = struct.onDeletePtr
    val onMovePtr = struct.onMovePtr

    if (onDeletePtr != 0L || onMovePtr != 0L) {
        val touchCallback = object : ItemTouchHelper.SimpleCallback(
            if (onMovePtr != 0L) ItemTouchHelper.UP or ItemTouchHelper.DOWN else 0,
            if (onDeletePtr != 0L) ItemTouchHelper.START or ItemTouchHelper.END else 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                if (onMovePtr == 0L) return false

                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition

                // Update local list
                val item = items.removeAt(fromPosition)
                items.add(toPosition, item)
                adapter.notifyItemMoved(fromPosition, toPosition)

                // Call Rust callback
                NativeBindings.waterui_call_move_action(
                    onMovePtr,
                    env.raw(),
                    fromPosition.toLong(),
                    toPosition.toLong()
                )

                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (onDeletePtr == 0L) return

                val position = viewHolder.adapterPosition
                val item = items[position]

                // Check if item is deletable
                val isDeletable = item.deletable?.value ?: true
                if (!isDeletable) {
                    // Restore the item if not deletable
                    adapter.notifyItemChanged(position)
                    return
                }

                // Remove from local list
                items.removeAt(position)
                adapter.notifyItemRemoved(position)

                // Call Rust callback
                NativeBindings.waterui_call_index_action(
                    onDeletePtr,
                    env.raw(),
                    position.toLong()
                )
            }

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (onDeletePtr == 0L) return 0

                val position = viewHolder.adapterPosition
                if (position < 0 || position >= items.size) return 0

                val item = items[position]
                val isDeletable = item.deletable?.value ?: true
                return if (isDeletable) super.getSwipeDirs(recyclerView, viewHolder) else 0
            }

            override fun isLongPressDragEnabled(): Boolean {
                // Only enable drag if editing mode is on or if there's no editing state
                return onMovePtr != 0L && (editingComputed?.value ?: true)
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                // Draw red background when swiping to delete
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val background = ColorDrawable(Color.RED)

                    if (dX > 0) {
                        background.setBounds(
                            itemView.left,
                            itemView.top,
                            itemView.left + dX.toInt(),
                            itemView.bottom
                        )
                    } else {
                        background.setBounds(
                            itemView.right + dX.toInt(),
                            itemView.top,
                            itemView.right,
                            itemView.bottom
                        )
                    }
                    background.draw(c)
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        val touchHelper = ItemTouchHelper(touchCallback)
        touchHelper.attachToRecyclerView(recyclerView)
    }

    recyclerView.disposeWith {
        if (contentsPtr != 0L) {
            NativeBindings.waterui_drop_any_views(contentsPtr)
        }
        if (struct.editingPtr != 0L) {
            NativeBindings.waterui_drop_binding_bool(struct.editingPtr)
        }
        if (onDeletePtr != 0L) {
            NativeBindings.waterui_drop_index_action(onDeletePtr)
        }
        if (onMovePtr != 0L) {
            NativeBindings.waterui_drop_move_action(onMovePtr)
        }
        // Drop deletable computeds
        items.forEach { item ->
            item.deletable?.dispose()
        }
    }

    recyclerView
}

/**
 * Data for a single list item.
 */
private data class ListItemData(
    val id: Int,
    val contentPtr: Long,
    val deletable: WuiComputedBool?
)

/**
 * RecyclerView adapter for WaterUI List.
 */
private class WuiListAdapter(
    private val context: Context,
    private val items: MutableList<ListItemData>,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry
) : RecyclerView.Adapter<WuiListAdapter.ViewHolder>() {

    class ViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ViewHolder(container)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.container.removeAllViews()

        if (item.contentPtr != 0L) {
            val contentView = inflateAnyView(context, item.contentPtr, env, registry)
            holder.container.addView(contentView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].id.toLong()
}

/**
 * Register List component with the registry.
 */
internal fun RegistryBuilder.registerWuiList() {
    register({ listTypeId }, listRenderer)
}
