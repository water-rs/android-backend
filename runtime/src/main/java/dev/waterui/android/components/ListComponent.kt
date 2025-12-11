package dev.waterui.android.components

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.TAG_STRETCH_AXIS

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
        // List is greedy - fills available space
        setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH)
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
                items.add(ListItemData(id, listItem.contentPtr))
            }
        }
    }

    recyclerView.adapter = WuiListAdapter(context, items, env, registry)

    recyclerView.disposeWith {
        if (contentsPtr != 0L) {
            NativeBindings.waterui_drop_any_views(contentsPtr)
        }
    }

    recyclerView
}

/**
 * Data for a single list item.
 */
private data class ListItemData(
    val id: Int,
    val contentPtr: Long
)

/**
 * RecyclerView adapter for WaterUI List.
 */
private class WuiListAdapter(
    private val context: Context,
    private val items: List<ListItemData>,
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
