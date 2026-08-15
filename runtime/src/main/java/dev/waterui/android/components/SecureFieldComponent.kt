package dev.waterui.android.components

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.LinearLayout
import androidx.core.widget.addTextChangedListener
import dev.waterui.android.layout.AxisExpandingLinearLayout
import dev.waterui.android.reactive.WuiBinding
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

private val secureFieldTypeId: WuiTypeId by lazy { NativeBindings.waterui_secure_field_id().toTypeId() }

private val secureFieldRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_secure_field(node.rawPtr)
    val binding = WuiBinding.secure(struct.valuePtr)

    val container = AxisExpandingLinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView)

    val input = createMaterialTextInput(context)
    val editText = input.editText.apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()
    }
    editText.installWuiFocusTarget(WuiTextInputFocusTarget(editText))
    container.addView(input.layout)

    val bindingSynchronizer = TextInputBindingSynchronizer(
        currentEditorValue = {
            requireNotNull(editText.text) { "SecureField editor returned null text" }.toString()
        },
        replaceEditorValue = { value ->
            editText.setText(value)
            editText.setSelection(value.length)
        },
        updateBinding = binding::set
    )
    binding.observe(bindingSynchronizer::bindingChanged)

    val textWatcher = editText.addTextChangedListener { text ->
        val value = requireNotNull(text) { "SecureField text watcher received null text" }
        bindingSynchronizer.editorChanged(value.toString())
    }
    container.disposeWith {
        editText.removeTextChangedListener(textWatcher)
        binding.close()
    }

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

    container
}

internal fun RegistryBuilder.registerWuiSecureField() {
    register({ secureFieldTypeId }, secureFieldRenderer)
}
