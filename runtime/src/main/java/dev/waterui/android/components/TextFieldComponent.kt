package dev.waterui.android.components

import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.widget.addTextChangedListener
import com.google.android.material.shape.MaterialShapeDrawable
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
import dev.waterui.android.runtime.dp
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

    val horizontalPadding = 12f.dp(context).toInt()
    val verticalPadding = 8f.dp(context).toInt()

    val editText = AppCompatEditText(context).apply {
        inputType = resolveInputType(struct.keyboardType)
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    editText.installWuiFocusTarget(WuiTextInputFocusTarget(editText))
    installTextSelectionMenu(editText, struct.selectionMenuPtr, env)
    container.addView(editText)
    val shape = MaterialShapeDrawable().apply {
        setCornerSize(12f.dp(context))
        strokeWidth = 1f.dp(context)
    }
    editText.background = shape

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

    val surfaceColor = ThemeBridge.surface(env)
    surfaceColor.observe { color ->
        shape.fillColor = ColorStateList.valueOf(color.toColorInt())
    }
    surfaceColor.attachTo(editText)

    val borderColor = ThemeBridge.border(env)
    borderColor.observe { color ->
        shape.setStroke(shape.strokeWidth, color.toColorInt())
    }
    borderColor.attachTo(editText)

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

private fun resolveInputType(keyboardType: Int): Int =
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
