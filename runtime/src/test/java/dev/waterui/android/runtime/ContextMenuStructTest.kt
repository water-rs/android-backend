package dev.waterui.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The JNI side constructs these structs positionally: `ffi/src/jni/convert.rs`
 * hands `MenuItemStruct` the fields of a `WuiMenuItem` and
 * `MetadataContextMenuStruct` the fields of a `WuiContextMenu`, in declaration
 * order. These tests pin that order, so a Kotlin declaration that drifts from
 * the Rust signature fails here instead of mis-marshalling silently.
 *
 * The members the signature gained are read reflectively so the suite keeps
 * compiling against the old declarations — the failure it was written to
 * catch is the missing constructor, not a compile error.
 */
class ContextMenuStructTest {
    /**
     * `(IJJJJLjava/lang/String;ZZZZILjava/lang/String;J)V`: tag, label, action,
     * disabled, selected, keyEquivalent, command, shift, option, control,
     * role, subtitle, items.
     */
    private fun menuItemConstructor() = MenuItemStruct::class.java.getDeclaredConstructor(
        Int::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        String::class.java,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        String::class.java,
        Long::class.javaPrimitiveType
    )

    /** `(JJJJJ)V`: content, items, preview, accessory, dismissRequests. */
    private fun metadataConstructor() =
        MetadataContextMenuStruct::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType
        )

    private fun field(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(instance)
        }

    @Test
    fun menuItemStructRoundTripsTheJniFieldOrder() {
        val item = menuItemConstructor().newInstance(
            MenuItemTag.COMMAND.value, // tag
            11L, // labelPtr
            22L, // actionPtr
            33L, // disabledPtr
            44L, // selectedPtr
            "k", // keyEquivalent
            true, // command
            false, // shift
            true, // option
            false, // control
            1, // role: destructive
            "cannot be undone", // subtitle
            55L // itemsPtr
        )

        assertEquals(MenuItemTag.COMMAND.value, field(item, "tag"))
        assertEquals(11L, field(item, "labelPtr"))
        assertEquals(22L, field(item, "actionPtr"))
        assertEquals(33L, field(item, "disabledPtr"))
        assertEquals(44L, field(item, "selectedPtr"))
        assertEquals("k", field(item, "keyEquivalent"))
        assertEquals(true, field(item, "command"))
        assertEquals(false, field(item, "shift"))
        assertEquals(true, field(item, "option"))
        assertEquals(false, field(item, "control"))
        assertEquals(1, field(item, "role"))
        assertEquals("cannot be undone", field(item, "subtitle"))
        assertEquals(55L, field(item, "itemsPtr"))
    }

    @Test
    fun metadataContextMenuStructRoundTripsTheJniFieldOrder() {
        val metadata = metadataConstructor().newInstance(11L, 22L, 33L, 44L, 55L)

        assertEquals(11L, field(metadata, "contentPtr"))
        assertEquals(22L, field(metadata, "itemsPtr"))
        assertEquals(33L, field(metadata, "previewPtr"))
        assertEquals(44L, field(metadata, "accessoryPtr"))
        assertEquals(55L, field(metadata, "dismissRequestsPtr"))
    }

    @Test
    fun absentContextMenuMembersArriveAsZeroPointers() {
        val metadata = metadataConstructor().newInstance(11L, 22L, 0L, 0L, 0L)

        assertEquals(0L, field(metadata, "previewPtr"))
        assertEquals(0L, field(metadata, "accessoryPtr"))
        assertEquals(0L, field(metadata, "dismissRequestsPtr"))
    }

    @Test
    fun menuItemStructAdmitsAbsentOptionalMembers() {
        val item = menuItemConstructor().newInstance(
            MenuItemTag.DIVIDER.value,
            0L, 0L, 0L, 0L,
            null,
            false, false, false, false,
            0,
            null,
            0L
        )

        assertEquals(0, field(item, "role"))
        assertNull(field(item, "subtitle"))
    }
}
