package dev.waterui.android.components

import android.content.res.ColorStateList
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.doOnLayout
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.motion.MotionUtils
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import dev.waterui.android.runtime.R
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
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupTextProvider
import com.google.android.material.R as MaterialR

private val listTypeId: WuiTypeId by lazy { NativeBindings.waterui_list_id().toTypeId() }
private val listItemTypeId: WuiTypeId by lazy { NativeBindings.waterui_list_item_id().toTypeId() }
private const val THEME_PAYLOAD = "waterui.list.theme"
private const val EDITING_PAYLOAD = "waterui.list.editing"

class ListRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            viewportMeasureSpec(widthMeasureSpec),
            viewportMeasureSpec(heightMeasureSpec)
        )
    }

    private fun viewportMeasureSpec(measureSpec: Int): Int {
        return if (MeasureSpec.getMode(measureSpec) == MeasureSpec.UNSPECIFIED) {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
        } else {
            measureSpec
        }
    }
}

private class MaterialListMotion(context: Context) {
    val emphasizedInterpolator: Interpolator = materialInterpolator(
        context,
        MaterialR.attr.motionEasingEmphasizedInterpolator,
        android.R.interpolator.fast_out_slow_in
    )
    val emphasizedDecelerateInterpolator: Interpolator = materialInterpolator(
        context,
        MaterialR.attr.motionEasingEmphasizedDecelerateInterpolator,
        android.R.interpolator.linear_out_slow_in
    )
    val emphasizedAccelerateInterpolator: Interpolator = materialInterpolator(
        context,
        MaterialR.attr.motionEasingEmphasizedAccelerateInterpolator,
        android.R.interpolator.fast_out_linear_in
    )
    val scrollDuration: Int = MotionUtils.resolveThemeDuration(
        context,
        MaterialR.attr.motionDurationLong2,
        context.resources.getInteger(android.R.integer.config_longAnimTime)
    )
    val controlDuration: Int = MotionUtils.resolveThemeDuration(
        context,
        MaterialR.attr.motionDurationShort4,
        context.resources.getInteger(android.R.integer.config_shortAnimTime)
    )

