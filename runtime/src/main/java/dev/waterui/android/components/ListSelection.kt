package dev.waterui.android.components

import android.view.KeyEvent
import dev.waterui.android.reactive.WuiBinding
import java.io.Closeable

/**
 * The half of an FFI binding a list's selection channel needs: read the
 * current value through [watch] (which delivers it first), write a native
 * interaction through [set], and release the handle through [close].
 *
 * A channel hides the `WuiBinding` the selection rides on so the state
 * machine is drivable from JVM tests, the way `TextInputBindingSynchronizer`
 * hides its binding behind lambdas.
 */
internal interface ListSelectionChannel<T> {
    fun set(value: T)

    /** Delivers the current value, then every subsequent change. */
    fun watch(onChange: (T) -> Unit)

    fun close()
}

private class WuiBindingChannel<T>(private val binding: WuiBinding<T>) :
    ListSelectionChannel<T> {
    override fun set(value: T) {
        binding.set(value)
    }

    override fun watch(onChange: (T) -> Unit) {
        binding.observe(onChange)
    }

    override fun close() {
        binding.close()
    }
}

/**
 * The selection a `WuiList` carries — the mode the authoring API picked and
 * the binding it projects into.
 *
 * The FFI hands the list an erased-id `Binding<i32>` for `Binding<Option<id>>`
 * (0 is "nothing selected") and an erased-id-vector binding for
 * `Binding<Set<id>>`; mode and slot are paired and may never disagree. Row
 * interactions write through the binding and the binding's own echo is what
 * repaints rows — there is no local copy to keep in sync and no loop.
 */
internal class ListSelection(
    private val mode: Int,
    private val rowIds: () -> IntArray,
    private val single: ListSelectionChannel<Int>?,
    private val multiple: ListSelectionChannel<IntArray>?
) : Closeable {
    constructor(
        mode: Int,
        rowIds: () -> IntArray,
        singleBinding: WuiBinding<Int>?,
        multipleBinding: WuiBinding<IntArray>?
    ) : this(
        mode,
        rowIds,
        singleBinding?.let(::WuiBindingChannel),
        multipleBinding?.let(::WuiBindingChannel)
    )

    init {
        check((single != null) == (mode == MODE_SINGLE)) {
            "WaterUI List selection mode $mode and its single-selection binding disagree"
        }
        check((multiple != null) == (mode == MODE_MULTIPLE)) {
            "WaterUI List selection mode $mode and its multi-selection binding disagree"
        }
    }

    /** Rows can be interacted with only in a selection mode. */
    val isEnabled: Boolean = mode != MODE_NONE

    /**
     * The selected rows' erased ids — the value the binding last published.
     * Rows read this when binding so a repaint never has to consult the FFI.
     */
    var selectedIds: Set<Int> = emptySet()
        private set

    /**
     * The row a Shift range extends from — the last row a plain interaction
     * wrote, the same anchor the hydrolysis write path keeps.
     */
    private var anchor: Int? = null

    /**
     * The ids whose selected state just flipped, delivered on every binding
     * read. The adapter repaints exactly those rows.
     */
    var onChanged: ((Set<Int>) -> Unit)? = null

    init {
        single?.watch { value -> apply(if (value != 0) setOf(value) else emptySet()) }
        multiple?.watch { value -> apply(value.toSet()) }
    }

    fun isSelected(id: Int): Boolean = id in selectedIds

    /**
     * A pointer or keyboard activation on row [id].
     *
     * Single mode selects outright. Multi mode follows the platform's choice
     * convention: a plain activation toggles the row, the way
     * `AbsListView.CHOICE_MODE_MULTIPLE` toggles each tapped row, and Shift —
     * reachable with a hardware keyboard or stylus — extends the range from
     * the anchor instead of moving it. Both write through the binding; the
     * binding's echo repaints.
     */
    fun activate(id: Int, metaState: Int = 0) {
        when (mode) {
            MODE_NONE -> return
            MODE_SINGLE -> single?.set(id)
            else -> {
                if (metaState and KeyEvent.META_SHIFT_ON != 0) {
                    writeRange(id)
                } else {
                    toggle(id)
                    anchor = id
                }
            }
        }
    }

    private fun toggle(id: Int) {
        multiple?.set(
            if (id in selectedIds) {
                (selectedIds - id).toIntArray()
            } else {
                (selectedIds + id).toIntArray()
            }
        )
    }

    private fun writeRange(id: Int) {
        val ids = rowIds()
        val row = ids.indexOf(id)
        if (row < 0) return
        val anchorRow = anchor?.let { ids.indexOf(it).takeIf { index -> index >= 0 } } ?: 0
        val start = minOf(anchorRow, row)
        val end = maxOf(anchorRow, row)
        multiple?.set(ids.sliceArray(start..end))
    }

    private fun apply(next: Set<Int>) {
        val changed = (selectedIds union next) - (selectedIds intersect next)
        selectedIds = next
        if (changed.isNotEmpty()) {
            onChanged?.invoke(changed)
        }
    }

    override fun close() {
        onChanged = null
        single?.close()
        multiple?.close()
    }

    companion object {
        /** `WuiListSelectionMode_None`. */
        const val MODE_NONE = 0

        /** `WuiListSelectionMode_Single`. */
        const val MODE_SINGLE = 1

        /** `WuiListSelectionMode_Multiple`. */
        const val MODE_MULTIPLE = 2
    }
}
