package dev.waterui.android.components

import android.app.Activity
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composed context-menu presentation: when a context menu carries a
 * preview or an accessory the platform [androidx.appcompat.widget.PopupMenu]
 * cannot host it (it would dismiss on the first accessory touch), so
 * [ContextMenuPresentation] lifts the anchor over a scrim, renders the command
 * rows itself, and floats the accessory in a second [PopupWindow].
 *
 * These tests drive that surface with the seams it exposes: a fake menu
 * source stands in for the Rust menu stream, a fake [DismissSignal] stands in
 * for `Computed<i32>` dismiss requests, and the popup factory records every
 * [PopupWindow] the presentation opens so their dismissal can be observed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContextMenuPresentationTest {
    private companion object {
        const val ANCHOR_WIDTH = 200
        const val ANCHOR_HEIGHT = 100
        const val ERROR_COLOR = 0xFFB00020.toInt()
        const val SECONDARY_COLOR = 0xFF666666.toInt()
    }

    private fun activity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(com.google.android.material.R.style.Theme_Material3_DayNight)
        }

    /** A menu source that plants fixed titles in the [Menu] it is handed. */
    private class FakeMenuSource(private val titles: List<CharSequence>) : ContextMenuSource {
        override var onRebuilt: (() -> Unit)? = null
        var bound = false
            private set
        var selectedItemId = -1
            private set

        override val isEmpty: Boolean
            get() = titles.isEmpty()

        override fun bind(menu: Menu, context: android.content.Context) {
            bound = true
            titles.forEachIndexed { index, title ->
                menu.add(Menu.NONE, index, Menu.NONE, title)
            }
        }

        override fun unbind() {
            bound = false
        }

        override fun onMenuItemSelected(itemId: Int): Boolean {
            selectedItemId = itemId
            return true
        }
    }

    private class FakeDismissSignal : DismissSignal {
        private val watchers = mutableListOf<() -> Unit>()

        override fun watch(onChange: () -> Unit) {
            watchers += onChange
        }

        fun fire() = watchers.toList().forEach { it() }
    }

    private fun collectViews(root: View): List<View> {
        val all = mutableListOf<View>()
        fun walk(view: View) {
            all += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index))
                }
            }
        }
        walk(root)
        return all
    }

    private fun menuTexts(menuPopup: PopupWindow): List<TextView> =
        collectViews(menuPopup.contentView).filterIsInstance<TextView>()

    private fun showMenu(
        activity: Activity,
        titles: List<CharSequence>,
        accessory: View? = View(activity),
        signal: FakeDismissSignal = FakeDismissSignal()
    ): Triple<ContextMenuPresentation, FakeMenuSource, List<PopupWindow>> {
        val anchor = View(activity)
        activity.setContentView(anchor, ViewGroup.LayoutParams(ANCHOR_WIDTH, ANCHOR_HEIGHT))
        anchor.layout(0, 400, ANCHOR_WIDTH, 400 + ANCHOR_HEIGHT)

        val source = FakeMenuSource(titles)
        val popups = mutableListOf<PopupWindow>()
        val presentation = ContextMenuPresentation(
            anchor = anchor,
            source = source,
            previewView = { null },
            accessoryView = { accessory },
            popupFactory = { view, width, height, focusable ->
                PopupWindow(view, width, height, focusable).also(popups::add)
            }
        )
        // The renderer wires the channel exactly like this: every change
        // dismisses both windows.
        signal.watch(presentation::dismiss)
        assertTrue(presentation.show())
        return Triple(presentation, source, popups)
    }

    @Test
    fun dismissRequestClosesTheMenuAndTheAccessory() {
        val signal = FakeDismissSignal()
        val (presentation, _, popups) = showMenu(
            activity(),
            listOf("Rename", "Delete"),
            signal = signal
        )
        assertEquals("menu plus accessory popups", 2, popups.size)
        assertTrue(popups.all { it.isShowing })

        signal.fire()

        assertFalse(presentation.isShowing)
        assertTrue(popups.none { it.isShowing })
    }

    @Test
    fun everyDismissRequestChangeClosesAgain() {
        val signal = FakeDismissSignal()
        val (presentation, _, popups) = showMenu(activity(), listOf("Rename"), signal = signal)

        signal.fire()
        assertFalse(presentation.isShowing)

        assertTrue(presentation.show())
        val reopened = popups.drop(2)
        assertEquals(2, reopened.size)
        assertTrue(reopened.all { it.isShowing })

        signal.fire()
        assertFalse(presentation.isShowing)
        assertTrue(popups.none { it.isShowing })
    }

    @Test
    fun choosingACommandClosesTheMenuAndTheAccessory() {
        val (presentation, source, popups) = showMenu(activity(), listOf("Rename", "Delete"))
        val title = menuTexts(popups.first()).first { it.text.toString() == "Rename" }
        val row = title.parent as View

        row.performClick()

        assertEquals("the command's item id reaches the source", 0, source.selectedItemId)
        assertFalse(presentation.isShowing)
        assertTrue(popups.none { it.isShowing })
    }

    @Test
    fun accessoryInteractionLeavesTheMenuOpen() {
        val activity = activity()
        var accessoryTapped = false
        val accessory = View(activity).apply {
            setOnClickListener { accessoryTapped = true }
        }
        val (presentation, _, popups) = showMenu(
            activity,
            listOf("Rename"),
            accessory = accessory
        )

        accessory.performClick()

        assertTrue(accessoryTapped)
        assertTrue(presentation.isShowing)
        assertTrue(popups.all { it.isShowing })
    }

    @Test
    fun destructiveCommandRendersInTheErrorColour() {
        val (_, _, popups) = showMenu(
            activity(),
            listOf(
                composeMenuItemTitle("Rename", null, null, SECONDARY_COLOR),
                composeMenuItemTitle("Delete", null, ERROR_COLOR, SECONDARY_COLOR)
            )
        )
        val title = menuTexts(popups.first()).first { it.text.toString() == "Delete" }
        val spanned = title.text as Spanned

        val span = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
            .firstOrNull()

        assertNotNull("the destructive command carries the error colour", span)
        assertEquals(ERROR_COLOR, span!!.foregroundColor)
    }

    @Test
    fun subtitleRendersAsASecondarySecondLine() {
        val (_, _, popups) = showMenu(
            activity(),
            listOf(composeMenuItemTitle("Share", "sends a copy", null, SECONDARY_COLOR))
        )
        val title = menuTexts(popups.first()).first { it.text.toString().startsWith("Share") }
        val spanned = title.text as Spanned

        assertEquals("Share\nsends a copy", spanned.toString())
        val subtitleStart = spanned.indexOf('\n') + 1
        val colour = spanned.getSpans(subtitleStart, spanned.length, ForegroundColorSpan::class.java)
            .firstOrNull()
        assertNotNull("the subtitle carries the secondary colour", colour)
        assertEquals(SECONDARY_COLOR, colour!!.foregroundColor)
        val size = spanned.getSpans(subtitleStart, spanned.length, RelativeSizeSpan::class.java)
            .firstOrNull()
        assertNotNull("the subtitle renders smaller than the title", size)
        assertTrue(size!!.sizeChange < 1f)
    }

    @Test
    fun labelAloneKeepsItsText() {
        val rendered = composeMenuItemTitle("Rename", null, null, SECONDARY_COLOR)

        assertEquals("Rename", rendered.toString())
    }

    @Test
    fun anAccessoryWiderThanTheScreenStillShows() {
        val activity = activity()
        val accessory = object : View(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                // An accessory that asks for far more than the display width
                // must not crash the anchoring maths; it clamps to the screen.
                setMeasuredDimension(4000, 120)
            }
        }

        val (presentation, _, popups) = showMenu(activity, listOf("Rename"), accessory = accessory)

        assertTrue(presentation.isShowing)
        assertTrue(popups.all { it.isShowing })
    }

    @Test
    fun anEmptyMenuShowsNothing() {
        val activity = activity()
        val anchor = View(activity)
        activity.setContentView(anchor, ViewGroup.LayoutParams(ANCHOR_WIDTH, ANCHOR_HEIGHT))
        val presentation = ContextMenuPresentation(
            anchor = anchor,
            source = FakeMenuSource(emptyList()),
            previewView = { null },
            accessoryView = { View(activity) }
        )

        assertFalse(presentation.show())
        assertFalse(presentation.isShowing)
    }
}
