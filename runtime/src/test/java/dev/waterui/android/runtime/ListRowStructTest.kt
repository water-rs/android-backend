package dev.waterui.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The JNI side constructs these structs positionally: `ffi/src/jni/convert.rs`
 * hands `ListStruct` the fields of a `WuiList` and `ListItemStruct` the fields
 * of a `WuiListItem`, in declaration order. These tests pin that order, so a
 * Kotlin declaration that drifts from the Rust signature fails here instead
 * of mis-marshalling silently.
 *
 * The members the signature gained are read reflectively so the suite keeps
 * compiling against the old declarations — the failure it was written to
 * catch is the missing constructor, not a compile error.
 */
class ListRowStructTest {
    /**
     * `(JIJJJJJJJZZF)V`: contents, selectionMode, selectionSingle,
     * selectionMultiple, editing, onDelete, onMove, targetIndex,
     * scrollGeneration, usesSections, hasMinRowHeight, minRowHeight.
     */
    private fun listConstructor() = ListStruct::class.java.getDeclaredConstructor(
        Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Float::class.javaPrimitiveType
    )

    /**
     * `(JJZJJZFFFF)V`: content, deletable, hasSection, sectionLabel,
     * sectionFooter, hasInsets, insetTop, insetLeading, insetBottom,
     * insetTrailing.
     */
    private fun listItemConstructor() = ListItemStruct::class.java.getDeclaredConstructor(
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Float::class.javaPrimitiveType,
        Float::class.javaPrimitiveType,
        Float::class.javaPrimitiveType,
        Float::class.javaPrimitiveType
    )

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(instance)
        }

    @Test
    fun listStructRoundTripsTheJniFieldOrder() {
        val list = listConstructor().newInstance(
            11L, // contentsPtr
            1, // selectionMode
            22L, // selectionSinglePtr
            33L, // selectionMultiplePtr
            44L, // editingPtr
            55L, // onDeletePtr
            66L, // onMovePtr
            77L, // targetIndexPtr
            88L, // scrollGenerationPtr
            true, // usesSections
            true, // hasMinRowHeight
            32.5f // minRowHeight
        )

        assertEquals(11L, field(list, "contentsPtr"))
        assertEquals(1, field(list, "selectionMode"))
        assertEquals(22L, field(list, "selectionSinglePtr"))
        assertEquals(33L, field(list, "selectionMultiplePtr"))
        assertEquals(44L, field(list, "editingPtr"))
        assertEquals(55L, field(list, "onDeletePtr"))
        assertEquals(66L, field(list, "onMovePtr"))
        assertEquals(77L, field(list, "targetIndexPtr"))
        assertEquals(88L, field(list, "scrollGenerationPtr"))
        assertEquals(true, field(list, "usesSections"))
        assertEquals(true, field(list, "hasMinRowHeight"))
        assertEquals(32.5f, field(list, "minRowHeight"))
    }

    @Test
    fun listItemStructRoundTripsTheJniFieldOrder() {
        val item = listItemConstructor().newInstance(
            11L, // contentPtr
            22L, // deletablePtr
            true, // hasSection
            33L, // sectionLabelPtr
            44L, // sectionFooterPtr
            true, // hasInsets
            8.0f, // insetTop
            16.0f, // insetLeading
            24.0f, // insetBottom
            32.0f // insetTrailing
        )

        assertEquals(11L, field(item, "contentPtr"))
        assertEquals(22L, field(item, "deletablePtr"))
        assertEquals(true, field(item, "hasSection"))
        assertEquals(33L, field(item, "sectionLabelPtr"))
        assertEquals(44L, field(item, "sectionFooterPtr"))
        assertEquals(true, field(item, "hasInsets"))
        assertEquals(8.0f, field(item, "insetTop"))
        assertEquals(16.0f, field(item, "insetLeading"))
        assertEquals(24.0f, field(item, "insetBottom"))
        assertEquals(32.0f, field(item, "insetTrailing"))
    }

    @Test
    fun absentRowMetricsArriveAsDefaults() {
        val list = listConstructor().newInstance(
            11L, 1, 0L, 0L, 0L, 0L, 0L, 0L, 0L, false, false, 0f
        )
        val item = listItemConstructor().newInstance(
            11L, 0L, false, 0L, 0L, false, 0f, 0f, 0f, 0f
        )

        assertEquals(false, field(list, "hasMinRowHeight"))
        assertEquals(0f, field(list, "minRowHeight"))
        assertEquals(false, field(item, "hasInsets"))
        assertEquals(0f, field(item, "insetTop"))
    }
}
