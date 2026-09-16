package dev.waterui.android.layout

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.waterui.android.components.SPACER_DEFAULT_LAYOUT_PRIORITY
import dev.waterui.android.components.wuiSpacer
import dev.waterui.android.runtime.HorizontalAlignment
import dev.waterui.android.runtime.HorizontalGuideStruct
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.SubViewStruct
import dev.waterui.android.runtime.SubviewPlacementStruct
import dev.waterui.android.runtime.TAG_LAYOUT_PRIORITY
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.WuiLiveSlotTraits
import dev.waterui.android.runtime.WuiMeasurableLayout
import dev.waterui.android.runtime.WuiProposalAware
import dev.waterui.android.runtime.getWuiLayoutPriority
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.measureSpecToProposalPx
import dev.waterui.android.runtime.proposalToMeasureSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the proposal a Rust layout selected travels through placement.
 *
 * The shared layout contract carries the selected proposal next to each
 * child's resolved frame: two children may be handed identical bounds yet have
 * been negotiated under different proposals, so the proposal is part of the
 * placement — never re-derived from the frame, and never whatever the last
 * measurement probe happened to offer. These tests pin the transport rules the
 * Android side of that contract answers to: the proposal reaches the child
 * unchanged (NaN stays unspecified, infinity stays unbounded), the child is
 * measured under it rather than under the frame, and wrappers forward it the
 * way they forward size.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProposalPlacementTest {
    private companion object {
        const val DENSITY = 2f
    }

    private fun context(): Context =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun placement(
        x: Float = 0f,
        y: Float = 0f,
        width: Float,
        height: Float,
        proposalWidth: Float,
        proposalHeight: Float
    ) = SubviewPlacementStruct(x, y, width, height, proposalWidth, proposalHeight)

    /** Records the proposal a WaterUI parent delivers instead of applying it. */
    private class RecordingProposalAware(context: Context) : View(context), WuiProposalAware {
        var selected: ProposalStruct? = null
            private set

        override fun setWuiSelectedProposal(proposalWidth: Float, proposalHeight: Float) {
            selected = ProposalStruct(proposalWidth, proposalHeight)
        }
    }

    /** Records the spec of the last measure pass instead of measuring. */
    private class RecordingMeasuredView(context: Context) : View(context) {
        var lastWidthMode = -1
            private set
        var lastWidthSize = -1
            private set
        var lastHeightMode = -1
            private set
        var lastHeightSize = -1
            private set

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            lastWidthMode = MeasureSpec.getMode(widthMeasureSpec)
            lastWidthSize = MeasureSpec.getSize(widthMeasureSpec)
            lastHeightMode = MeasureSpec.getMode(heightMeasureSpec)
            lastHeightSize = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(10, 10)
        }
    }

    /**
     * Stands in for a view hosting a Rust layout of its own: it answers probes
     * itself and records the proposal each probe carried.
     */
    private class RecordingMeasurableLayout(context: Context) : View(context), WuiMeasurableLayout {
        val probes = mutableListOf<ProposalStruct>()
        var answer = ViewDimensionsStruct(SizeStruct(10f, 10f), emptyArray(), emptyArray())

        override fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
            probes.add(proposal)
            return answer
        }
    }

    /**
     * Stands in for a view whose slot traits are recomputed per read the way a
     * `RustLayoutViewGroup` recomputes `stretch_axis(children)` — the answer
     * may change between reads, and every read must see the latest.
     */
    private class MutableTraitsView(context: Context) : View(context), WuiLiveSlotTraits {
        var axis = StretchAxis.NONE
        var priority = 0

        override fun resolveWuiStretchAxis(): StretchAxis = axis
        override fun resolveWuiLayoutPriority(): Int = priority
    }

    private fun atMost(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
    private fun unspecified(): Int = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

    @Test
    fun equalBoundsCarryDifferentSelectedProposals() {
        val child = RecordingProposalAware(context())

        child.deliverSelectedProposal(placement(width = 200f, height = 40f, proposalWidth = 200f, proposalHeight = Float.NaN))
        child.deliverSelectedProposal(placement(width = 200f, height = 40f, proposalWidth = Float.POSITIVE_INFINITY, proposalHeight = 60f))

        // Identical frames, different selections: the proposal that arrives is
        // the one the layout selected, not anything the frame could describe.
        val selected = checkNotNull(child.selected)
        assertEquals(Float.POSITIVE_INFINITY, selected.width)
        assertEquals(60f, selected.height)
    }

    @Test
    fun frameIsTheAllocationNeverTheOffer() {
        val child = RecordingMeasuredView(context())

        // A ZStack hands a child the frame it stretched to under an unbounded
        // offer; measuring the child at the frame would pin it to the answer.
        child.measureForPlacement(
            placement(width = 200f, height = 40f, proposalWidth = Float.POSITIVE_INFINITY, proposalHeight = Float.NaN),
            DENSITY
        )

        assertEquals(View.MeasureSpec.UNSPECIFIED, child.lastWidthMode)
        assertEquals(View.MeasureSpec.UNSPECIFIED, child.lastHeightMode)
    }

    @Test
    fun placementMeasuresUnderTheSelectedProposalNotTheLastProbe() {
        val child = RecordingMeasuredView(context())

        // Probes arrive in any order; the last one must not leak into placement.
        child.measure(atMost(400), unspecified())
        child.measure(atMost(160), atMost(80))

        child.measureForPlacement(
            placement(width = 200f, height = 40f, proposalWidth = 200f, proposalHeight = 40f),
            DENSITY
        )

        assertEquals(View.MeasureSpec.AT_MOST, child.lastWidthMode)
        assertEquals(400, child.lastWidthSize)
        assertEquals(View.MeasureSpec.AT_MOST, child.lastHeightMode)
        assertEquals(80, child.lastHeightSize)
    }

    @Test
    fun finiteOfferCapsTheAskNotTheAnswer() {
        // A child whose minimum exceeds the offer is still offered the offer;
        // it answers with its size and the frame overflows.
        val child = RecordingMeasuredView(context())

        child.measureForPlacement(
            placement(width = 100f, height = 10f, proposalWidth = 60f, proposalHeight = Float.NaN),
            DENSITY
        )

        assertEquals(View.MeasureSpec.AT_MOST, child.lastWidthMode)
        assertEquals(120, child.lastWidthSize)
        assertEquals(View.MeasureSpec.UNSPECIFIED, child.lastHeightMode)
    }

    @Test
    fun unspecifiedAxisSurvivesTheSpecRoundTrip() {
        // A scroll container's unbounded axis is spelled UNSPECIFIED to a plain
        // Android child, and reads back as an unspecified proposal — the only
        // axis encoding the spec channel can carry losslessly.
        val spec = proposalToMeasureSpec(Float.NaN)

        assertEquals(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.getMode(spec))
        assertTrue(measureSpecToProposalPx(spec).isNaN())
        assertEquals(200f, measureSpecToProposalPx(atMost(200)))
    }

    @Test
    fun unboundedProbeReachesANestedLayoutUnchanged() {
        // A scroll container probes its content without bound on the scroll
        // axis. Through MeasureSpec that probe would collapse to "unspecified";
        // a view hosting a Rust layout must receive the probe itself.
        val host = RecordingMeasurableLayout(context())
        val subview = SubViewStruct(view = host, stretchAxis = StretchAxis.NONE, density = 1f)

        subview.measureForLayout(Float.POSITIVE_INFINITY, 40f)

        assertEquals(Float.POSITIVE_INFINITY, host.probes.last().width)
        assertEquals(40f, host.probes.last().height)
    }

    @Test
    fun transparentWrapperForwardsTheSelectedProposal() {
        val context = context()
        val wrapper = PassThroughFrameLayout(context)
        val child = RecordingProposalAware(context)
        wrapper.addView(child)

        // The placement order a Rust parent keeps: measure, then deliver, then
        // lay out — delivery always follows the last measure of the pass.
        wrapper.measure(unspecified(), unspecified())
        wrapper.setWuiSelectedProposal(300f, Float.NaN)
        wrapper.layout(0, 0, 600, 80)

        val selected = checkNotNull(child.selected)
        assertEquals(300f, selected.width)
        assertTrue(selected.height.isNaN())
    }

    @Test
    fun wrapperForwardsToContentInflatedAfterTheSelection() {
        // A Dynamic swaps its child between passes; the selection delivered
        // for the next pass reaches the replacement, not the view it replaced.
        val context = context()
        val wrapper = PassThroughFrameLayout(context)
        val first = RecordingProposalAware(context)
        wrapper.addView(first)
        wrapper.measure(unspecified(), unspecified())
        wrapper.setWuiSelectedProposal(300f, Float.NaN)
        wrapper.layout(0, 0, 600, 80)

        val second = RecordingProposalAware(context)
        wrapper.removeView(first)
        wrapper.addView(second)
        wrapper.measure(unspecified(), unspecified())
        wrapper.setWuiSelectedProposal(300f, Float.NaN)
        wrapper.layout(0, 0, 600, 80)

        val selected = checkNotNull(second.selected)
        assertEquals(300f, selected.width)
        assertTrue(selected.height.isNaN())
    }

    @Test
    fun spacerAsksBelowEveryNamedPriority() {
        assertEquals(Int.MIN_VALUE, SPACER_DEFAULT_LAYOUT_PRIORITY)
        assertEquals(Int.MIN_VALUE, wuiSpacer(context()).getWuiLayoutPriority())
    }

    @Test
    fun explicitPriorityOverridesTheSpacerDefault() {
        // `.layoutPriority(5)` on a Spacer: the wrapper answers as the content
        // until the explicit value is stamped, and the explicit value wins —
        // including over the content's own below-everything default.
        val context = context()
        val wrapper = PassThroughFrameLayout(context)
        // Inflation supplies the live stretch tag before wrapping the child.
        val spacer = wuiSpacer(context).apply { setTag(TAG_STRETCH_AXIS, StretchAxis.MAIN_AXIS) }
        wrapper.addView(spacer)
        assertEquals(Int.MIN_VALUE, wrapper.getWuiLayoutPriority())

        wrapper.setTag(TAG_LAYOUT_PRIORITY, 5)

        assertEquals(5, wrapper.getWuiLayoutPriority())
    }

    @Test
    fun explicitWrapperPriorityShadowsContentsAnswer() {
        val context = context()
        val content = View(context).apply {
            setTag(TAG_STRETCH_AXIS, StretchAxis.NONE)
            setTag(TAG_LAYOUT_PRIORITY, 3)
        }
        val wrapper = PassThroughFrameLayout(context)
        wrapper.addView(content)
        assertEquals(3, wrapper.getWuiLayoutPriority())

        wrapper.setTag(TAG_LAYOUT_PRIORITY, 5)

        assertEquals(5, wrapper.getWuiLayoutPriority())
    }

    @Test
    fun wrapperPresentsItsContentsSlotIdentityLive() {
        val context = context()
        val child = View(context).apply {
            setTag(TAG_STRETCH_AXIS, StretchAxis.HORIZONTAL)
            setTag(TAG_LAYOUT_PRIORITY, 5)
        }
        val wrapper = PassThroughFrameLayout(context)
        wrapper.addView(child)

        assertEquals(StretchAxis.HORIZONTAL, wrapper.getWuiStretchAxis())
        assertEquals(5, wrapper.getWuiLayoutPriority())
    }

    @Test
    fun wrapperStretchAnswerTracksContentTraitChanges() {
        // The wrapper stands in its content's slot, so the answer changes the
        // moment the content's own answer does — a reconciled membership, a
        // nested layout recomputing over its children. There is no copy to go
        // stale, and the new answer is there for the next measurement pass.
        val context = context()
        val content = MutableTraitsView(context)
        val wrapper = PassThroughFrameLayout(context)
        wrapper.addView(content)
        assertEquals(StretchAxis.NONE, wrapper.getWuiStretchAxis())
        assertEquals(0, wrapper.getWuiLayoutPriority())

        content.axis = StretchAxis.MAIN_AXIS
        content.priority = 2

        assertEquals(StretchAxis.MAIN_AXIS, wrapper.getWuiStretchAxis())
        assertEquals(2, wrapper.getWuiLayoutPriority())
    }

    @Test
    fun wrapperStretchAnswerTracksContentSwaps() {
        // A Dynamic swaps its child: the wrapper's slot identity is whichever
        // content it holds now, never the view it replaced.
        val context = context()
        val wrapper = PassThroughFrameLayout(context)
        val first = View(context).apply { setTag(TAG_STRETCH_AXIS, StretchAxis.NONE) }
        wrapper.addView(first)
        assertEquals(StretchAxis.NONE, wrapper.getWuiStretchAxis())

        wrapper.removeView(first)
        val second = View(context).apply { setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH) }
        wrapper.addView(second)

        assertEquals(StretchAxis.BOTH, wrapper.getWuiStretchAxis())
    }

    @Test
    fun traitsAndProbesCrossArbitraryWrapperDepth() {
        // Metadata stacks — `.background(...).layoutPriority(1)` around a
        // stack: the inner wrapper is the outer's content, so probes and slot
        // identity cross every level untouched.
        val context = context()
        val content = RecordingMeasurableLayout(context).apply {
            setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH)
        }
        val inner = PassThroughFrameLayout(context)
        inner.addView(content)
        val outer = PassThroughFrameLayout(context)
        outer.addView(inner)

        outer.measureForLayout(ProposalStruct(80f, Float.POSITIVE_INFINITY))

        val probe = content.probes.single()
        assertEquals(80f, probe.width)
        assertEquals(Float.POSITIVE_INFINITY, probe.height)
        assertEquals(StretchAxis.BOTH, outer.getWuiStretchAxis())
    }

    @Test
    fun wrapperForwardsProbesVerbatimToMeasurableContent() {
        // A metadata wrapper is transparent to layout: a probe through it
        // reaches the content untouched — infinity stays an unbounded ask,
        // NaN stays unspecified, zero stays an empty offer — and the content's
        // explicit alignment guides come back through.
        val context = context()
        val guides = arrayOf(HorizontalGuideStruct(HorizontalAlignment.LEADING, 12f))
        val content = RecordingMeasurableLayout(context).apply {
            answer = ViewDimensionsStruct(SizeStruct(10f, 10f), guides, emptyArray())
        }
        val wrapper = PassThroughFrameLayout(context)
        wrapper.addView(content)

        val dimensions = wrapper.measureForLayout(ProposalStruct(Float.POSITIVE_INFINITY, Float.NaN))
        wrapper.measureForLayout(ProposalStruct(0f, 40f))

        assertEquals(Float.POSITIVE_INFINITY, content.probes[0].width)
        assertTrue(content.probes[0].height.isNaN())
        assertEquals(0f, content.probes[1].width)
        assertEquals(40f, content.probes[1].height)
        assertTrue(dimensions.horizontalGuides.contentEquals(guides))
    }

    @Test
    fun subViewProbePassesThroughWrapperToMeasurableContent() {
        // What Rust sees when it probes a wrapped nested layout: the wrapper
        // is measurable, so the probe crosses it untouched rather than
        // collapsing through MeasureSpec into an unspecified ask.
        val context = context()
        val content = RecordingMeasurableLayout(context)
        val wrapper = PassThroughFrameLayout(context)
        wrapper.addView(content)
        val subview = SubViewStruct(view = wrapper, stretchAxis = StretchAxis.NONE, density = 1f)

        val dimensions = subview.measureForLayout(Float.POSITIVE_INFINITY, 40f)

        val probe = content.probes.single()
        assertEquals(Float.POSITIVE_INFINITY, probe.width)
        assertEquals(40f, probe.height)
        assertEquals(10f, dimensions.size.width)
    }

    @Test
    fun wrapperProbeMeasuresTheContentNotItsAuxiliaryChildren() {
        // A host that overlays surfaces of its own (video, capture) answers
        // for the content alone: a match-parent overlay must not inflate the
        // answer to the whole offer.
        val context = context()
        val density = context.resources.displayMetrics.density
        val content = RecordingMeasuredView(context).apply {
            setTag(TAG_STRETCH_AXIS, StretchAxis.NONE)
        }
        val overlay = View(context)
        val host = PassThroughFrameLayout(context)
        host.addView(content)
        host.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val dimensions = host.measureForLayout(ProposalStruct(200f, 100f))

        // The content was measured under the proposal, and the answer is the
        // content's own 10px — not the offer a match-parent overlay fills.
        assertEquals(View.MeasureSpec.AT_MOST, content.lastWidthMode)
        assertEquals(kotlin.math.ceil(200f * density).toInt(), content.lastWidthSize)
        assertEquals(10f / density, dimensions.size.width)
    }

    @Test(expected = IllegalStateException::class)
    fun wrapperRefusesAmbiguousContent() {
        // Two children carrying a WaterUI slot identity: the wrapper cannot
        // pick a slot to stand in, so it fails rather than answering for one
        // of them arbitrarily.
        val context = context()
        val wrapper = PassThroughFrameLayout(context)
        wrapper.addView(View(context).apply { setTag(TAG_STRETCH_AXIS, StretchAxis.NONE) })
        wrapper.addView(View(context).apply { setTag(TAG_STRETCH_AXIS, StretchAxis.NONE) })

        wrapper.getWuiStretchAxis()
    }

    @Test
    fun childStretchAxisIsReadLiveNotAtInflation() {
        // The stretch answer a parent reads is the tag the child carries now;
        // a container that changes composition changes its answer.
        val context = context()
        val child = View(context).apply {
            setTag(TAG_STRETCH_AXIS, StretchAxis.NONE)
        }
        assertEquals(StretchAxis.NONE, child.getWuiStretchAxis())

        child.setTag(TAG_STRETCH_AXIS, StretchAxis.HORIZONTAL)

        assertEquals(StretchAxis.HORIZONTAL, child.getWuiStretchAxis())
    }

    @Test
    fun axisExpandingRowFillsTheFrameItIsHanded() {
        // Measurement offered room; placement allocates it. The row reports a
        // minimum under AT_MOST, then expands into the frame it is laid out to.
        val context = context()
        val row = AxisExpandingLinearLayout(context)
        val content = View(context)
        row.addView(
            content,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                50
            )
        )

        row.measure(atMost(2000), atMost(100))
        row.layout(0, 0, 1600, 100)

        assertEquals("the row fills the frame placement handed it", 1600, row.measuredWidth)
        assertEquals(1600, content.measuredWidth)
    }
}
