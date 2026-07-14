package dev.waterui.android.components

internal class TextInputBindingSynchronizer(
    private val currentEditorValue: () -> String,
    private val replaceEditorValue: (String) -> Unit,
    private val updateBinding: (String) -> Unit
) {
    private var applyingBindingValue = false

    fun bindingChanged(value: String) {
        if (currentEditorValue() == value) return

        applyingBindingValue = true
        replaceEditorValue(value)
        applyingBindingValue = false
    }

    fun editorChanged(value: String) {
        if (!applyingBindingValue) {
            updateBinding(value)
        }
    }
}
