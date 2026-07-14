package dev.waterui.android.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInputBindingSynchronizerTest {
    @Test
    fun secureFieldBindingValuesUpdateEditorWithoutEchoingIntoBinding() {
        var editorValue = ""
        val bindingWrites = mutableListOf<String>()
        lateinit var synchronizer: TextInputBindingSynchronizer
        synchronizer = TextInputBindingSynchronizer(
            currentEditorValue = { editorValue },
            replaceEditorValue = { value ->
                editorValue = value
                synchronizer.editorChanged(value)
            },
            updateBinding = bindingWrites::add
        )

        synchronizer.bindingChanged("initial")
        synchronizer.bindingChanged("external")

        assertEquals("external", editorValue)
        assertEquals(emptyList<String>(), bindingWrites)

        synchronizer.editorChanged("typed")

        assertEquals(listOf("typed"), bindingWrites)
    }
}
