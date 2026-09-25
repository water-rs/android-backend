package dev.waterui.android.components

import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.drawToBitmap
import androidx.core.view.get
import androidx.core.view.size
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import dev.waterui.android.reactive.WuiComputed
import java.io.Closeable
import kotlin.math.roundToInt
import com.google.android.material.R as MaterialR

/**
 * A `Computed<i32>` stream of dismiss requests: every change closes the open
 * menu and its accessory. Implemented over the runtime's computed-watcher
 * bridge in production; faked in tests.
 */
internal interface DismissSignal {
    fun watch(onChange: () -> Unit)
}

/** A `WuiComputed<i32>` owned for the lifetime of one context menu. */
internal class WuiDismissRequests(ptr: Long) : DismissSignal, Closeable {
    private val computed = WuiComputed.int(ptr)

    override fun watch(onChange: () -> Unit) {
        computed.observe { onChange() }
    }

    override fun close() = computed.close()
}

/** The menu surface an open context-menu presentation talks back to. */
internal interface ContextMenuSource {
    val isEmpty: Boolean
    fun bind(menu: Menu, context: Context)
    fun unbind()
    fun onMenuItemSelected(itemId: Int): Boolean

    /** Invoked after the bound [Menu] was re-populated from Rust. */
    var onRebuilt: (() -> Unit)?
}

private const val SCRIM_COLOR = 0x59000000.toInt()
private const val MENU_GAP_DP = 8f
private const val MENU_EDGE_MARGIN_DP = 16f
private const val MENU_MIN_WIDTH_DP = 200f
private const val MENU_MAX_HEIGHT_RATIO = 0.6f
private const val MENU_CARD_RADIUS_DP = 12f
private const val MENU_CARD_ELEVATION_DP = 16f
private const val ROW_MIN_HEIGHT_DP = 44f
private const val ROW_PADDING_H_DP = 16f
private const val ROW_PADDING_V_DP = 10f
private const val CHECK_SLOT_DP = 24f
private const val TITLE_TEXT_SP = 15f
private const val PREVIEW_ELEVATION_DP = 12f
private const val PREVIEW_RADIUS_DP = 12f

/**
 * The composed context-menu presentation used when the menu carries a preview
 * or an accessory: the preview (or the source view itself, when no preview is
 * given) is lifted over a dimmed scrim, the commands are rendered into a menu
 * card below it, and the accessory floats in its own [PopupWindow] anchored
 * above the source view — or below it when the space above does not fit.
 *
 * A platform [PopupMenu] cannot coexist with an interactive accessory window:
 * it dismisses on any touch outside its bounds, including the accessory's.
 * Composing the menu inside the presentation keeps every dismissal owned here —
 * scrim tap, item choice, or a `dismiss_requests` change — while accessory
 * actions reach the accessory untouched.
 */
