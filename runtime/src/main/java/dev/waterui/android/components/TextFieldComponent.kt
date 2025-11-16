package dev.waterui.android.components

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.addTextChangedListener
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.register
import dev.waterui.android.runtime.toTypeId

private val textFieldTypeId: WuiTypeId by lazy { NativeBindings.waterui_text_field_id().toTypeId() }

private const val KEYBOARD_TEXT = 0
private const val KEYBOARD_SECURE = 1
private const val KEYBOARD_EMAIL = 2
private const val KEYBOARD_URL = 3
private const val KEYBOARD_NUMBER = 4
private const val KEYBOARD_PHONE = 5

private val textFieldRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_text_field(node.rawPtr)
    val binding = WuiBinding.str(struct.valuePtr, env)
    val promptComputed = struct.promptPtr.takeIf { it != 0L }?.let { WuiComputed.styledString(it, env) }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView)

    val editText = AppCompatEditText(context).apply {
        inputType = resolveInputType(struct.keyboardType)
        if (struct.keyboardType == KEYBOARD_SECURE) {
            transformationMethod = PasswordTransformationMethod.getInstance()
        } else {
            transformationMethod = null
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    container.addView(editText)

    var updating = false
    binding.observe { value ->
        val current = editText.text?.toString().orEmpty()
        if (current != value) {
            updating = true
            editText.setText(value)
            editText.setSelection(value.length)
            updating = false
        }
    }

    editText.addTextChangedListener { text ->
        if (!updating) {
            binding.set(text?.toString().orEmpty())
        }
    }

    promptComputed?.observe { prompt ->
        editText.hint = prompt.toCharSequence(env)
    }

    container.disposeWith(binding)
    promptComputed?.let { container.disposeWith(it) }
    container
}

private fun resolveInputType(keyboardType: Int): Int =
    when (keyboardType) {
        KEYBOARD_SECURE -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        KEYBOARD_EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        KEYBOARD_URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        KEYBOARD_NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        KEYBOARD_PHONE -> InputType.TYPE_CLASS_PHONE
        else -> InputType.TYPE_CLASS_TEXT
    }

internal fun MutableMap<WuiTypeId, WuiRenderer>.registerWuiTextField() {
    register({ textFieldTypeId }, textFieldRenderer)
}
