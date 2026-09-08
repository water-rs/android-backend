package dev.waterui.android.components

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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

/**
 * The text a view says about itself, used as a component's accessibility label
 * when WaterUI carried no explicit one. This is the Android counterpart of the
 * hydrolysis renderer's `default_label`: the label view is read directly rather
 * than through the accessibility tree, so folding it into the control's own node
 * and hiding it as a separate node stays consistent.
 */
internal fun accessibilityTextOf(view: View): CharSequence? {
    val description = view.contentDescription
    if (!description.isNullOrEmpty()) {
        return description
    }
    if (view is TextView) {
        return view.text?.takeIf { it.isNotEmpty() }
    }
    if (view is ViewGroup) {
        val parts = (0 until view.childCount)
            .mapNotNull { index -> accessibilityTextOf(view.getChildAt(index)) }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = " ")
    }
    return null
}

/**
 * Chains an accessibility delegate onto [target] that rewrites the node it
 * publishes and, optionally, handles accessibility actions performed on it.
 *
 * Whatever delegate the view already had keeps running first, so a platform
 * widget's own node population is preserved and only the WaterUI semantics are
 * layered on top. [performAction] is consulted before the previous delegate, so
 * an action WaterUI owns wins over the widget's default handling.
 */
internal fun installAccessibilityDelegate(
    target: View,
    mutate: (AccessibilityNodeInfoCompat) -> Unit,
    performAction: (action: Int, arguments: Bundle?) -> Boolean = { _, _ -> false },
) {
    val previous = ViewCompat.getAccessibilityDelegate(target)
    ViewCompat.setAccessibilityDelegate(target, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(
            host: View,
            info: AccessibilityNodeInfoCompat,
        ) {
            if (previous == null) {
                super.onInitializeAccessibilityNodeInfo(host, info)
            } else {
                previous.onInitializeAccessibilityNodeInfo(host, info)
            }
            mutate(info)
        }

        override fun performAccessibilityAction(
            host: View,
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            if (performAction(action, arguments)) {
                return true
            }
            return if (previous == null) {
                super.performAccessibilityAction(host, action, arguments)
            } else {
                previous.performAccessibilityAction(host, action, arguments)
            }
        }
    })
}

/** Publishes only a node rewrite, with no WaterUI-owned actions. */
internal fun installAccessibilityMutation(
    target: View,
    mutation: (AccessibilityNodeInfoCompat) -> Unit,
) {
    installAccessibilityDelegate(target, mutate = mutation)
}
