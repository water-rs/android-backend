package dev.waterui.android.components

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.widget.addTextChangedListener
import dev.waterui.android.layout.AxisExpandingLinearLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiTextInputFocusTarget
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.installWuiFocusTarget
import dev.waterui.android.runtime.toColorInt
import java.io.Closeable

private val textFieldTypeId: WuiTypeId by lazy { NativeBindings.waterui_text_field_id().toTypeId() }

private const val KEYBOARD_TEXT = 0
private const val KEYBOARD_EMAIL = 1
private const val KEYBOARD_URL = 2
private const val KEYBOARD_NUMBER = 3
private const val KEYBOARD_PHONE = 4

private val textFieldRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_text_field(node.rawPtr)
    val binding = WuiBinding.styledPlain(struct.valuePtr)
    val promptComputed = WuiComputed.styledString(struct.promptPtr)
    val promptAlignment = WuiComputed.horizontalAlignment(struct.promptAlignmentPtr)

    val container = AxisExpandingLinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView)

    val singleLine = struct.lineLimit == 1
    val input = createMaterialTextInput(context)
    val editText = input.editText.apply {
        inputType = resolveInputType(struct.keyboardType, singleLine)
        // `lineLimit == 0` means the field has no limit; anything else caps the
        // visible lines. A single-line field also suppresses the Enter key.
        isSingleLine = singleLine
        if (struct.lineLimit > 0) {
            maxLines = struct.lineLimit
        }
    }
    editText.installWuiFocusTarget(WuiTextInputFocusTarget(editText))
    installTextSelectionMenu(editText, struct.selectionMenuPtr, env)
    container.addView(input.layout)

    val bindingSynchronizer = TextInputBindingSynchronizer(
        currentEditorValue = {
            requireNotNull(editText.text) { "TextField editor returned null text" }.toString()
        },
        replaceEditorValue = { value ->
            editText.setText(value)
            editText.setSelection(value.length)
        },
        updateBinding = binding::set
    )
    binding.observe(bindingSynchronizer::bindingChanged)

    val textWatcher = editText.addTextChangedListener { text ->
        val value = requireNotNull(text) { "TextField text watcher received null text" }
        bindingSynchronizer.editorChanged(value.toString())
    }

    var promptBinding: Closeable? = null
    promptComputed.observe { prompt ->
        promptBinding?.close()
        promptBinding = prompt.bind(env) { resolved -> editText.hint = resolved }
    }
    promptAlignment.observe { alignment ->
        when (alignment) {
            0 -> {
                editText.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                editText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            1 -> {
                editText.textAlignment = View.TEXT_ALIGNMENT_CENTER
                editText.gravity = Gravity.CENTER
            }
            2 -> {
                editText.textAlignment = View.TEXT_ALIGNMENT_VIEW_END
                editText.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            else -> error("unknown text-field prompt alignment: $alignment")
        }
    }
    promptAlignment.attachTo(editText)

    installSemanticAccessibilityLabel(
        target = editText,
        content = labelView,
        labelPtr = struct.accessibilityLabelPtr,
        env = env
    )

    val foreground = ThemeBridge.foreground(env)
    foreground.observe { color -> editText.setTextColor(color.toColorInt()) }
    foreground.attachTo(editText)

    val hintColor = ThemeBridge.mutedForeground(env)
    hintColor.observe { color -> editText.setHintTextColor(color.toColorInt()) }
    hintColor.attachTo(editText)

    container.disposeWith {
        editText.removeTextChangedListener(textWatcher)
        binding.close()
        promptBinding?.close()
        promptComputed.close()
    }
    container
}

private fun resolveInputType(keyboardType: Int, singleLine: Boolean): Int {
    val base = resolveKeyboardInputType(keyboardType)
    // Only a text keyboard can carry the multi-line flag; numeric and phone
    // keypads have no newline key to enable.
    return if (!singleLine && base and InputType.TYPE_CLASS_TEXT != 0) {
        base or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    } else {
        base
    }
}

private fun resolveKeyboardInputType(keyboardType: Int): Int =
    when (keyboardType) {
        KEYBOARD_TEXT -> InputType.TYPE_CLASS_TEXT
        KEYBOARD_EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        KEYBOARD_URL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        KEYBOARD_NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        KEYBOARD_PHONE -> InputType.TYPE_CLASS_PHONE
        else -> error("unknown keyboard type: $keyboardType")
    }

internal fun RegistryBuilder.registerWuiTextField() {
    register({ textFieldTypeId }, textFieldRenderer)
}