internal class ContextMenuPresentation(
    private val anchor: View,
    private val source: ContextMenuSource,
    private val menuFactory: (Context) -> Menu = { context -> PopupMenu(context, anchor).menu },
    private val previewView: () -> View?,
    private val accessoryView: () -> View?,
    private val popupFactory: (View, Int, Int, Boolean) -> PopupWindow =
        { view, width, height, focusable -> PopupWindow(view, width, height, focusable) }
) : Closeable {
    private var showing = false
    private var presentationPopup: PopupWindow? = null
    private var accessoryPopup: PopupWindow? = null
    private var container: FrameLayout? = null
    private var rowsColumn: LinearLayout? = null
    private var menu: Menu? = null
    private val levelStack = ArrayDeque<Menu>()
    private var currentMenu: Menu? = null

    val isShowing: Boolean get() = showing

    fun show(): Boolean {
        if (showing) {
            return true
        }
        if (source.isEmpty) {
            return false
        }
        showing = true

        val context = anchor.context
        val density = context.resources.displayMetrics.density
        val display = Rect()
        anchor.getWindowVisibleDisplayFrame(display)
        val origin = IntArray(2)
        anchor.getLocationOnScreen(origin)
        val sourceRect = Rect(origin[0], origin[1], origin[0] + anchor.width, origin[1] + anchor.height)

        val preview = previewView()
        val root = FrameLayout(context)
        container = root

        val scrim = View(context).apply {
            setBackgroundColor(SCRIM_COLOR)
            setOnClickListener { dismiss() }
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(scrim, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // Without an explicit preview the source itself is lifted: it is
        // drawn into a snapshot so the lift reproduces exactly what the view
        // renders — rounded corners and alpha included — and elevated through
        // the source's own outline so the lift shadow follows its real shape
        // instead of an artificial card frame.
        val lifted = if (preview != null) {
            // The provider hands back the same view instance on a re-show;
            // detach it from the previous presentation's host first.
            (preview.parent as? ViewGroup)?.removeView(preview)
            MaterialCardView(context).apply {
                radius = PREVIEW_RADIUS_DP * density
                cardElevation = PREVIEW_ELEVATION_DP * density
                clipToOutline = true
                addView(
                    preview,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        } else {
            anchor.snapshot().apply {
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        anchor.outlineProvider.getOutline(anchor, outline)
                    }
                }
                clipToOutline = true
                elevation = PREVIEW_ELEVATION_DP * density
            }
        }
        root.addView(
            lifted,
            FrameLayout.LayoutParams(sourceRect.width(), sourceRect.height())
        )

        val boundMenu = menuFactory(context)
        source.bind(boundMenu, context)
        menu = boundMenu
        currentMenu = boundMenu
        levelStack.clear()
        source.onRebuilt = ::rebuildRows

        val card = buildMenuCard(context)
        root.addView(card, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        val presentation = popupFactory(
            root,
            display.width(),
            display.height(),
            true
        )
        presentation.isTouchable = true
        presentation.isOutsideTouchable = false
        presentation.setOnDismissListener { dismiss() }
        presentationPopup = presentation
        presentation.showAtLocation(anchor, Gravity.NO_GRAVITY, display.left, display.top)

        // Window decorations may inset the popup's content (a focusable popup
        // sits below the status bar while the anchor's coordinates are in raw
        // screen space), so positions are resolved against the root's actual
        // on-screen origin once the window exists rather than the display
        // frame assumed up front. The card is also measured now so
        // WRAP_CONTENT sizes settle against the real content.
        root.post {
            if (root.parent == null) {
                return@post
            }
            val popupOrigin = IntArray(2)
            root.getLocationOnScreen(popupOrigin)
            (lifted.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.leftMargin = sourceRect.left - popupOrigin[0]
                params.topMargin = sourceRect.top - popupOrigin[1]
                lifted.layoutParams = params
            }
            positionMenuCard(root, card, sourceRect, popupOrigin, display, density)
        }

        accessoryView()?.let { accessory ->
            (accessory.parent as? ViewGroup)?.removeView(accessory)
            val gap = (MENU_GAP_DP * density).roundToInt()
            val surface = MaterialColors.getColor(
                context,
                MaterialR.attr.colorSurface,
                "WaterUI context menu requires a Material colorSurface"
            )
            val host = MaterialCardView(context).apply {
                radius = MENU_CARD_RADIUS_DP * density
                cardElevation = MENU_CARD_ELEVATION_DP * density
                setCardBackgroundColor(surface)
                clipToOutline = true
                addView(
                    accessory,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            val maxAccessoryWidth = (display.width() - 2 * gap).coerceAtLeast(0)
            host.measure(
                View.MeasureSpec.makeMeasureSpec(maxAccessoryWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val accessoryHeight = host.measuredHeight
            var accessoryTop = sourceRect.top - gap - accessoryHeight
            if (accessoryTop < display.top + gap) {
                accessoryTop = sourceRect.bottom + gap
            }
            val accessoryLeft = (
                sourceRect.left + sourceRect.width() / 2 - host.measuredWidth / 2
                ).coerceIn(
                    display.left + gap,
                    (display.right - gap - host.measuredWidth).coerceAtLeast(display.left + gap)
                )
            val popup = popupFactory(
                host,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false
            )
            popup.isTouchable = true
            popup.isOutsideTouchable = false
            popup.setOnDismissListener { dismiss() }
            accessoryPopup = popup
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, accessoryLeft, accessoryTop)
        }
        return true
    }

    /** Closes the menu and the accessory; safe no-op when nothing is shown. */
    fun dismiss() {
        if (!showing) {
            return
        }
        showing = false
        source.onRebuilt = null
        source.unbind()
        accessoryPopup?.setOnDismissListener(null)
        presentationPopup?.setOnDismissListener(null)
        accessoryPopup?.dismiss()
        presentationPopup?.dismiss()
        accessoryPopup = null
        presentationPopup = null
        container = null
        rowsColumn = null
        menu = null
        currentMenu = null
        levelStack.clear()
    }

    override fun close() = dismiss()

    private fun positionMenuCard(
        root: FrameLayout,
        card: View,
        sourceRect: Rect,
        popupOrigin: IntArray,
        display: Rect,
        density: Float
    ) {
        if (card.parent == null) {
            return
        }
        val gap = (MENU_GAP_DP * density).roundToInt()
        val margin = (MENU_EDGE_MARGIN_DP * density).roundToInt()
        val minWidth = (MENU_MIN_WIDTH_DP * density).roundToInt()
        val maxWidth = display.width() - 2 * margin
        val maxHeight = (display.height() * MENU_MAX_HEIGHT_RATIO).roundToInt()

        card.minimumWidth = minWidth.coerceAtMost(maxWidth)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val cardWidth = card.measuredWidth.coerceIn(minWidth.coerceAtMost(maxWidth), maxWidth)
        var cardHeight = card.measuredHeight
        if (cardHeight > maxHeight) {
            card.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
            )
            cardHeight = card.measuredHeight.coerceAtMost(maxHeight)
        }

        val left = (sourceRect.left - popupOrigin[0])
            .coerceIn(margin, (display.width() - margin - cardWidth).coerceAtLeast(margin))
        val belowTop = sourceRect.bottom - popupOrigin[1] + gap
        var top = belowTop
        if (belowTop + cardHeight > display.height() - margin) {
            top = sourceRect.top - popupOrigin[1] - gap - cardHeight
        }
        top = top.coerceIn(margin, (display.height() - margin - cardHeight).coerceAtLeast(margin))

        (card.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.width = cardWidth
            params.height = cardHeight
            params.leftMargin = left
            params.topMargin = top
            card.layoutParams = params
        } ?: run {
            card.translationX = left.toFloat()
            card.translationY = top.toFloat()
        }
        root.requestLayout()
    }

    private fun buildMenuCard(context: Context): MaterialCardView {
        val density = context.resources.displayMetrics.density
        val surface = MaterialColors.getColor(
            context,
            MaterialR.attr.colorSurface,
            "WaterUI context menu requires a Material colorSurface"
        )
        return MaterialCardView(context).apply {
            radius = MENU_CARD_RADIUS_DP * density
            cardElevation = MENU_CARD_ELEVATION_DP * density
            setCardBackgroundColor(surface)
            val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            scroll.addView(
                column,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            rowsColumn = column
            addView(
                scroll,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            refreshRows(column)
        }
    }

    private fun rebuildRows() {
        // The bound Menu object is repopulated in place; drilled-in submenu
        // state cannot survive a rebuild, so rows restart at the root level.
        levelStack.clear()
        currentMenu = menu
        rowsColumn?.let { refreshRows(it) }
    }

    private fun refreshRows(column: LinearLayout) {
        val menu = currentMenu ?: return
        val context = column.context
        column.removeAllViews()
        if (levelStack.isNotEmpty()) {
            column.addView(backRow(context))
        }
        // Like the platform popup, the leading column is only reserved when
        // some visible item actually carries an icon (or a checked state that
        // renders in that slot); otherwise titles start at the row padding.
        val hasIcons = (0 until menu.size).any { index ->
            val item = menu[index]
            item.isVisible && (item.icon != null || item.isChecked)
        }
        var previousGroup = -1
        for (index in 0 until menu.size) {
            val item = menu[index]
            if (!item.isVisible) {
                continue
            }
            if (previousGroup >= 0 && item.groupId != previousGroup) {
                column.addView(divider(context))
            }
            previousGroup = item.groupId
            column.addView(commandRow(context, item, hasIcons))
        }
    }

    private fun backRow(context: Context): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            applyRowChrome(context)
        }
        row.addView(
            TextView(context).apply {
                text = "‹"
                textSize = TITLE_TEXT_SP
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        row.setOnClickListener {
            levelStack.removeLastOrNull()
            currentMenu = levelStack.lastOrNull() ?: menu
            rowsColumn?.let(::refreshRows)
        }
        return row
    }

    private fun divider(context: Context): View {
        val density = context.resources.displayMetrics.density
        val color = MaterialColors.getColor(
            context,
            MaterialR.attr.colorOutlineVariant,
            "WaterUI context menu requires a Material colorOutlineVariant"
        )
        return View(context).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1f * density).roundToInt().coerceAtLeast(1)
            )
        }
    }

    private fun commandRow(context: Context, item: MenuItem, hasIcons: Boolean): View {
        val density = context.resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(
            context,
            MaterialR.attr.colorOnSurface,
            "WaterUI context menu requires a Material colorOnSurface"
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            applyRowChrome(context)
            alpha = if (item.isEnabled) 1f else 0.38f
        }
        if (hasIcons) {
            val slotSize = (CHECK_SLOT_DP * density).roundToInt()
            val slot = FrameLayout(context)
            if (item.icon != null) {
                slot.addView(
                    ImageView(context).apply { setImageDrawable(item.icon) },
                    FrameLayout.LayoutParams(slotSize, slotSize, Gravity.CENTER)
                )
            } else {
                slot.addView(
                    TextView(context).apply {
                        text = if (item.isChecked) "✓" else ""
                        textSize = TITLE_TEXT_SP
                        setTextColor(onSurface)
                        gravity = Gravity.CENTER
                    },
                    FrameLayout.LayoutParams(slotSize, slotSize)
                )
            }
            row.addView(
                slot,
                LinearLayout.LayoutParams(
                    slotSize,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (4f * density).roundToInt()
                }
            )
        }
        val title = TextView(context).apply {
            text = item.title
            textSize = TITLE_TEXT_SP
            setTextColor(onSurface)
        }
        row.addView(
            title,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        if (item.hasSubMenu()) {
            val arrow = TextView(context).apply {
                text = "›"
                textSize = TITLE_TEXT_SP
                setTextColor(onSurface)
            }
            row.addView(
                arrow,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        if (item.hasSubMenu()) {
            row.setOnClickListener {
                val subMenu = item.subMenu ?: return@setOnClickListener
                levelStack.addLast(subMenu)
                currentMenu = subMenu
                rowsColumn?.let(::refreshRows)
            }
        } else if (item.isEnabled) {
            row.setOnClickListener {
                source.onMenuItemSelected(item.itemId)
                dismiss()
            }
        }
        return row
    }

    /** Renders this view into a same-size bitmap inside an [ImageView]. */
    private fun View.snapshot(): View {
        return ImageView(context).apply { setImageBitmap(this@snapshot.drawToBitmap()) }
    }

    private fun LinearLayout.applyRowChrome(context: Context) {
        val density = context.resources.displayMetrics.density
        setPadding(
            (ROW_PADDING_H_DP * density).roundToInt(),
            (ROW_PADDING_V_DP * density).roundToInt(),
            (ROW_PADDING_H_DP * density).roundToInt(),
            (ROW_PADDING_V_DP * density).roundToInt()
        )
        minimumHeight = (ROW_MIN_HEIGHT_DP * density).roundToInt()
        val typedValue = TypedValue()
        if (context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                typedValue,
                true
            )
        ) {
            setBackgroundResource(typedValue.resourceId)
        }
        isClickable = true
        isFocusable = true
    }
}