    private fun materialInterpolator(
        context: Context,
        attribute: Int,
        fallbackResource: Int
    ): Interpolator {
        val resolved = MotionUtils.resolveThemeInterpolator(
            context,
            attribute,
            AnimationUtils.loadInterpolator(context, fallbackResource)
        )
        return resolved as? Interpolator
            ?: error("Material motion attribute $attribute did not resolve to an Interpolator")
    }
}

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
    val footer: TextView,
    val actions: LinearLayout,
    val deleteButton: MaterialButton,
    val moveButton: MaterialButton
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
    private val registry: RenderRegistry
) : RecyclerView.Adapter<ListItemHolder>(), Closeable, PopupTextProvider {
    private data class ActiveMove(
        val itemId: Int,
        val originalPosition: Int
    )

    private val editing = WuiComputed.bool(editingPtr)
    private val mutedForeground = ThemeBridge.mutedForeground(env)
    private val motion = MaterialListMotion(context)
    private val source = NativeAnyViews(contentsPtr)
    private val sourceWatcher: WatcherGuard
    private val sectionModels = linkedMapOf<Int, ListItemModel>()
    private val visibleFlatModels = mutableSetOf<ListItemModel>()
    private var itemIds = IntArray(0)
    private var footerAfter = emptyMap<Int, String>()
    private var touchHelper: ItemTouchHelper? = null
    private var activeMove: ActiveMove? = null

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
    private var sectionTextColor = 0

    init {
        setHasStableIds(true)
        sourceWatcher = source.watch(::updateIds)
        updateIds(source.ids())
        editing.observe { value ->
            if (isEditing == value) return@observe
            isEditing = value
            notifyItemRangeChanged(0, itemCount, EDITING_PAYLOAD)
        }
        mutedForeground.observe { color ->
            sectionTextColor = color.toColorInt()
            notifyItemRangeChanged(0, itemCount, THEME_PAYLOAD)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListItemHolder {
        val horizontalMargin = 12f.dp(context).toInt()
        val verticalMargin = 4f.dp(context).toInt()
        val card = MaterialCardView(
            context,
            null,
            com.google.android.material.R.attr.materialCardViewOutlinedStyle
        ).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin)
            }
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }
        val deleteButton = materialIconButton(
            iconResource = R.drawable.wui_delete,
            descriptionResource = R.string.wui_list_delete_item,
            iconColorAttribute = android.R.attr.colorError
        )
        val moveButton = materialIconButton(
            iconResource = R.drawable.wui_drag_handle,
            descriptionResource = R.string.wui_list_move_item,
            iconColorAttribute = MaterialR.attr.colorOnSurfaceVariant
        )
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isVisible = false
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                marginEnd = 8f.dp(context).toInt()
            }
            addView(deleteButton)
            addView(moveButton)
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
        row.addView(column)
        row.addView(actions)
        card.addView(row)
        return ListItemHolder(card, header, content, footer, actions, deleteButton, moveButton)
    }

    private fun materialIconButton(
        iconResource: Int,
        descriptionResource: Int,
        iconColorAttribute: Int
    ): MaterialButton = MaterialButton(
        context,
        null,
        MaterialR.attr.materialIconButtonStyle
    ).apply {
        icon = checkNotNull(AppCompatResources.getDrawable(context, iconResource)) {
            "WaterUI List icon resource $iconResource is missing"
        }
        iconTint = ColorStateList.valueOf(
            MaterialColors.getColor(
                context,
                iconColorAttribute,
                "WaterUI List requires Material 3 color attribute $iconColorAttribute"
            )
        )
        contentDescription = context.getString(descriptionResource)
        iconPadding = 0
        insetTop = 0
        insetBottom = 0
        minWidth = 48f.dp(context).toInt()
        minimumWidth = minWidth
        minHeight = 48f.dp(context).toInt()
        minimumHeight = minHeight
        layoutParams = LinearLayout.LayoutParams(minWidth, minHeight)
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
        bindEditing(holder, animate = false)
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
            if (payloads.contains(THEME_PAYLOAD)) bindTheme(holder)
            if (payloads.contains(EDITING_PAYLOAD)) bindEditing(holder, animate = true)
        }
    }

    override fun onViewRecycled(holder: ListItemHolder) {
        holder.content.removeAllViews()
        releaseHolderModel(holder)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = itemIds.size

    override fun getItemId(position: Int): Long = itemIds[position].toLong()

    override fun getPopupText(view: View, position: Int): CharSequence =
        "${position + 1} / $itemCount"

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

    fun attachTouchHelper(helper: ItemTouchHelper) {
        check(touchHelper == null) { "List ItemTouchHelper was already attached" }
        touchHelper = helper
    }

    fun beginMove(holder: RecyclerView.ViewHolder) {
        check(activeMove == null) { "List already has an active move interaction" }
        val position = holder.bindingAdapterPosition
        check(position in itemIds.indices) {
            "List move started from invalid adapter position $position"
        }
        activeMove = ActiveMove(itemIds[position], position)
    }

    fun previewMove(from: Int, to: Int): Boolean {
        if (onMovePtr == 0L || from == to) return false
        check(activeMove != null) { "List move preview requires an active interaction" }
        val reordered = itemIds.copyOf()
        val moved = reordered[from]
        if (from < to) {
            reordered.copyInto(reordered, from, from + 1, to + 1)
        } else {
            reordered.copyInto(reordered, to + 1, to, from)
        }
        reordered[to] = moved
        itemIds = reordered
        footerAfter = computeFooters()
        notifyItemMoved(from, to)
        return true
    }

    fun commitMove() {
        val movement = activeMove ?: return
        activeMove = null
        val finalPosition = itemIds.indexOf(movement.itemId)
        check(finalPosition >= 0) {
            "List item ${movement.itemId} disappeared during an active move"
        }
        if (movement.originalPosition == finalPosition) return
        NativeBindings.waterui_call_move_action(
            onMovePtr,
            env.raw(),
            movement.originalPosition,
            finalPosition
        )
    }

    fun moveOnce(from: Int, to: Int) {
        check(from in itemIds.indices) { "List move source $from is out of bounds" }
        check(to in itemIds.indices) { "List move destination $to is out of bounds" }
        beginMoveAt(from)
        val moved = previewMove(from, to)
        check(moved) { "List move from $from to $to was rejected" }
        commitMove()
    }

    private fun beginMoveAt(position: Int) {
        check(activeMove == null) { "List already has an active move interaction" }
        activeMove = ActiveMove(itemIds[position], position)
    }

    fun delete(position: Int) {
        val id = itemIds[position]
        NativeBindings.waterui_call_index_action(onDeletePtr, env.raw(), position)
        val currentPosition = itemIds.indexOf(id)
        if (currentPosition >= 0) notifyItemChanged(currentPosition)
    }

    override fun close() {
        activeMove = null
        sourceWatcher.close()
        source.close()
        sectionModels.values.forEach(ListItemModel::close)
        sectionModels.clear()
        visibleFlatModels.toList().forEach(ListItemModel::close)
        visibleFlatModels.clear()
        itemIds = IntArray(0)
        footerAfter = emptyMap()
        editing.close()
        mutedForeground.close()
        if (onDeletePtr != 0L) NativeBindings.waterui_drop_index_action(onDeletePtr)
        if (onMovePtr != 0L) NativeBindings.waterui_drop_move_action(onMovePtr)
    }

    private fun bindTheme(holder: ListItemHolder) {
        // Card container/stroke stay on the M3 outlined-card style defaults;
        // repainting them with Surface/Border flattened the elevation tint
        // and darkened the outline versus a Compose Card.
        holder.header.setTextColor(sectionTextColor)
        holder.footer.setTextColor(sectionTextColor)
    }

    @Suppress("ClickableViewAccessibility")
    private fun bindEditing(holder: ListItemHolder, animate: Boolean) {
        val deletable = isEditing && onDeletePtr != 0L && holder.model?.isDeletable == true
        val movable = isEditing && onMovePtr != 0L
        holder.deleteButton.isVisible = deletable
        holder.moveButton.isVisible = movable
        setActionsVisible(holder, deletable || movable, animate)
        holder.deleteButton.setOnClickListener(
            if (deletable) {
                View.OnClickListener {
                    val position = holder.bindingAdapterPosition
                    check(position in itemIds.indices) {
                        "List delete requested from invalid adapter position $position"
                    }
                    delete(position)
                }
            } else {
                null
            }
        )
        holder.moveButton.setOnTouchListener(
            if (movable) {
                View.OnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        checkNotNull(touchHelper) {
                            "List move interaction requires an attached ItemTouchHelper"
                        }.startDrag(holder)
                    }
                    false
                }
            } else {
                null
            }
        )
        holder.moveButton.setOnClickListener(
            if (movable) {
                View.OnClickListener {
                    val from = holder.bindingAdapterPosition
                    if (from in 0 until itemIds.lastIndex) {
                        moveOnce(from, from + 1)
                    }
                }
            } else {
                null
            }
        )
    }

    private fun setActionsVisible(holder: ListItemHolder, visible: Boolean, animate: Boolean) {
        val actions = holder.actions
        actions.animate().cancel()
        if (!animate) {
            actions.isVisible = visible
            actions.alpha = if (visible) 1f else 0f
            actions.scaleX = 1f
            actions.scaleY = 1f
            return
        }
        if (visible) {
            actions.isVisible = true
            actions.alpha = 0f
            actions.scaleX = 0.8f
            actions.scaleY = 0.8f
            actions.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(motion.controlDuration.toLong())
                .setInterpolator(motion.emphasizedDecelerateInterpolator)
                .start()
        } else if (actions.isVisible) {
            actions.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(motion.controlDuration.toLong())
                .setInterpolator(motion.emphasizedAccelerateInterpolator)
                .withEndAction {
                    actions.isVisible = false
                    actions.scaleX = 1f
                    actions.scaleY = 1f
                }
                .start()
        }
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
        val next = nextIds
        if (previous.contentEquals(next)) return
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
        if (previous.isEmpty()) {
            notifyItemRangeInserted(0, next.size)
            return
        }
        singleRemovalIndex(previous, next)?.let { removedIndex ->
            notifyItemRemoved(removedIndex)
            return
        }

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

    private fun singleRemovalIndex(previous: IntArray, next: IntArray): Int? {
        if (previous.size != next.size + 1) return null
        var removedIndex = 0
        while (
            removedIndex < next.size &&
            previous[removedIndex] == next[removedIndex]
        ) {
            removedIndex += 1
        }
        for (index in removedIndex until next.size) {
            if (previous[index + 1] != next[index]) return null
        }
        return removedIndex
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
    context: Context,
    private val adapter: WuiListAdapter
) : ItemTouchHelper.Callback() {
    private val motion = MaterialListMotion(context)
    private val deleteBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(
            context,
            MaterialR.attr.colorErrorContainer,
            "WaterUI List requires Material 3 colorErrorContainer"
        )
    }
    private val deleteIcon = checkNotNull(
        AppCompatResources.getDrawable(context, R.drawable.wui_delete)
    ) {
        "WaterUI List delete icon is missing"
    }.mutate().apply {
        setTint(
            MaterialColors.getColor(
                context,
                MaterialR.attr.colorOnErrorContainer,
                "WaterUI List requires Material 3 colorOnErrorContainer"
            )
        )
    }
    private val cornerRadius = 12f.dp(context)
    private val iconSize = 24f.dp(context).toInt()
    private val iconMargin = 20f.dp(context).toInt()

    override fun isLongPressDragEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int = adapter.movementFlags(viewHolder)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = adapter.previewMove(
        viewHolder.bindingAdapterPosition,
        target.bindingAdapterPosition
    )

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        adapter.delete(viewHolder.bindingAdapterPosition)
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        super.onSelectedChanged(viewHolder, actionState)
        if (actionState != ItemTouchHelper.ACTION_STATE_DRAG) return
        val holder = checkNotNull(viewHolder) {
            "List drag state requires a selected ViewHolder"
        }
        adapter.beginMove(holder)
        holder.itemView.animate()
            .translationZ(8f.dp(holder.itemView.context))
            .setDuration(motion.controlDuration.toLong())
            .setInterpolator(motion.emphasizedDecelerateInterpolator)
            .start()
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        super.clearView(recyclerView, viewHolder)
        viewHolder.itemView.animate()
            .translationZ(0f)
            .setDuration(motion.controlDuration.toLong())
            .setInterpolator(motion.emphasizedDecelerateInterpolator)
            .start()
        adapter.commitMove()
    }

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
            drawDeleteAffordance(canvas, viewHolder.itemView, dX)
        }
        super.onChildDraw(
            canvas,
            recyclerView,
            viewHolder,
            dX,
            dY,
            actionState,
            isCurrentlyActive
        )
    }

    private fun drawDeleteAffordance(canvas: Canvas, item: View, dX: Float) {
        val reveal = kotlin.math.abs(dX)
        val bounds = if (dX > 0f) {
            RectF(
                item.left.toFloat(),
                item.top.toFloat(),
                item.left + reveal,
                item.bottom.toFloat()
            )
        } else {
            RectF(
                item.right - reveal,
                item.top.toFloat(),
                item.right.toFloat(),
                item.bottom.toFloat()
            )
        }
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, deleteBackground)

        val iconCenterX = if (dX > 0f) {
            item.left + iconMargin + iconSize / 2
        } else {
            item.right - iconMargin - iconSize / 2
        }
        val iconCenterY = item.top + item.height / 2
        deleteIcon.bounds = android.graphics.Rect(
            iconCenterX - iconSize / 2,
            iconCenterY - iconSize / 2,
            iconCenterX + iconSize / 2,
            iconCenterY + iconSize / 2
        )
        deleteIcon.alpha = (
            reveal / (iconMargin * 2 + iconSize).toFloat() * 255f
        ).toInt().coerceIn(0, 255)
        deleteIcon.draw(canvas)
    }
}

