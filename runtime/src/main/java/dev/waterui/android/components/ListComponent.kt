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
import dev.waterui.android.runtime.ReactivePlainText
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

private const val VIEW_TYPE_ROW = 0
private const val VIEW_TYPE_HEADER = 1
private const val VIEW_TYPE_FOOTER = 2

/**
 * One line of the list.
 *
 * Section chrome is a line of its own, never part of a row: a header
 * introduces the cards that follow it, so it has to sit outside them — the
 * same split the Apple backend makes with supplementary views and the
 * self-drawn renderer makes by keeping the chrome out of the row's slot.
 *
 * The stable id tags the owning row so a header, its rows, and the footer that
 * closes the section all move and diff together.
 */
private sealed class ListEntry(val rowId: Int, tag: Long) {
    val stableId: Long = (rowId.toLong() shl 2) or tag
}

private class RowEntry(rowId: Int) : ListEntry(rowId, 0L)

/** The header of the section row [rowId] opens. */
private class HeaderEntry(rowId: Int, val text: ReactivePlainText) : ListEntry(rowId, 1L)

/**
 * The footer of the section row [rowId] opened.
 *
 * It is keyed by the row that *opened* the section rather than the last row of
 * it, so the footer keeps its identity while rows are inserted or removed
 * inside the section.
 */
