package dev.waterui.android.components

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dev.waterui.android.runtime.EdgeInsetsStruct
import dev.waterui.android.runtime.R
import dev.waterui.android.runtime.dp
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * The view pieces a list row is built from.
 *
 * A row is a `MaterialCardView` wrapping a horizontal layout: the bound
 * content on the left and the editing actions — delete and move — revealed on
 * the right while the list is being edited. A selected row shows Material's
 * checked-card container tint, never a check icon: selection chrome, not a
 * checkbox.
 */
internal fun createListRowViews(context: Context, horizontalMargin: Int): ListItemHolder {
    val verticalMargin = 4f.dp(context).toInt()
    val card = MaterialCardView(
        context,
        null,
        MaterialR.attr.materialCardViewOutlinedStyle
    ).apply {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(horizontalMargin, verticalMargin, horizontalMargin, verticalMargin)
        }
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
        context,
        iconResource = R.drawable.wui_delete,
        descriptionResource = R.string.wui_list_delete_item,
        iconColorAttribute = android.R.attr.colorError
    )
    val moveButton = materialIconButton(
        context,
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

/// The theme's one-line row height the card's content is floored to when the
/// list carries no minimum of its own.
private const val THEME_ROW_MIN_HEIGHT_DP = 48f

/**
 * Applies a row's metrics while it is bound: the insets between the card's
 * edges and the content, and the floor the row's height measures against.
 *
 * `null` insets keep the theme's row insets — none, the content fills the
 * card — while a row that carries its own pads the content inside the card
 * instead; leading and trailing follow text direction, so they land on the
 * start and end edges under RTL. `null` minRowHeightPx keeps the theme's
 * one-line floor; `0` lets the row size to its content plus its insets. The
 * floor covers the insets, the same clamp the self-drawn renderer applies
 * (`content + insets` measured against the floor). The actions column keeps
 * its own chrome — the insets belong to the content alone.
 */
internal fun bindListRowMetrics(
    holder: ListItemHolder,
    insets: EdgeInsetsStruct?,
    minRowHeightPx: Int?,
    context: Context
) {
    val content = holder.content
    if (insets == null) {
        content.setPadding(0, 0, 0, 0)
    } else {
        content.setPaddingRelative(
            insets.leading.dp(context).toInt(),
            insets.top.dp(context).toInt(),
            insets.trailing.dp(context).toInt(),
            insets.bottom.dp(context).toInt()
        )
    }
    content.minimumHeight = minRowHeightPx ?: THEME_ROW_MIN_HEIGHT_DP.dp(context).toInt()
}

/// The three platform flags a selected row carries: Material's checked-card
/// chrome, `state_activated` — AbsListView's choice-mode flag, so
/// state_activated drawables behave the same on a WaterUI row — and
/// `state_selected`, which publishes the row's selected bit to the
/// accessibility node and `state_selected` drawables.
internal fun applyListRowSelectedState(card: MaterialCardView, selected: Boolean) {
    card.isChecked = selected
    card.isActivated = selected
    card.isSelected = selected
}

/**
 * The row's assistive reach: its accessibility node reports `isSelected` —
 * AbsListView's item delegate publishes the flag the same explicit way — and
 * its "select" action performs the same write a tap does. The row id resolves
 * lazily at action time, so a delegate installed once when the holder is
 * created keeps addressing the bound row across rebinds; `null` means the
 * row is unbound (or chrome) and the action is not ours.
 */
internal fun bindListRowAccessibility(
    card: View,
    selection: ListSelection,
    rowId: () -> Int?
) {
    installAccessibilityDelegate(
        card,
        mutate = { info -> info.isSelected = card.isSelected },
        performAction = { action, _ ->
            val id = if (
                selection.isEnabled &&
                action == AccessibilityNodeInfoCompat.ACTION_SELECT
            ) {
                rowId()
            } else {
                null
            }
            if (id != null) {
                selection.activate(id)
                true
            } else {
                false
            }
        }
    )
}

private fun materialIconButton(
    context: Context,
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