private fun ListRecyclerView.animateToPosition(
    target: Int,
    motion: MaterialListMotion
) {
    stopScroll()
    doOnLayout {
        val layout = layoutManager as? LinearLayoutManager
            ?: error("WaterUI List requires LinearLayoutManager")
        val targetView = layout.findViewByPosition(target)
        if (targetView != null) {
            val distance = targetView.top - paddingTop
            if (distance != 0) {
                smoothScrollBy(
                    0,
                    distance,
                    motion.emphasizedInterpolator,
                    motion.scrollDuration
                )
            }
            return@doOnLayout
        }

        val visibleBounds = Rect()
        check(getLocalVisibleRect(visibleBounds)) {
            "WaterUI List cannot animate before it has a visible viewport"
        }
        val viewport = visibleBounds.height().coerceAtLeast(1)
        val lastVisible = layout.findLastVisibleItemPosition()
        val scrollingForward = lastVisible == RecyclerView.NO_POSITION || target > lastVisible
        layout.scrollToPositionWithOffset(
            target,
            if (scrollingForward) viewport else -viewport
        )
        doOnLayout {
            smoothScrollBy(
                0,
                if (scrollingForward) viewport else -viewport,
                motion.emphasizedInterpolator,
                motion.scrollDuration
            )
        }
    }
}

