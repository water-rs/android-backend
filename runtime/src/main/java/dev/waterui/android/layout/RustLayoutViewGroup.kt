package dev.waterui.android.layout

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import dev.waterui.android.runtime.ChildMetadataStruct
import dev.waterui.android.runtime.ChildPlacementStruct
import dev.waterui.android.runtime.LayoutContextStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.RectStruct
import dev.waterui.android.runtime.SafeAreaInsetsStruct
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.toTypeId
import kotlin.math.roundToInt

/**
 * Android [ViewGroup] that mirrors the Swift/Compose Rust layout bridge. Measurement and placement
 * are delegated to the Rust layout engine via JNI.
 */
class RustLayoutViewGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val layoutPtr: Long = 0L,
    private val descriptors: List<ChildDescriptor> = emptyList(),
    private var layoutContext: LayoutContextStruct = LayoutContextStruct.EMPTY
) : ViewGroup(context, attrs) {

    private var lastMetadata: Array<ChildMetadataStruct>? = null
    private var lastParentProposal: ProposalStruct = UnspecifiedProposal
    private var lastChildContexts: Array<LayoutContextStruct>? = null
    
    /**
     * Update the layout context (safe area insets) for this view group.
     * This should be called when safe area changes (e.g., keyboard appears).
     */
    fun updateLayoutContext(context: LayoutContextStruct) {
        if (layoutContext != context) {
            layoutContext = context
            requestLayout()
        }
    }
    
    /**
     * Update safe area insets directly.
     */
    fun updateSafeArea(safeArea: SafeAreaInsetsStruct) {
        updateLayoutContext(layoutContext.copy(safeArea = safeArea))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        require(layoutPtr != 0L) { "onMeasure called with null layout pointer" }
        require(childCount > 0) { "onMeasure called with no children" }

        val constraints = LayoutConstraints.fromMeasureSpecs(widthMeasureSpec, heightMeasureSpec)
        val parentProposal = constraints.toProposalStruct()

        val initialMetadata = Array(childCount) { index ->
            ChildMetadataStruct(
                proposal = UnspecifiedProposal,
                priority = descriptors.getPriority(index),
                stretch = descriptors.getOrNull(index)?.isSpacer == true
            )
        }

        val childProposals = NativeBindings.waterui_layout_propose(layoutPtr, parentProposal, initialMetadata, layoutContext)

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val proposal = childProposals[index]
            val childConstraints = proposal.toConstraints(constraints)
            val childWidthSpec = childConstraints.toMeasureSpec(isWidth = true)
            val childHeightSpec = childConstraints.toMeasureSpec(isWidth = false)
            child.measure(childWidthSpec, childHeightSpec)
        }

        val finalMetadata = Array(childCount) { index ->
            val isSpacer = descriptors.getOrNull(index)?.isSpacer == true
            val child = getChildAt(index)
            val proposal = child.toProposalStruct(isSpacer)
            ChildMetadataStruct(
                proposal = proposal,
                priority = descriptors.getPriority(index),
                stretch = isSpacer
            )
        }

        lastMetadata = finalMetadata
        lastParentProposal = parentProposal

        val requestedSize = NativeBindings.waterui_layout_size(layoutPtr, parentProposal, finalMetadata, layoutContext)
        val measuredWidth = requestedSize.width.resolveDimension(constraints.minWidth, constraints.maxWidth)
        val measuredHeight = requestedSize.height.resolveDimension(constraints.minHeight, constraints.maxHeight)

        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        require(layoutPtr != 0L) { "onLayout called with null layout pointer" }
        require(childCount > 0) { "onLayout called with no children" }

        val metadata = checkNotNull(lastMetadata) { "onLayout called before onMeasure" }

        val bounds = RectStruct(
            x = 0f,
            y = 0f,
            width = (right - left).toFloat(),
            height = (bottom - top).toFloat()
        )

        val placements = NativeBindings.waterui_layout_place(layoutPtr, bounds, lastParentProposal, metadata, layoutContext)
        
        // Store child contexts for nested RustLayoutViewGroups
        lastChildContexts = Array(placements.size) { placements[it].context }
        
        for (index in 0 until childCount) {
            val placement = placements[index]
            val rect = placement.rect
            val child = getChildAt(index)
            val childLeft = rect.x.roundToInt()
            val childTop = rect.y.roundToInt()
            // Use the rect dimensions from Rust layout, not the child's measured dimensions
            val childRight = (rect.x + rect.width).roundToInt()
            val childBottom = (rect.y + rect.height).roundToInt()
            child.layout(childLeft, childTop, childRight, childBottom)
            
            // Propagate layout context to nested RustLayoutViewGroups
            if (child is RustLayoutViewGroup) {
                child.updateLayoutContext(placement.context)
            }
        }
    }
    
    /**
     * Get the layout context for a specific child (after layout).
     */
    fun getChildLayoutContext(index: Int): LayoutContextStruct {
        return lastChildContexts?.getOrNull(index) ?: layoutContext
    }


}

