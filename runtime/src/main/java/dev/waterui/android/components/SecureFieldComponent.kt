package dev.waterui.android.components

import android.content.res.ColorStateList
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.material.shape.MaterialShapeDrawable
import dev.waterui.android.layout.AxisExpandingLinearLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt
import dev.waterui.android.runtime.dp
import java.util.concurrent.atomic.AtomicBoolean

private val secureFieldTypeId: WuiTypeId by lazy { NativeBindings.waterui_secure_field_id().toTypeId() }

private val secureFieldRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_secure_field(node.rawPtr)

    // SecureField is StretchAxis::Horizontal (Rust-defined):
    // report a minimum usable width in size_that_fits, then expand during place.
    val container = AxisExpandingLinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    val labelView = inflateAnyView(context, struct.labelPtr, env, registry)
    container.addView(labelView)

    val editText = AppCompatEditText(context).apply {
        // Configure for secure input
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        transformationMethod = PasswordTransformationMethod.getInstance()

        // Disable autocorrect and suggestions for security
        // Note: These are hints to the system; enforcement may vary by keyboard
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    container.addView(editText)

    val density = context.resources.displayMetrics.density
    val shape = MaterialShapeDrawable().apply {
        val radius = 12f * density
        val strokeWidthPx = 1f * density
        setCornerSize(radius)
        strokeWidth = strokeWidthPx
    }
    ViewCompat.setBackground(editText, shape)

    val updating = AtomicBoolean(false)

    // Handle Secure binding
    // Note: For security, we don't read the initial value from the binding
    // The field starts empty and user must enter the value

    editText.addTextChangedListener { text ->
        if (!updating.get()) {
            val textValue = text?.toString().orEmpty()
            // Set the binding value directly with the string bytes
            NativeBindings.waterui_set_binding_secure(struct.valuePtr, textValue.encodeToByteArray())
        }
    }

    // Apply theming
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

    container
}

internal fun RegistryBuilder.registerWuiSecureField() {
    register({ secureFieldTypeId }, secureFieldRenderer)
}
