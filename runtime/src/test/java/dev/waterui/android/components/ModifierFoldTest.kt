package dev.waterui.android.components

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.FoldedTransform
import org.junit.Assert.assertNotSame
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

    @Test
    fun repeatedOpacityComposesAcrossDistinctHosts() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // `.opacity(0.5).opacity(0.5)` must end at 0.25: the second modifier
        // may not fold onto the view whose alpha the first already owns, or
        // the two subscriptions would write the same channel and overwrite
        // each other.
        val leaf = View(context)
        val inner = opacityHostFor(context, leaf)
        val outer = opacityHostFor(context, inner)
        assertNotSame(inner, outer)
        assertTrue(outer is OpacityLayout)
        assertSame(inner, (outer as ViewGroup).getChildAt(0))
    }

    @Test
    fun opacityWrapsAChildWhoseAlphaIsOwned() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // `.shadow().opacity()`: an elevation shadow is projected by the
        // parent render node outside the subtree's compositing, so folding
        // the fade onto the caster would leave the shadow undimmed. The
        // opacity owns a wrapper's alpha instead.
        val shadowed = shadowHostFor(context, View(context))
        assertNotSame(shadowed, opacityHostFor(context, shadowed))
    }

    @Test
    fun opacityFoldsOntoAChildPaintingItsOwnContent() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // `.border().opacity()`: the border's foreground drawable and a
        // subclass's post-children paint ride *inside* the group-alpha layer
        // now that compositing wraps the complete draw — so the fade folds
        // onto the child and dims everything it paints.
        val bordered = PassThroughFrameLayout(context)
        bordered.foreground = ColorDrawable(Color.BLACK)
        assertSame(bordered, opacityHostFor(context, bordered))
    }

    @Test
    fun borderFoldsOnlyOntoAnUnownedChild() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // `.offset().border()`: folding the stroke onto the transformed view
        // would move the border with the content; the border belongs on the
        // frame the modifier occupies, so the child keeps a wrapper.
        val transformed = PassThroughFrameLayout(context)
        FoldedTransform.on(transformed).addOffset()
        val transformedHost =
            borderHostFor(context, transformed, width = 1f, cornerRadius = 0f, edges = 15)
        assertNotSame(transformed, transformedHost)
        assertSame(transformed, (transformedHost as ViewGroup).getChildAt(0))

        // `.opacity().border()`: the border is outside the fade and must not
        // be dimmed by it.
        val opacityOwned = opacityHostFor(context, View(context))
        assertNotSame(
            opacityOwned,
            borderHostFor(context, opacityOwned, width = 1f, cornerRadius = 0f, edges = 15)
        )

        // A child carrying no inner border, transform or opacity still takes
        // the foreground fold — the optimization holds where order is
        // equivalent.
        val clean = View(context)
        assertSame(
            clean,
            borderHostFor(context, clean, width = 1f, cornerRadius = 0f, edges = 15)
        )
    }

    @Test
    fun repeatedShadowOwnsDistinctHosts() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // Elevation, the outline provider and the shadow colors are
        // single-owner slots: a second `.shadow` must cast its own shadow on
        // a wrapper rather than overwrite the inner one.
        val leaf = View(context)
        val inner = shadowHostFor(context, leaf)
        val outer = shadowHostFor(context, inner)
        assertNotSame(inner, outer)
        assertSame(inner, (outer as ViewGroup).getChildAt(0))
    }

    @Test
    fun shadowWrapsAChildCarryingInnerEffects() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // `.offset(...).shadow(...)`: the shadow evaluates its silhouette in
        // the frame the modifier occupies — folding it onto the transformed
        // child would let the inner transform move and shape the shadow.
        val transformed = PassThroughFrameLayout(context)
        FoldedTransform.on(transformed).addOffset()
        val shadowOverTransform = shadowHostFor(context, transformed)
        assertNotSame(transformed, shadowOverTransform)
        assertSame(transformed, (shadowOverTransform as ViewGroup).getChildAt(0))

        // `.opacity(...).shadow(...)`: folding the shadow onto the faded
        // child would make the inner alpha fade the outer shadow.
        val opacityOwned = opacityHostFor(context, View(context))
        assertNotSame(opacityOwned, shadowHostFor(context, opacityOwned))

        // A child already carrying native elevation keeps it — folding the
        // metadata shadow on top would overwrite those slots.
        val elevated = View(context).apply { elevation = 8f }
        assertNotSame(elevated, shadowHostFor(context, elevated))
    }

    @Test
    fun shadowStillFoldsOntoAnUnownedChild() {
        val context = Robolectric.buildActivity(Activity::class.java).get()

        // `.border().shadow()` stays folded: the silhouette the metadata
        // carries is evaluated in the child's frame and the foreground
        // border does not transform or fade it — the optimization holds
        // where order is equivalent.
        val bordered = PassThroughFrameLayout(context)
        bordered.foreground = ColorDrawable(Color.BLACK)
        assertSame(bordered, shadowHostFor(context, bordered))

        val clean = View(context)
        assertSame(clean, shadowHostFor(context, clean))
    }
}
