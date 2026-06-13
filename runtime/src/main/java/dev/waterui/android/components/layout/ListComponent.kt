package dev.waterui.android.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.waterui.android.reactive.WatcherGuard
import dev.waterui.android.runtime.NativeAnyViews
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.RenderRegistry
import java.io.Closeable

private val listTypeId: WuiTypeId by lazy { NativeBindings.waterui_list_id().toTypeId() }

/**
 * List component renderer.
 * Renders a scrollable list of items using RecyclerView.
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

    val contentsPtr = struct.contentsPtr
    if (contentsPtr == 0L) {
        recyclerView.adapter = WuiListAdapter(context, null, env, registry)
    } else {
        val adapter = WuiListAdapter(context, NativeAnyViews(contentsPtr), env, registry)
        recyclerView.adapter = adapter
        recyclerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                adapter.close()
            }
        })
    }

    recyclerView
}

/**
 * RecyclerView adapter for WaterUI List.
 */
private class WuiListAdapter(
    private val context: Context,
    private val anyViews: NativeAnyViews?,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry
) : RecyclerView.Adapter<WuiListAdapter.ViewHolder>(), Closeable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var watcherGuard: WatcherGuard? = null
    private var itemIds: MutableList<Int> = anyViews?.ids()?.toList()?.toMutableList() ?: mutableListOf()

    init {
        setHasStableIds(true)
        if (anyViews != null) {
            watcherGuard = anyViews.watch {
                mainHandler.post {
                    if (anyViews.isReleased) {
                        return@post
                    }
                    updateIds(anyViews.ids().toList())
                }
            }
        }
    }

    class ViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        /** The item id whose content is currently inflated in [container]. */
        var boundId: Int? = null
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
        val id = itemIds[position]
        // Stable ids + DiffUtil mean a row's content is the same as long as its id
        // is: if this holder already holds that id's inflated content, keep it so
        // its in-flight animation, focus, and accessibility node survive the bind
        // (only re-inflate when the holder is recycled to a different id).
        if (holder.boundId == id && holder.container.childCount > 0) {
            return
        }
        holder.boundId = id
        holder.container.removeAllViews()

        val source = anyViews ?: return
        val viewPtr = source.viewAt(position)
        if (viewPtr != 0L) {
            val listItem = NativeBindings.waterui_force_as_list_item(viewPtr)
            val contentPtr = listItem.contentPtr
            if (contentPtr == 0L) {
                return
            }

            val contentView = inflateAnyView(context, contentPtr, env, registry)
            holder.container.addView(contentView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    override fun getItemCount(): Int = itemIds.size

    override fun getItemId(position: Int): Long = itemIds[position].toLong()

    override fun close() {
        watcherGuard?.close()
        watcherGuard = null
        anyViews?.close()
    }

    private fun updateIds(newIds: List<Int>) {
        val oldIds = itemIds.toList()
        if (oldIds == newIds) {
            return
        }

        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldIds.size

            override fun getNewListSize(): Int = newIds.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldIds[oldItemPosition] == newIds[newItemPosition]

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldIds[oldItemPosition] == newIds[newItemPosition]
        })

        itemIds = newIds.toMutableList()
        diff.dispatchUpdatesTo(this)
    }
}

/**
 * Register List component with the registry.
 */
internal fun RegistryBuilder.registerWuiList() {
    register({ listTypeId }, listRenderer)
}
