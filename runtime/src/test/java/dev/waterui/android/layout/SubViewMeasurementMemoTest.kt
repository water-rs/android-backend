package dev.waterui.android.layout

import android.app.Activity
import android.content.Context
import android.view.View
import dev.waterui.android.runtime.ProposalStruct
import dev.waterui.android.runtime.SizeStruct
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.SubViewStruct
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
 * The measurement memo a `SubViewStruct` bridge object carries.
 *
 * Rust layout containers probe their children freely — ideal, minimum, and
 * allocated offers — and a probe repeated under the same proposal must return
 * the remembered answer instead of measuring the child again. Without the
 * memo every probe of a nested layout re-measures its whole subtree, which is
 * the exponential measurement storm that made `edge_layout` ANR on the main
 * thread. The memo is keyed on the proposal's raw bits — NaN, the infinities,
 * and signed zero are distinct proposals — and it dies with the bridge object.
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

        override fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
            probes.add(proposal)
            return answer
        }
    }

    private fun subviewOf(child: View) =
        SubViewStruct(view = child, stretchAxis = StretchAxis.NONE, density = 1f)

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
            override fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
                try {
                    subview.measureForLayout(proposal.width, proposal.height)
                } catch (e: IllegalStateException) {
                    recursiveError = e
                }
                return ViewDimensionsStruct(SizeStruct(10f, 10f), emptyArray(), emptyArray())
            }
        }
        subview = SubViewStruct(view = child, stretchAxis = StretchAxis.NONE, density = 1f)

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
            override fun measureForLayout(proposal: ProposalStruct): ViewDimensionsStruct {
                if (!entered && proposal.width == 40f) {
                    entered = true
                    innerAnswer = subview.measureForLayout(20f, proposal.height)
                }
                return ViewDimensionsStruct(SizeStruct(10f, 10f), emptyArray(), emptyArray())
            }
        }
        subview = SubViewStruct(view = child, stretchAxis = StretchAxis.NONE, density = 1f)

        subview.measureForLayout(40f, Float.NaN)

        assertTrue(innerAnswer != null)
    }

    @Test
    fun theMemoDiesWithTheBridgeObject() {
        // The cache belongs to the bridge object a container hands Rust for a
        // negotiation — rebuilt when the child set or its content invalidates —
        // never a global or static store that could answer across invalidation.
        val child = CountingMeasurableView(context())
        subviewOf(child).measureForLayout(40f, Float.NaN)
        subviewOf(child).measureForLayout(40f, Float.NaN)

        assertEquals(2, child.probes.size)
    }
}
