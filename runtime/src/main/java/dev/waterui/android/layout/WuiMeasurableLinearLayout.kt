package dev.waterui.android.layout

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isGone
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.WuiMeasurableLayout
import dev.waterui.android.runtime.WuiProposalAware
import dev.waterui.android.runtime.answerProposal
import dev.waterui.android.runtime.hasWuiSlotIdentity
import dev.waterui.android.runtime.leafAxisAnswer
import dev.waterui.android.runtime.mayFillHorizontal
import dev.waterui.android.runtime.mayFillVertical
import dev.waterui.android.runtime.minusChrome

/**
 * The LinearLayout a labelled WaterUI control wraps its content and platform
 * chrome in.
 *
 * The contract for a control is platform chrome around negotiable content:
 * the chrome — switches, buttons, indicators — measures at its intrinsic
 * size, while children carrying a WaterUI slot identity hear the control's
 * own proposal minus the room the chrome takes on the flow axis. The
 * container's answer is the summed result on an axis it does not stretch,
 * and the offer on one it does ([leafAxisAnswer]). Measured through a plain
 * `LinearLayout` probe instead, a stack's minimum query reaches the label as
 * `AT_MOST 0` and a text label answers zero height, collapsing the whole
 * control.
 *
 * Weighted content children split a finite flow offer the way `LinearLayout`
 * splits weighted space — proportionally to their weights — so a row of two
 * labels each wraps inside its own share.
 */
internal open class WuiMeasurableLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr), WuiMeasurableLayout, WuiProposalAware {

    private val density = resources.displayMetrics.density

    /**
     * The narrowest extent the container may answer on the horizontal axis,
     * in dp — `AxisExpandingLinearLayout` floors its usable width here.
     */
    protected open val widthFloorDp: Float get() = 0f

    override fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
        val horizontal = orientation == HORIZONTAL
        var flowPx = (if (horizontal) paddingLeft + paddingRight else paddingTop + paddingBottom).toFloat()
        var crossPx = (if (horizontal) paddingTop + paddingBottom else paddingLeft + paddingRight).toFloat()
        val content = mutableListOf<View>()

        // Platform chrome answers at its intrinsic extent on both axes; only
        // children carrying a WaterUI slot identity negotiate the proposal.
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone) continue
            if (child.hasWuiSlotIdentity()) {
                content.add(child)
                continue
            }
            child.measure(unboundedSpec(), unboundedSpec())
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            flowPx += if (horizontal) {
                child.measuredWidth + margins.horizontally
            } else {
                child.measuredHeight + margins.vertically
            }
            crossPx = maxOf(crossPx, if (horizontal) {
                (child.measuredHeight + margins.vertically).toFloat()
            } else {
                (child.measuredWidth + margins.horizontally).toFloat()
            })
        }

        // A finite flow offer splits among weighted content children the way
        // LinearLayout splits weighted space; a lone or unweighted child
        // hears the offer whole.
        val allWeighted = content.isNotEmpty() && content.all { child ->
            ((child.layoutParams as? LayoutParams)?.weight ?: 0f) > 0f
        }
        val totalWeight = if (allWeighted) {
            content.sumOf { ((it.layoutParams as? LayoutParams)?.weight ?: 0f).toDouble() }.toFloat()
        } else {
            0f
        }
        val flowOfferFinite = proposal.flowAxis(horizontal).isFinite()

        for (child in content) {
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            // The child hears the proposal minus the room the chrome and its
            // own margins take: the chrome flow extent plus flow margins on
            // the flow axis, the container padding plus cross margins on the
            // cross axis.
            val horizontalChrome = if (horizontal) {
                flowPx + margins.horizontally
            } else {
                paddingLeft + paddingRight + margins.horizontally.toFloat()
            }
            val verticalChrome = if (horizontal) {
                paddingTop + paddingBottom + margins.vertically.toFloat()
            } else {
                flowPx + margins.vertically
            }
            var offer = proposal.minusChrome(horizontalChrome / density, verticalChrome / density)
            val weight = (child.layoutParams as? LayoutParams)?.weight ?: 0f
            if (allWeighted && flowOfferFinite) {
                offer = offer.withFlowAxis(
                    horizontal,
                    offer.flowAxis(horizontal) * (weight / totalWeight)
                )
            }
            val dims = child.answerProposal(offer, density)
            flowPx += (if (horizontal) dims.size.width else dims.size.height) * density +
                (if (horizontal) margins.horizontally else margins.vertically)
            crossPx = maxOf(crossPx, (if (horizontal) dims.size.height else dims.size.width) * density +
                (if (horizontal) margins.vertically else margins.horizontally))
        }

        val intrinsicWidth = if (horizontal) flowPx else crossPx
        val intrinsicHeight = if (horizontal) crossPx else flowPx
        val axis = getTag(TAG_STRETCH_AXIS) as? StretchAxis ?: StretchAxis.NONE
        return ViewDimensionsStruct(
            size = SizeStruct(
                width = leafAxisAnswer(
                    proposal.width,
                    maxOf(intrinsicWidth / density, widthFloorDp),
                    axis.mayFillHorizontal()
                ),
                height = leafAxisAnswer(
                    proposal.height,
                    intrinsicHeight / density,
                    axis.mayFillVertical()
                )
            ),
            horizontalGuides = emptyArray(),
            verticalGuides = emptyArray()
        )
    }

    /**
     * Forwards the selected proposal minus chrome to content that hosts a
     * WaterUI layout of its own, so a nested layout inside a control's label
     * negotiates under the same offer the probe used.
     */
    override fun setWuiSelectedProposal(proposalWidth: Float, proposalHeight: Float) {
        val horizontal = orientation == HORIZONTAL
        var chromeFlowPx = (if (horizontal) paddingLeft + paddingRight else paddingTop + paddingBottom).toFloat()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone || child.hasWuiSlotIdentity()) continue
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            chromeFlowPx += if (horizontal) {
                child.measuredWidth + margins.horizontally
            } else {
                child.measuredHeight + margins.vertically
            }
        }
        val proposal = ProposalStruct(proposalWidth, proposalHeight)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.isGone || child !is WuiProposalAware || !child.hasWuiSlotIdentity()) {
                continue
            }
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            val horizontalChrome = if (horizontal) {
                chromeFlowPx + margins.horizontally
            } else {
                paddingLeft + paddingRight + margins.horizontally.toFloat()
            }
            val verticalChrome = if (horizontal) {
                paddingTop + paddingBottom + margins.vertically.toFloat()
            } else {
                chromeFlowPx + margins.vertically
            }
            val offer = proposal.minusChrome(horizontalChrome / density, verticalChrome / density)
            child.setWuiSelectedProposal(offer.width, offer.height)
        }
    }

    private fun unboundedSpec() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

    private fun ProposalStruct.flowAxis(horizontal: Boolean): Float =
        if (horizontal) width else height

    private fun ProposalStruct.withFlowAxis(horizontal: Boolean, value: Float): ProposalStruct =
        if (horizontal) ProposalStruct(value, height) else ProposalStruct(width, value)

    private val ViewGroup.MarginLayoutParams?.horizontally: Int
        get() = this?.let { it.marginStart + it.marginEnd } ?: 0

    private val ViewGroup.MarginLayoutParams?.vertically: Int
        get() = this?.let { it.topMargin + it.bottomMargin } ?: 0
}