private fun styleFastScrollerPopup(context: Context, popup: TextView) {
    val horizontalPadding = 16f.dp(context).toInt()
    val verticalPadding = 8f.dp(context).toInt()
    val shape = ShapeAppearanceModel.builder()
        .setAllCornerSizes(16f.dp(context))
        .build()
    popup.background = MaterialShapeDrawable(shape).apply {
        fillColor = ColorStateList.valueOf(
            MaterialColors.getColor(
                context,
                MaterialR.attr.colorPrimaryContainer,
                "WaterUI List requires Material 3 colorPrimaryContainer"
            )
        )
        elevation = 3f.dp(context)
    }
    popup.setTextAppearance(MaterialR.style.TextAppearance_Material3_TitleMedium)
    popup.setTextColor(
        MaterialColors.getColor(
            context,
            MaterialR.attr.colorOnPrimaryContainer,
            "WaterUI List requires Material 3 colorOnPrimaryContainer"
        )
    )
    popup.gravity = Gravity.CENTER
    popup.maxLines = 1
    popup.setPadding(
        horizontalPadding,
        verticalPadding,
        horizontalPadding,
        verticalPadding
    )
    popup.elevation = 3f.dp(context)
    val params = popup.layoutParams as? FrameLayout.LayoutParams
        ?: error("WaterUI List fast-scroller popup requires FrameLayout.LayoutParams")
    params.marginEnd = 12f.dp(context).toInt()
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
    val recyclerView = ListRecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        this.adapter = adapter
        val touchHelper = ItemTouchHelper(ListTouchCallback(context, adapter))
        adapter.attachTouchHelper(touchHelper)
        touchHelper.attachToRecyclerView(this)
        val track = checkNotNull(
            AppCompatResources.getDrawable(
                context,
                R.drawable.wui_list_scrollbar_vertical_track
            )
        ) {
            "WaterUI List scrollbar track is missing"
        }
        val thumb = checkNotNull(
            AppCompatResources.getDrawable(
                context,
                R.drawable.wui_list_scrollbar_vertical_thumb
            )
        ) {
            "WaterUI List scrollbar thumb is missing"
        }
        FastScrollerBuilder(this)
            .setTrackDrawable(track)
            .setThumbDrawable(thumb)
            .setPopupTextProvider(adapter)
            .setPopupStyle { popup -> styleFastScrollerPopup(context, popup) }
            .build()
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
        val motion = MaterialListMotion(context)
        val targetIndex = WuiComputed.int(struct.targetIndexPtr)
        val generation = WuiComputed.int(struct.scrollGenerationPtr)
        var target = 0
        targetIndex.observe { target = it }
        generation.observe { request ->
            if (request == 0) return@observe
            check(target in 0 until adapter.itemCount) {
                "List scroll target $target exceeds collection length ${adapter.itemCount}"
            }
            recyclerView.animateToPosition(target, motion)
        }
        recyclerView.disposeWith(targetIndex)
        recyclerView.disposeWith(generation)
    }
    recyclerView
}

internal fun RegistryBuilder.registerWuiList() {
    register({ listTypeId }, listRenderer)
}
