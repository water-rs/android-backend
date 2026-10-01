package dev.waterui.android.components

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import dev.waterui.android.layout.PassThroughFrameLayout
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A rect carrying `.offset().rotation().opacity().position_in(...)` must not
 * grow a ViewGroup per modifier: transforms fold onto the view's own
 * properties and opacity folds onto a group-compositing host, so the chain
 * adds zero views beyond the layout containers that measure it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModifierFoldTest {

    @Test
    fun opacityFoldsOntoWaterUIContainersAndLeaves() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // WaterUI's own containers carry the group-alpha mechanism — the
        // modifier returns the child itself, no wrapper.
        val passThrough = PassThroughFrameLayout(context)
        assertSame(passThrough, opacityHostFor(context, passThrough))

        val leaf = View(context)
        assertSame(leaf, opacityHostFor(context, leaf))
    }

    @Test
    fun opacityWrapsOnlyForeignViewGroups() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // A foreign ViewGroup cannot group-composite its subtree — that is
        // the one case that still earns a wrapper, and it is one wrapper for
        // the whole foreign subtree, not one per modifier.
        val foreign = ScrollView(context)
        val host = opacityHostFor(context, foreign)
        assertTrue(host is OpacityLayout)
        assertSame(foreign, (host as ViewGroup).getChildAt(0))
    }
}
