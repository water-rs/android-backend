package dev.waterui.android.components

import android.view.View
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.WuiEnvironment

internal fun installSemanticAccessibilityLabel(
    target: View,
    content: View,
    labelPtr: Long,
    env: WuiEnvironment
): AutoCloseable? {
    if (labelPtr == 0L) {
        return null
    }

    target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

    return WuiComputed.styledString(labelPtr, env).also { label ->
        label.observe { styled ->
            target.contentDescription = styled.toCharSequence(env)
        }
    }
}
