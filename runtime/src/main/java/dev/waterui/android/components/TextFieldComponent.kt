package dev.waterui.android.components

import android.content.res.ColorStateList
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.material.shape.MaterialShapeDrawable
import dev.waterui.android.layout.AxisExpandingLinearLayout
import dev.waterui.android.reactive.WuiBinding
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.editableToStyled
import dev.waterui.android.runtime.toModel

import dev.waterui.android.runtime.dp
import java.util.concurrent.atomic.AtomicBoolean

private val textFieldTypeId: WuiTypeId by lazy { WatcherJni.textFieldId().toTypeId() }

private const val KEYBOARD_TEXT = 0
private const val KEYBOARD_SECURE = 1
private const val KEYBOARD_EMAIL = 2
private const val KEYBOARD_URL = 3
private const val KEYBOARD_NUMBER = 4
private const val KEYBOARD_PHONE = 5

private val textFieldRenderer = WuiRenderer { context, node, env, registry ->
    val struct = WatcherJni.forceAsTextField(node.rawPtr)
    val binding = WuiBinding.styledString(struct.valuePtr, env)
    val promptComputed = struct.promptPtr.takeIf { it != 0L }?.let { WuiComputed.styledString(it, env) }

    // TextField is StretchAxis::Horizontal (Rust-defined):
    // report a minimum usable width in size_that_fits, then expand during place.
    val container = AxisExpandingLinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView)

    val density = context.resources.displayMetrics.density
    val horizontalPadding = (12 * density).toInt()
    val verticalPadding = (8 * density).toInt()

    val editText = AppCompatEditText(context).apply {
        inputType = resolveInputType(struct.keyboardType)
        if (struct.keyboardType == KEYBOARD_SECURE) {
            transformationMethod = PasswordTransformationMethod.getInstance()
        } else {
            transformationMethod = null
        }
        setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    container.addView(editText)
    val shape = MaterialShapeDrawable().apply {
        val radius = 12f * density
        val strokeWidthPx = 1f * density
        setCornerSize(radius)
        strokeWidth = strokeWidthPx
    }
    ViewCompat.setBackground(editText, shape)

    val updating = AtomicBoolean(false)
    var lastRendered: dev.waterui.android.runtime.StyledStrStruct? = null
    binding.observe { styled ->
        if (updating.get() || lastRendered == styled) return@observe

        val selected = editText.selectionStart.takeIf { it >= 0 } ?: 0
        val model = styled.toModel()
        val rendered = model.toCharSequence(env)
        model.close()

        updating.set(true)
        editText.setText(rendered, TextView.BufferType.SPANNABLE)
        val length = editText.text?.length ?: 0
        editText.setSelection(selected.coerceAtMost(length))
        updating.set(false)

        lastRendered = styled
    }

    editText.addTextChangedListener {
        if (!updating.get()) {
            val styled = editableToStyled(editText.editableText, editText)
            lastRendered = styled
            binding.set(styled)
        }
    }

    promptComputed?.observe { prompt ->
        editText.hint = prompt.toCharSequence(env)
    }

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

internal fun RegistryBuilder.registerWuiTextField() {
    register({ textFieldTypeId }, textFieldRenderer)
}