private class FooterEntry(rowId: Int, val text: ReactivePlainText) : ListEntry(rowId, 2L)

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
    selectedPtr: Long,
    hasSection: Boolean,
    sectionLabelPtr: Long,
    sectionFooterPtr: Long,
    private val context: Context,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry
) : Closeable {
    private var contentPtr = contentPtr
    private var contentView: View? = null
    private var contentDisposed = false
    private val deletable = WuiComputed.bool(deletablePtr)
    private val selected = WuiComputed.bool(selectedPtr)

    /** Whether this row opens a section, even one that draws no chrome. */
    val opensSection = hasSection

    /**
     * The section's header and footer as live text. They stay attached to this
     * model for its lifetime; the holder that draws a given piece of chrome
     * subscribes to it while bound and lets go on recycle.
     */
    val sectionLabel = sectionLabelPtr.takeIf { it != 0L }?.let(::ReactivePlainText)
    val sectionFooter = sectionFooterPtr.takeIf { it != 0L }?.let(::ReactivePlainText)
    var isDeletable = false
        private set
    var isSelected = false
        private set

    // The bound holder's hook; selection can change while the row is on screen.
    var onSelectedChanged: ((Boolean) -> Unit)? = null

    init {
        deletable.observe { value ->
            isDeletable = value
        }
        selected.observe { value ->
            isSelected = value
            onSelectedChanged?.invoke(value)
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
        onSelectedChanged = null
        deletable.close()
        selected.close()
        sectionLabel?.close()
        sectionFooter?.close()
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
    val content: FrameLayout,
    val actions: LinearLayout,
    val deleteButton: MaterialButton,
    val moveButton: MaterialButton
) : RecyclerView.ViewHolder(card) {
    var model: ListItemModel? = null
    var ownsModel: Boolean = false

    /// The card's unselected container, captured before selection repaints it.
    var defaultContainerColor: android.content.res.ColorStateList? = null
}

/// A standalone section header or footer, drawn between card groups.
private class ListChromeHolder(val label: TextView) : RecyclerView.ViewHolder(label) {
    /// The chrome text this holder is subscribed to while bound.
    var attached: ReactivePlainText? = null
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
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), Closeable, PopupTextProvider {
    private data class ActiveMove(
        val itemId: Int,
        val originalRow: Int
    )

    private val editing = WuiComputed.bool(editingPtr)
    private val mutedForeground = ThemeBridge.mutedForeground(env)
    private val selectionContainer = ThemeBridge.selectionContainer(env)
    private val motion = MaterialListMotion(context)
    private val source = NativeAnyViews(contentsPtr)
    private val sourceWatcher: WatcherGuard
    private val sectionModels = linkedMapOf<Int, ListItemModel>()
    private val visibleFlatModels = mutableSetOf<ListItemModel>()
    private var itemIds = IntArray(0)

    /// Every line the list draws: the rows plus the section chrome between
    /// them, in display order.
    private var entries = emptyList<ListEntry>()

    /// Adapter position of each row, indexed by its position in [itemIds].
    /// Strictly increasing, so a position maps back to a row by binary search.
    private var rowPositions = IntArray(0)
    private var touchHelper: ItemTouchHelper? = null
    private var activeMove: ActiveMove? = null

    /// The inset the cards carry, which the section chrome aligns its text to.
    private val cardHorizontalMargin = 12f.dp(context).toInt()

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
            selectedPtr = item.selectedPtr,
            hasSection = item.hasSection,
            sectionLabelPtr = item.sectionLabelPtr,
            sectionFooterPtr = item.sectionFooterPtr,
            context = context,
            env = env,
            registry = registry
        )
    }
    private var isEditing = false
    private var sectionTextColor = 0
    private var selectedContainerColor = 0

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
        selectionContainer.observe { color ->
            selectedContainerColor = color.toColorInt()
            notifyItemRangeChanged(0, itemCount, THEME_PAYLOAD)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            VIEW_TYPE_ROW -> createRowHolder()
            VIEW_TYPE_HEADER -> ListChromeHolder(
                createChromeLabel(
                    textAppearance = MaterialR.style.TextAppearance_Material3_TitleSmall,
                    topPadding = 16f,
                    bottomPadding = 8f
                )
            )
            VIEW_TYPE_FOOTER -> ListChromeHolder(
                createChromeLabel(
                    textAppearance = MaterialR.style.TextAppearance_Material3_BodySmall,
                    topPadding = 8f,
                    bottomPadding = 16f
                )
            )
            else -> error("WaterUI List has no view type $viewType")
        }

    /**
     * A standalone section label.
     *
     * Its horizontal padding is the cards' own inset, so the chrome reads as
     * the title of the group below it rather than as a stray indented line.
     */
    private fun createChromeLabel(
        textAppearance: Int,
        topPadding: Float,
        bottomPadding: Float
    ): TextView = TextView(context).apply {
        setTextAppearance(textAppearance)
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setPadding(
            cardHorizontalMargin,
            topPadding.dp(context).toInt(),
            cardHorizontalMargin,
            bottomPadding.dp(context).toInt()
        )
    }

    private fun createRowHolder(): ListItemHolder {
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
                setMargins(
                    cardHorizontalMargin,
                    verticalMargin,
                    cardHorizontalMargin,
                    verticalMargin
                )
            }
            // A selected row shows Material's checked-card container tint, not
            // a check icon: selection chrome, not a checkbox.
            isCheckable = true
            checkedIcon = null
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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
        val content = FrameLayout(context).apply {
            minimumHeight = 48f.dp(context).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
        }
        row.addView(content)
        row.addView(actions)
        card.addView(row)
        return ListItemHolder(card, content, actions, deleteButton, moveButton)
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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val entry = entries[position]) {
            is RowEntry -> bindRow(asRowHolder(holder), entry, position)
            is HeaderEntry -> bindChrome(asChromeHolder(holder), entry.text)
            is FooterEntry -> bindChrome(asChromeHolder(holder), entry.text)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        if (payloads.contains(THEME_PAYLOAD)) bindTheme(holder)
        if (payloads.contains(EDITING_PAYLOAD) && holder is ListItemHolder) {
            bindEditing(holder, animate = true)
        }
    }

    private fun bindRow(holder: ListItemHolder, entry: RowEntry, position: Int) {
        releaseHolderModel(holder)
        val model = if (usesSections) {
            checkNotNull(sectionModels[entry.rowId]) {
                "sectioned List row ${entry.rowId} was not materialized"
            }
        } else {
            materialize(rowAt(position)).also(visibleFlatModels::add)
        }
        holder.model = model
        holder.ownsModel = !usesSections
        applySelection(holder, model.isSelected)
        model.onSelectedChanged = { value -> applySelection(holder, value) }
        bindTheme(holder)
        bindEditing(holder, animate = false)
        holder.content.removeAllViews()
        holder.content.addView(
            model.takeContentView(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun bindChrome(holder: ListChromeHolder, text: ReactivePlainText) {
        holder.attached?.detach()
        holder.attached = text
        text.attach { value -> holder.label.text = value }
        bindTheme(holder)
    }

    private fun asRowHolder(holder: RecyclerView.ViewHolder): ListItemHolder =
        holder as? ListItemHolder ?: error("WaterUI List row needs a ListItemHolder, got $holder")

    private fun asChromeHolder(holder: RecyclerView.ViewHolder): ListChromeHolder =
        holder as? ListChromeHolder
            ?: error("WaterUI List section chrome needs a ListChromeHolder, got $holder")

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is ListItemHolder -> {
                holder.content.removeAllViews()
                releaseHolderModel(holder)
            }
            is ListChromeHolder -> {
                holder.attached?.detach()
                holder.attached = null
            }
            else -> error("WaterUI List recycled an unknown holder $holder")
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = entries.size

    override fun getItemId(position: Int): Long = entries[position].stableId

    override fun getItemViewType(position: Int): Int = when (entries[position]) {
        is RowEntry -> VIEW_TYPE_ROW
        is HeaderEntry -> VIEW_TYPE_HEADER
        is FooterEntry -> VIEW_TYPE_FOOTER
    }

    /** How many rows — section chrome does not count — end at `position`. */
    private fun rowsThrough(position: Int): Int {
        val found = rowPositions.binarySearch(position)
        return if (found >= 0) found + 1 else -found - 1
    }

    /** The row index a row position addresses, rejecting section chrome. */
    private fun rowAt(position: Int): Int {
        val row = rowPositions.binarySearch(position)
        check(row >= 0) { "List position $position is section chrome, not a row" }
        return row
    }

    override fun getPopupText(view: View, position: Int): CharSequence =
        "${rowsThrough(position)} / ${itemIds.size}"

    /**
     * The position to scroll to in order to bring row [row] into view.
     *
     * A row that opens a section is introduced by its header, so the header is
     * what has to come to rest at the top — landing on the row alone would push
     * its own title off screen.
     */
    fun scrollPositionForRow(row: Int): Int {
        check(row in itemIds.indices) {
            "List scroll target $row exceeds collection length ${itemIds.size}"
        }
        val position = rowPositions[row]
        return if (position > 0 && entries[position - 1] is HeaderEntry) position - 1 else position
    }

    val rowCount: Int get() = itemIds.size

    fun movementFlags(holder: RecyclerView.ViewHolder): Int {
        // Section chrome is not a row: it neither drags nor swipes away.
        val item = (holder as? ListItemHolder)?.model
            ?: return ItemTouchHelper.Callback.makeMovementFlags(0, 0)
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
        val position = holder.bindingAdapterPosition
        check(position in entries.indices) {
            "List move started from invalid adapter position $position"
        }
        beginMoveAt(rowAt(position))
    }

    /** Reorders the rows behind the two adapter positions a drag connects. */
    fun previewMove(from: Int, to: Int): Boolean {
        if (from == to) return false
        return previewMoveRows(rowAt(from), rowAt(to))
    }

    private fun previewMoveRows(from: Int, to: Int): Boolean {
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
        applyEntries()
        return true
    }

    fun commitMove() {
        val movement = activeMove ?: return
        activeMove = null
        val finalRow = itemIds.indexOf(movement.itemId)
        check(finalRow >= 0) {
            "List item ${movement.itemId} disappeared during an active move"
        }
        if (movement.originalRow == finalRow) return
        NativeBindings.waterui_call_move_action(
            onMovePtr,
            env.raw(),
            movement.originalRow,
            finalRow
        )
    }

    fun moveOnce(from: Int, to: Int) {
        check(from in itemIds.indices) { "List move source $from is out of bounds" }
        check(to in itemIds.indices) { "List move destination $to is out of bounds" }
        beginMoveAt(from)
        val moved = previewMoveRows(from, to)
        check(moved) { "List move from $from to $to was rejected" }
        commitMove()
    }

    private fun beginMoveAt(row: Int) {
        check(activeMove == null) { "List already has an active move interaction" }
        activeMove = ActiveMove(itemIds[row], row)
    }

    fun delete(position: Int) {
        val row = rowAt(position)
        val id = itemIds[row]
        NativeBindings.waterui_call_index_action(onDeletePtr, env.raw(), row)
        val currentRow = itemIds.indexOf(id)
        if (currentRow >= 0) notifyItemChanged(rowPositions[currentRow])
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
        entries = emptyList()
        rowPositions = IntArray(0)
        editing.close()
        mutedForeground.close()
        selectionContainer.close()
        if (onDeletePtr != 0L) NativeBindings.waterui_drop_index_action(onDeletePtr)
        if (onMovePtr != 0L) NativeBindings.waterui_drop_move_action(onMovePtr)
    }

    private fun bindTheme(holder: RecyclerView.ViewHolder) {
        when (holder) {
            // Card container/stroke stay on the M3 outlined-card style defaults;
            // repainting them with Surface/Border flattened the elevation tint
            // and darkened the outline versus a Compose Card.
            is ListItemHolder -> holder.model?.let { applySelection(holder, it.isSelected) }
            is ListChromeHolder -> holder.label.setTextColor(sectionTextColor)
            else -> error("WaterUI List cannot theme an unknown holder $holder")
        }
    }

    /// Paints a row's selection chrome.
    ///
    /// Selected rows arrive re-themed to the `SelectionForeground` slot, which
    /// this backend installs from Material's `colorOnSecondaryContainer`. The
    /// fill is that slot's partner, `SelectionContainer` — Material's
    /// `colorSecondaryContainer` — so the row is the canonical M3 selected pair
    /// rather than accent-on-accent.
    private fun applySelection(holder: ListItemHolder, selected: Boolean) {
        holder.card.isChecked = selected
        if (holder.defaultContainerColor == null) {
            holder.defaultContainerColor = holder.card.cardBackgroundColor
        }
        if (selected) {
            holder.card.setCardBackgroundColor(selectedContainerColor)
        } else {
            holder.defaultContainerColor?.let(holder.card::setCardBackgroundColor)
        }
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
                    check(position in entries.indices) {
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
                    val from = rowAt(holder.bindingAdapterPosition)
                    if (from < itemIds.lastIndex) moveOnce(from, from + 1)
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

    private fun releaseHolderModel(holder: ListItemHolder) {
        val model = holder.model ?: return
        model.onSelectedChanged = null
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
        applyEntries()
    }

    /**
     * Rebuilds the displayed lines from the current row order and tells the
     * RecyclerView what changed.
     */
    private fun applyEntries() {
        val previous = entries
        val next = computeEntries()
        entries = next
        rowPositions = IntArray(itemIds.size)
        var row = 0
        next.forEachIndexed { index, entry ->
            if (entry is RowEntry) {
                rowPositions[row] = index
                row += 1
            }
        }
        check(row == itemIds.size) {
            "List built $row row lines for ${itemIds.size} rows"
        }
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
                previous[oldItemPosition].stableId == next[newItemPosition].stableId
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                previous[oldItemPosition].stableId == next[newItemPosition].stableId
        })
        diff.dispatchUpdatesTo(this)
    }

    private fun singleRemovalIndex(previous: List<ListEntry>, next: List<ListEntry>): Int? {
        if (previous.size != next.size + 1) return null
        var removedIndex = 0
        while (
            removedIndex < next.size &&
            previous[removedIndex].stableId == next[removedIndex].stableId
        ) {
            removedIndex += 1
        }
        for (index in removedIndex until next.size) {
            if (previous[index + 1].stableId != next[index].stableId) return null
        }
        return removedIndex
    }

    /**
     * The lines the list draws, derived from the section markers the rows carry.
     *
     * A marker opens a section on the row that carries it, so that row's header
     * precedes it. The footer closes the section on the line *after* its last
     * row — which is the line before the next marker — so it is emitted when
     * the following section opens, or once the rows run out.
     */
    private fun computeEntries(): List<ListEntry> {
        if (!usesSections) return itemIds.map(::RowEntry)
        val lines = ArrayList<ListEntry>(itemIds.size)
        var openId = 0
        var openFooter: ReactivePlainText? = null
        itemIds.forEach { id ->
            val item = checkNotNull(sectionModels[id]) {
                "sectioned List row $id was not materialized"
            }
            if (item.opensSection) {
                openFooter?.let { lines.add(FooterEntry(openId, it)) }
                item.sectionLabel?.let { lines.add(HeaderEntry(id, it)) }
                openId = id
                openFooter = item.sectionFooter
            }
            lines.add(RowEntry(id))
        }
        openFooter?.let { lines.add(FooterEntry(openId, it)) }
        return lines
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

    /// Section chrome is not a drop target: a row only ever trades places with
    /// another row.
    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = target is ListItemHolder

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
            check(target in 0 until adapter.rowCount) {
                "List scroll target $target exceeds collection length ${adapter.rowCount}"
            }
            recyclerView.animateToPosition(adapter.scrollPositionForRow(target), motion)
        }
        recyclerView.disposeWith(targetIndex)
        recyclerView.disposeWith(generation)
    }
    recyclerView
}

internal fun RegistryBuilder.registerWuiList() {
    register({ listTypeId }, listRenderer)
}
