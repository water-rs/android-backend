package dev.waterui.android.components

import android.content.Context
import android.widget.LinearLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * The native Material text input scaffold shared by the text and secure
 * fields.
 *
 * Compose's default `TextField` is the Material 3 filled style; the View-world
 * projection of that widget is `TextInputLayout` with `textInputFilledStyle`,
 * which owns the container fill, bottom indicator line, and focus animation.
 * WaterUI renders the semantic label as its own view above the box and routes
 * the prompt through the editor hint, so the floating hint is disabled.
 */
internal class MaterialTextInput(
    val layout: TextInputLayout,
    val editText: TextInputEditText
)

internal fun createMaterialTextInput(context: Context): MaterialTextInput {
    val layout = TextInputLayout(
        context,
        null,
        com.google.android.material.R.attr.textInputFilledStyle
    ).apply {
        isHintEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }
    val editText = TextInputEditText(layout.context)
    layout.addView(
        editText,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    )
    return MaterialTextInput(layout, editText)
}
