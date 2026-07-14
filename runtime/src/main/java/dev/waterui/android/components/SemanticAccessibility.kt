package dev.waterui.android.components

import android.view.View
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.disposeWith
import java.io.Closeable

internal fun bindSemanticAccessibilityLabel(
    labelPtr: Long,
    env: WuiEnvironment,
    onValue: (CharSequence) -> Unit
): Closeable {
    val label = WuiComputed.styledString(labelPtr)
    var styledBinding: Closeable? = null
    label.observe { styled ->
        styledBinding?.close()
        styledBinding = styled.bind(env, onValue)
    }
    return Closeable {
        styledBinding?.close()
        label.close()
    }
}

internal fun installSemanticAccessibilityLabel(
    target: View,
    content: View,
    labelPtr: Long,
    env: WuiEnvironment
) {
    target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    target.disposeWith(
        bindSemanticAccessibilityLabel(labelPtr, env) { resolved ->
            target.contentDescription = resolved
        }
    )
}