data class ChildDescriptor(
    val typeId: WuiTypeId,
    val isSpacer: Boolean
)

private val spacerTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_spacer_id().toTypeId()
}

private fun List<ChildDescriptor>.getPriority(_index: Int): Int = 0

fun List<Long>.toChildDescriptors(): List<ChildDescriptor> {
    if (isEmpty()) return emptyList()
    return map { pointer ->
        val typeId = NativeBindings.waterui_view_id(pointer).toTypeId()
        ChildDescriptor(typeId = typeId, isSpacer = typeId == spacerTypeId)
    }
}

private data class LayoutConstraints(
    val minWidth: Int,
    val maxWidth: Int,
    val minHeight: Int,
    val maxHeight: Int
) {
    companion object {
        fun fromMeasureSpecs(widthSpec: Int, heightSpec: Int): LayoutConstraints {
            val widthMode = View.MeasureSpec.getMode(widthSpec)
            val widthSize = View.MeasureSpec.getSize(widthSpec)
            val heightMode = View.MeasureSpec.getMode(heightSpec)
            val heightSize = View.MeasureSpec.getSize(heightSpec)

            val maxWidth = when (widthMode) {
                View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST -> widthSize
                else -> Int.MAX_VALUE
            }
            val minWidth = if (widthMode == View.MeasureSpec.EXACTLY) widthSize else 0

            val maxHeight = when (heightMode) {
                View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST -> heightSize
                else -> Int.MAX_VALUE
            }
            val minHeight = if (heightMode == View.MeasureSpec.EXACTLY) heightSize else 0

            return LayoutConstraints(minWidth, maxWidth, minHeight, maxHeight)
        }
    }
}

private fun LayoutConstraints.toProposalStruct(): ProposalStruct = ProposalStruct(
    width = if (maxWidth != Int.MAX_VALUE) maxWidth.toFloat() else Float.NaN,
    height = if (maxHeight != Int.MAX_VALUE) maxHeight.toFloat() else Float.NaN
)

private fun ProposalStruct.toConstraints(parent: LayoutConstraints): LayoutConstraints {
    // Proposal values:
    // - NaN: "None" - child decides size, but capped by parent's available space (AT_MOST)
    // - 0.0: "Zero" - minimum size (EXACTLY 0)
    // - Infinity: "Infinity" - expand as much as possible (UNSPECIFIED)
    // - Other: "Exact" - specific size (EXACTLY value)
    
    val resolvedMaxWidth = when {
        width.isNaN() -> parent.maxWidth  // None: AT_MOST parent's max
        width.isInfinite() -> Int.MAX_VALUE  // Infinity: UNSPECIFIED
        else -> width.roundToInt().coerceAtLeast(0)  // Exact value
    }
    
    val resolvedMaxHeight = when {
        height.isNaN() -> parent.maxHeight  // None: AT_MOST parent's max
        height.isInfinite() -> Int.MAX_VALUE  // Infinity: UNSPECIFIED
        else -> height.roundToInt().coerceAtLeast(0)  // Exact value
    }
    
    // Min is 0 for None/Infinity (flexible), or same as max for Exact (fixed)
    val resolvedMinWidth = when {
        width.isNaN() || width.isInfinite() -> 0
        else -> resolvedMaxWidth
    }
    
    val resolvedMinHeight = when {
        height.isNaN() || height.isInfinite() -> 0
        else -> resolvedMaxHeight
    }

    return LayoutConstraints(
        minWidth = resolvedMinWidth,
        maxWidth = resolvedMaxWidth.coerceAtLeast(0),
        minHeight = resolvedMinHeight,
        maxHeight = resolvedMaxHeight.coerceAtLeast(0)
    )
}

private fun LayoutConstraints.toMeasureSpec(isWidth: Boolean): Int {
    val min = if (isWidth) minWidth else minHeight
    val max = if (isWidth) maxWidth else maxHeight
    val mode = when {
        max == Int.MAX_VALUE -> View.MeasureSpec.UNSPECIFIED
        min >= max -> View.MeasureSpec.EXACTLY
        else -> View.MeasureSpec.AT_MOST
    }
    val size = when (mode) {
        View.MeasureSpec.UNSPECIFIED -> 0
        else -> max
    }
    return View.MeasureSpec.makeMeasureSpec(size, mode)
}

private fun View.toProposalStruct(isSpacer: Boolean): ProposalStruct =
    if (isSpacer) UnspecifiedProposal else ProposalStruct(measuredWidth.toFloat(), measuredHeight.toFloat())

private fun Float.resolveDimension(min: Int, max: Int): Int {
    if (isNaN()) {
        return if (max == Int.MAX_VALUE) min else max
    }
    val rounded = roundToInt().coerceAtLeast(0)
    if (max == Int.MAX_VALUE) return rounded.coerceAtLeast(min)
    return rounded.coerceIn(min, max)
}

private val UnspecifiedProposal = ProposalStruct(Float.NaN, Float.NaN)
