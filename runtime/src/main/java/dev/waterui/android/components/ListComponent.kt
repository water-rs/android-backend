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
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.RenderRegistry

private val listTypeId: WuiTypeId by lazy { WatcherJni.listId().toTypeId() }

/**
 * List component renderer.
 * Renders a scrollable list of items using RecyclerView.
 * Supports swipe-to-delete and drag-to-reorder via ItemTouchHelper.
 */
private val listRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsList(node.rawPtr)

    val recyclerView = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val contentsPtr = struct.contentsPtr
    val adapter = WuiListAdapter(context, contentsPtr, env, registry)
    recyclerView.adapter = adapter
    var contentsWatcherGuard: Long = 0L
    if (contentsPtr != 0L) {
        contentsWatcherGuard = WatcherJni.anyViewsWatch(contentsPtr, Runnable {
            recyclerView.post {
                adapter.reload()
            }
        })
    }

    // Setup editing state watcher if provided
    val editingComputed = struct.editingPtr.takeIf { it != 0L }?.let { WuiComputedBool(it) }

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

                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) return false

                // Update local order immediately for smooth UI.
                adapter.move(fromPosition, toPosition)

                // Call Rust callback
                WatcherJni.callMoveAction(
                    onMovePtr,
                    env.raw(),
                    fromPosition.toLong(),
                    toPosition.toLong()
                )

                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                if (onDeletePtr == 0L) return

                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return

                // Check if item is deletable
                val isDeletable = adapter.isDeletable(position)
                if (!isDeletable) {
                    // Restore the item if not deletable
                    adapter.notifyItemChanged(position)
                    return
                }

                // Remove from local list
                adapter.removeAt(position)

                // Call Rust callback
                WatcherJni.callIndexAction(
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

                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return 0

                val isDeletable = adapter.isDeletable(position)
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
        if (contentsWatcherGuard != 0L) {
            WatcherJni.dropWatcherGuard(contentsWatcherGuard)
            contentsWatcherGuard = 0L
        }
        if (contentsPtr != 0L) {
            WatcherJni.dropAnyViews(contentsPtr)
        }
        editingComputed?.dispose()
        if (onDeletePtr != 0L) {
            WatcherJni.dropIndexAction(onDeletePtr)
        }
        if (onMovePtr != 0L) {
            WatcherJni.dropMoveAction(onMovePtr)
        }
    }

    recyclerView
}

/**
 * RecyclerView adapter for WaterUI List.
 */
private class WuiListAdapter(
    private val context: Context,
    private val contentsPtr: Long,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry
) : RecyclerView.Adapter<WuiListAdapter.ViewHolder>() {

    class ViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    // A local order mapping so we can reflect drag/delete immediately without
    // caching AnyView pointers (AnyView is single-consume).
    private val order: MutableList<Int> = mutableListOf()

    init {
        setHasStableIds(true)
        reload()
    }

    fun reload() {
        order.clear()
        if (contentsPtr == 0L) return
        val count = WatcherJni.anyViewsLen(contentsPtr)
        for (i in 0 until count) {
            order.add(i)
        }
        notifyDataSetChanged()
    }

    fun move(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition) return
        if (fromPosition !in order.indices || toPosition !in order.indices) return
        val item = order.removeAt(fromPosition)
        order.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    fun removeAt(position: Int) {
        if (position !in order.indices) return
        order.removeAt(position)
        notifyItemRemoved(position)
    }

    private fun underlyingIndex(position: Int): Int? =
        order.getOrNull(position)

    fun isDeletable(position: Int): Boolean {
        val idx = underlyingIndex(position) ?: return true
        if (contentsPtr == 0L) return true

        val viewPtr = WatcherJni.anyViewsGetView(contentsPtr, idx)
        if (viewPtr == 0L) return true

        val listItem = WatcherJni.forceAsListItem(viewPtr)
        val deletablePtr = listItem.deletablePtr
        if (deletablePtr == 0L) return true

        return try {
            WatcherJni.readComputedBool(deletablePtr)
        } finally {
            WatcherJni.dropComputedBool(deletablePtr)
        }
    }

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
        holder.container.removeAllViews()

        val idx = underlyingIndex(position) ?: return
        if (contentsPtr == 0L) return

        // AnyView is single-consume: fetch a fresh ListItem AnyView each bind.
        val viewPtr = WatcherJni.anyViewsGetView(contentsPtr, idx)
        if (viewPtr == 0L) return

        val listItem = WatcherJni.forceAsListItem(viewPtr)
        val contentPtr = listItem.contentPtr
        if (contentPtr != 0L) {
            val contentView = inflateAnyView(context, contentPtr, env, registry)
            holder.container.addView(
                contentView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Drop item-level computeds we didn't install watchers for.
        if (listItem.deletablePtr != 0L) {
            WatcherJni.dropComputedBool(listItem.deletablePtr)
        }
    }

    override fun getItemCount(): Int = order.size

    override fun getItemId(position: Int): Long {
        val idx = underlyingIndex(position) ?: return RecyclerView.NO_ID
        if (contentsPtr == 0L) return RecyclerView.NO_ID
        return WatcherJni.anyViewsGetId(contentsPtr, idx).toLong()
    }
}

/**
 * Register List component with the registry.
 */
internal fun RegistryBuilder.registerWuiList() {
    register({ listTypeId }, listRenderer)
}
