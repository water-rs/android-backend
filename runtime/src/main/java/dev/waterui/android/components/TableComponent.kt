package dev.waterui.android.components

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.TableColumnStruct
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRustAnimation
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.toTypeface

private val tableTypeId: WuiTypeId by lazy { WatcherJni.tableId().toTypeId() }

private val tableRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsTable(node.rawPtr)
    val computed = WuiComputed.tableColumns(struct.columnsPtr, env)

    val tableView = WuiTableGridView(context, env, registry)
    computed.observeWithAnimation { cols, animation ->
        tableView.applyRustAnimation(animation) {
            tableView.setColumns(cols)
        }
    }
    tableView.disposeWith(computed)
    tableView
}

internal fun RegistryBuilder.registerWuiTable() {
    register({ tableTypeId }, tableRenderer)
}

private class WuiTableGridView(
    context: Context,
    private val env: WuiEnvironment,
    private val registry: RenderRegistry
) : ViewGroup(context) {

    private val cellPaddingPx = 8f.dp(context).toInt()
    private val rowSpacingPx = 4f.dp(context).toInt()
    private val colSpacingPx = 8f.dp(context).toInt()
    private val minRowHeightPx = 28f.dp(context).toInt()

    private var columnCount: Int = 0
    private var rowCount: Int = 0 // includes header row

    private var columnWidths: IntArray = intArrayOf()
    private var rowHeights: IntArray = intArrayOf()

    fun setColumns(columns: List<TableColumnStruct>) {
        removeAllViews()

        if (columns.isEmpty()) {
            columnCount = 0
            rowCount = 0
            requestLayout()
            return
        }

        columnCount = columns.size

        val rowsPerColumn = columns.map { col ->
            if (col.rowsPtr == 0L) 0 else WatcherJni.anyViewsLen(col.rowsPtr)
        }
        val maxDataRows = rowsPerColumn.maxOrNull() ?: 0
        rowCount = 1 + maxDataRows

        // Header row
        for (colIndex in columns.indices) {
            addView(createHeaderCell(columns[colIndex].labelContentPtr))
        }

        // Data rows
        for (rowIndex in 0 until maxDataRows) {
            for (colIndex in columns.indices) {
                val col = columns[colIndex]
                val cellView =
                    if (col.rowsPtr != 0L && rowIndex < (rowsPerColumn[colIndex])) {
                        val anyViewPtr = WatcherJni.anyViewsGetView(col.rowsPtr, rowIndex)
                        if (anyViewPtr != 0L) inflateAnyView(context, anyViewPtr, env, registry) else spacerCell()
                    } else {
                        spacerCell()
                    }
                addView(wrapCell(cellView))
            }
        }

        // Drop AnyViews pointers now that we've consumed their AnyView children.
        for (col in columns) {
            if (col.rowsPtr != 0L) {
                WatcherJni.dropAnyViews(col.rowsPtr)
            }
        }

        requestLayout()
    }

    private fun createHeaderCell(contentPtr: Long): View {
        val computed = WuiComputed.styledString(contentPtr, env)
        val textView = TextView(context).apply {
            includeFontPadding = false
            setLineSpacing(0f, 1f)
            setPadding(cellPaddingPx, cellPaddingPx, cellPaddingPx, cellPaddingPx)
        }

        val foreground = ThemeBridge.foreground(env)
        foreground.observe { color -> textView.setTextColor(color.toColorInt()) }
        foreground.attachTo(textView)

        val bodyFont = ThemeBridge.bodyFont(env)
        bodyFont.observe { font ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, font.size)
            textView.typeface = font.toTypeface()
        }
        bodyFont.attachTo(textView)

        computed.observeWithAnimation { styled, animation ->
            val resolved = styled.toCharSequence(env)
            textView.applyRustAnimation(animation) {
                textView.text = resolved
            }
            textView.requestLayout()
        }
        textView.disposeWith(computed)

        return textView
    }

    private fun wrapCell(child: View): View {
        child.setPadding(
            child.paddingLeft + cellPaddingPx,
            child.paddingTop + cellPaddingPx,
            child.paddingRight + cellPaddingPx,
            child.paddingBottom + cellPaddingPx
        )
        return child
    }

    private fun spacerCell(): View = View(context).apply {
        layoutParams = LayoutParams(0, 0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (childCount == 0 || columnCount == 0 || rowCount == 0) {
            setMeasuredDimension(0, 0)
            return
        }

        val totalCells = columnCount * rowCount
        if (childCount != totalCells) {
            // Defensive: keep layout stable even if we haven't built all cells yet.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        if (columnWidths.size != columnCount) columnWidths = IntArray(columnCount)
        if (rowHeights.size != rowCount) rowHeights = IntArray(rowCount)
        columnWidths.fill(0)
        rowHeights.fill(0)

        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        for (row in 0 until rowCount) {
            for (col in 0 until columnCount) {
                val idx = row * columnCount + col
                val child = getChildAt(idx)
                measureChild(child, unspecified, unspecified)
                columnWidths[col] = maxOf(columnWidths[col], child.measuredWidth)
                rowHeights[row] = maxOf(rowHeights[row], child.measuredHeight)
            }
        }

        // Enforce a minimum row height for readability.
        for (row in rowHeights.indices) {
            rowHeights[row] = maxOf(rowHeights[row], minRowHeightPx)
        }

        val width = columnWidths.sum() + (colSpacingPx * (columnCount - 1))
        val height = rowHeights.sum() + (rowSpacingPx * (rowCount - 1))

        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (childCount == 0 || columnCount == 0 || rowCount == 0) return

        var y = paddingTop
        for (row in 0 until rowCount) {
            var x = paddingLeft
            for (col in 0 until columnCount) {
                val idx = row * columnCount + col
                val child = getChildAt(idx)
                val cw = columnWidths.getOrNull(col) ?: child.measuredWidth
                val ch = rowHeights.getOrNull(row) ?: child.measuredHeight
                child.layout(x, y, x + cw, y + ch)
                x += cw + colSpacingPx
            }
            y += (rowHeights.getOrNull(row) ?: 0) + rowSpacingPx
        }
    }
}
