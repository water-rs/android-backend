package dev.waterui.android.components

import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.card.MaterialCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The list-level selection contract the FFI carries: native pointer and
 * keyboard input writes the binding, the binding's publications repaint the
 * touched rows, and the assistive select action writes through — with no copy
 * of the state to drift and no write-back when the binding moves.
 *
 * The channels are in-memory fakes because `WuiBinding`'s watcher bridge reads
 * animation metadata through JNI, which a JVM test cannot reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ListSelectionTest {

    /** A selection channel backed by a variable — the fake `WuiBinding`. */
    private class FakeChannel<T>(initial: T) : ListSelectionChannel<T> {
        var value = initial
        var onChange: ((T) -> Unit)? = null
        val writes = mutableListOf<T>()
        var closed = false

        override fun set(value: T) {
            writes.add(value)
            if (value != this.value) {
                this.value = value
                onChange?.invoke(value)
            }
        }

        override fun watch(onChange: (T) -> Unit) {
            this.onChange = onChange
            onChange(value)
        }

        /** An external publication — a write that came from the Rust side. */
        fun push(value: T) {
            value.also { this.value = it }
            onChange?.invoke(value)
        }

        override fun close() {
            closed = true
        }
    }

    private val ids = intArrayOf(10, 11, 12, 13, 14)

    private fun selection(
        mode: Int,
        single: FakeChannel<Int>? = null,
        multiple: FakeChannel<IntArray>? = null,
    ): ListSelection = ListSelection(mode, { ids }, single, multiple)

    @Test
    fun singleModePublishesCurrentAndWritesOnActivate() {
        val single = FakeChannel(0)
        val changes = mutableListOf<Set<Int>>()
        val selection = selection(ListSelection.MODE_SINGLE, single = single)
            .apply { onChanged = changes::add }

        assertTrue(selection.isEnabled)
        assertEquals(emptySet<Int>(), selection.selectedIds)

        selection.activate(12)
        assertEquals(listOf(12), single.writes)

        // The write's echo repaints the touched row.
        assertEquals(listOf(setOf(12)), changes)
        assertTrue(selection.isSelected(12))

        // A second tap selects — single mode never toggles a row off.
        selection.activate(14)
        assertEquals(listOf(12, 14), single.writes)
        assertEquals(listOf(setOf(12), setOf(12, 14)), changes)
    }

    @Test
    fun bindingWritesReflectWithoutEcho() {
        val single = FakeChannel(0)
        val changes = mutableListOf<Set<Int>>()
        val selection = selection(ListSelection.MODE_SINGLE, single = single)
            .apply { onChanged = changes::add }

        single.push(11)
        assertEquals(setOf(11), selection.selectedIds)
        assertEquals(listOf(setOf(11)), changes)

        // Binding-driven application never writes the binding back.
        assertEquals(emptyList<Int>(), single.writes)

        single.push(0)
        assertEquals(emptySet<Int>(), selection.selectedIds)
        assertEquals(listOf(setOf(11), setOf(11)), changes)
    }

    @Test
    fun multipleModeTapTogglesAndShiftExtends() {
        val multiple = FakeChannel(intArrayOf())
        val selection = selection(ListSelection.MODE_MULTIPLE, multiple = multiple)

        // Plain activation toggles — Android's CHOICE_MODE_MULTIPLE gesture.
        selection.activate(11)
        selection.activate(13)
        assertEquals(2, multiple.writes.size)
        assertTrue(selection.isSelected(11))
        assertTrue(selection.isSelected(13))

        // Toggling a selected row removes it; the anchor follows plain taps.
        selection.activate(11)
        assertFalse(selection.isSelected(11))

        // Shift extends the range from the last plain interaction, anchor to id.
        selection.activate(11)
        selection.activate(14, KeyEvent.META_SHIFT_ON)
        assertEquals(
            intArrayOf(11, 12, 13, 14).toList(),
            multiple.writes.last().toList()
        )

        // A following plain tap toggles too — CHOICE_MODE_MULTIPLE has no
        // replace gesture; Shift is the only way to set a range.
        selection.activate(10)
        assertEquals(intArrayOf(11, 12, 13, 14, 10).toList(), multiple.writes.last().toList())
    }

    @Test
    fun disabledModeIgnoresActivation() {
        val selection = selection(ListSelection.MODE_NONE)
        assertFalse(selection.isEnabled)
        selection.activate(11)
        assertEquals(emptySet<Int>(), selection.selectedIds)
    }

    @Test
    fun modeAndSlotAgreementIsChecked() {
        val single = FakeChannel(0)
        var threw = false
        try {
            selection(ListSelection.MODE_MULTIPLE, single = single)
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun closeReleasesTheBindings() {
        val single = FakeChannel(0)
        val selection = selection(ListSelection.MODE_SINGLE, single = single)
        selection.close()
        assertTrue(single.closed)
    }

    @Test
    fun rowPublishesSelectedStateToTheAccessibilityNode() {
        val card = MaterialCardView(
            ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                com.google.android.material.R.style.Theme_Material3_DayNight
            )
        ).apply { isCheckable = true }
        bindListRowAccessibility(card, selection(ListSelection.MODE_NONE)) { 11 }
        applyListRowSelectedState(card, true)
        assertTrue(card.isChecked)
        assertTrue(card.isActivated)
        assertTrue(card.isSelected)
        assertTrue(populateNode(card).isSelected)

        applyListRowSelectedState(card, false)
        assertFalse(populateNode(card).isSelected)
    }

    /** The populate path the framework drives: delegate → node info. */
    private fun populateNode(card: MaterialCardView): AccessibilityNodeInfoCompat {
        val info = AccessibilityNodeInfoCompat.wrap(AccessibilityNodeInfo())
        checkNotNull(ViewCompat.getAccessibilityDelegate(card)) {
            "row has no accessibility delegate to populate its node"
        }.onInitializeAccessibilityNodeInfo(card, info)
        return info
    }

    @Test
    fun assistiveSelectActionWritesTheBinding() {
        val single = FakeChannel(0)
        val selection = selection(ListSelection.MODE_SINGLE, single = single)
        val card = MaterialCardView(
            ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                com.google.android.material.R.style.Theme_Material3_DayNight
            )
        )
        var rowId = 11
        bindListRowAccessibility(card, selection) { rowId }

        card.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null)
        assertEquals(listOf(11), single.writes)

        // The node re-publishes for a different bound row without re-install.
        rowId = 13
        card.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null)
        assertEquals(listOf(11, 13), single.writes)
    }

    @Test
    fun assistiveSelectIsInertWithoutASelectionMode() {
        val selection = selection(ListSelection.MODE_NONE)
        val card = MaterialCardView(
            ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                com.google.android.material.R.style.Theme_Material3_DayNight
            )
        )
        bindListRowAccessibility(card, selection) { 11 }
        card.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SELECT, null)
        assertEquals(emptySet<Int>(), selection.selectedIds)
    }
}
