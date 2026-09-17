package dev.waterui.android.layout

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import dev.waterui.android.runtime.ProbeMemos
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.SubViewStruct
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.ViewDimensionsStruct
import dev.waterui.android.runtime.WuiMeasurableLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The measurement memo one layout negotiation carries.
 *
 * Rust layout containers probe their children freely — ideal, minimum, and
 * allocated offers — and a probe repeated under the same proposal must return
 * the remembered answer instead of measuring the child again. Without the
 * memo every probe of a nested layout re-measures its whole subtree, which is
 * the exponential measurement storm that made `edge_layout` ANR on the main
 * thread. The memo is keyed on the proposal's raw bits — NaN, the infinities,
 * and signed zero are distinct proposals — shared by every bridge the pass
 * builds, and dies with the negotiation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubViewMeasurementMemoTest {
    private fun context(): Context =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    /** Records every probe the child is actually asked to measure. */
    private class CountingMeasurableView(context: Context) : View(context), WuiMeasurableLayout {
        val probes = mutableListOf<ProposalStruct>()
        var answer = ViewDimensionsStruct(SizeStruct(10f, 10f), emptyArray(), emptyArray())

        override fun measureForLayout(proposal: ProposalStruct, memos: ProbeMemos): ViewDimensionsStruct {
            probes.add(proposal)
            return answer
        }
    }

    private fun subviewOf(child: View, memos: ProbeMemos = ProbeMemos()) =
        SubViewStruct(view = child, stretchAxis = StretchAxis.NONE, density = 1f, memos = memos)

    @Test
    fun repeatedProposalMeasuresTheChildOnce() {
        val child = CountingMeasurableView(context())
        val subview = subviewOf(child)

        val first = subview.measureForLayout(40f, Float.NaN)
        val second = subview.measureForLayout(40f, Float.NaN)

        assertEquals(1, child.probes.size)
        assertEquals(first, second)
    }

    @Test
    fun distinctProposalsMeasureIndependently() {
        val child = CountingMeasurableView(context())
        val subview = subviewOf(child)

        subview.measureForLayout(40f, Float.NaN)
        subview.measureForLayout(0f, Float.NaN)
        subview.measureForLayout(40f, 20f)

        assertEquals(3, child.probes.size)
    }

    @Test
    fun proposalKeyingIsOnRawBits() {
        // NaN (unspecified), infinity (unbounded), finite, and signed zero are
        // all distinct proposals: equality would collapse NaN with itself not
        // at all and -0.0 with 0.0 entirely — both wrong here.
        val child = CountingMeasurableView(context())
        val subview = subviewOf(child)

        subview.measureForLayout(Float.NaN, Float.NaN)
        subview.measureForLayout(Float.POSITIVE_INFINITY, Float.NaN)
        subview.measureForLayout(0f, Float.NaN)
        subview.measureForLayout(-0f, Float.NaN)
        subview.measureForLayout(Float.NaN, Float.NaN)

        assertEquals(4, child.probes.size)
    }

    @Test
    fun recursiveMeasurementOfTheSameProposalFailsFast() {
        // A container probing a child under the same proposal from inside that
        // child's own measurement is a broken layout contract, not a query to
        // answer twice — the Apple backend's SubViewProxy fatalErrors on it.
        lateinit var subview: SubViewStruct
        var recursiveError: IllegalStateException? = null
        val child = object : View(context()), WuiMeasurableLayout {
            override fun measureForLayout(proposal: ProposalStruct, memos: ProbeMemos): ViewDimensionsStruct {
                try {
                    subview.measureForLayout(proposal.width, proposal.height)
                } catch (e: IllegalStateException) {
                    recursiveError = e
                }
                return ViewDimensionsStruct(SizeStruct(10f, 10f), emptyArray(), emptyArray())
            }
        }
        subview = SubViewStruct(view = child, stretchAxis = StretchAxis.NONE, density = 1f, memos = ProbeMemos())

        subview.measureForLayout(40f, Float.NaN)

        assertTrue(recursiveError != null)
    }

    @Test
    fun recursionUnderADifferentProposalIsAllowed() {
        // Only the in-flight proposal is guarded: a measurement may legitimately
        // probe the same child under a different offer before answering.
        lateinit var subview: SubViewStruct
        var innerAnswer: ViewDimensionsStruct? = null
        val child = object : View(context()), WuiMeasurableLayout {
            var entered = false
            override fun measureForLayout(proposal: ProposalStruct, memos: ProbeMemos): ViewDimensionsStruct {
                if (!entered && proposal.width == 40f) {
                    entered = true
                    innerAnswer = subview.measureForLayout(20f, proposal.height)
                }
                return ViewDimensionsStruct(SizeStruct(10f, 10f), emptyArray(), emptyArray())
            }
        }
        subview = SubViewStruct(view = child, stretchAxis = StretchAxis.NONE, density = 1f, memos = ProbeMemos())

        subview.measureForLayout(40f, Float.NaN)

        assertTrue(innerAnswer != null)
    }

    @Test
    fun consecutiveNegotiationsNeverShareAMemo() {
        // Each `waterui_layout_*` entry builds a fresh store, so no memoized
        // answer can outlive the synchronous negotiation that computed it.
        // This is the absorbed-`requestLayout` case: `View.requestLayout`
        // stops at the first ancestor already flagged for layout, so a leaf
        // changing size under a flagged intermediate can never rely on
        // propagation reaching a long-lived cache — the next negotiation must
        // re-measure on its own.
        val group = FrameLayout(context())
        val child = CountingMeasurableView(group.context)
        child.setTag(TAG_STRETCH_AXIS, StretchAxis.NONE)
        group.addView(child)

        group.buildSubViewBridges(density = 1f, memos = ProbeMemos()).single()
            .measureForLayout(40f, Float.NaN)

        group.requestLayout() // the flag an absorbed descendant request stops at
        child.answer = ViewDimensionsStruct(SizeStruct(50f, 20f), emptyArray(), emptyArray())

        val dims = group.buildSubViewBridges(density = 1f, memos = ProbeMemos()).single()
            .measureForLayout(40f, Float.NaN)

        assertEquals(2, child.probes.size)
        assertEquals(50f, dims.size.width, 0.001f)
        assertEquals(20f, dims.size.height, 0.001f)
    }

    @Test
    fun oneNegotiationDedupesAcrossRebuiltBridgeSets() {
        // A nested container re-entered by a parent's probe rebuilds its child
        // bridge set for the nested `waterui_layout_*` call; the answers
        // survive because the negotiation store — not the bridge array — owns
        // them. Without that sharing, each probe of a nested group re-runs its
        // subtree's probes and the pass multiplies exponentially in depth.
        val group = FrameLayout(context())
        val child = CountingMeasurableView(group.context)
        child.setTag(TAG_STRETCH_AXIS, StretchAxis.NONE)
        group.addView(child)
        val memos = ProbeMemos()

        group.buildSubViewBridges(density = 1f, memos = memos).single()
            .measureForLayout(40f, Float.NaN)
        group.buildSubViewBridges(density = 1f, memos = memos).single()
            .measureForLayout(40f, Float.NaN)

        assertEquals(1, child.probes.size)
    }

    @Test
    fun theMemoDiesWithTheNegotiation() {
        // The store belongs to one negotiation — built at each
        // `waterui_layout_*` entry and dropped when it returns — never a
        // global or static store that could answer across invalidation.
        val child = CountingMeasurableView(context())
        subviewOf(child, ProbeMemos()).measureForLayout(40f, Float.NaN)
        subviewOf(child, ProbeMemos()).measureForLayout(40f, Float.NaN)

        assertEquals(2, child.probes.size)
    }
}
