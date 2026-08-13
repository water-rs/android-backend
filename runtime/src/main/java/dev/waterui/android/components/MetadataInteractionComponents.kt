package dev.waterui.android.components

import android.content.Context
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.ViewCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val metadataCursorTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_cursor_id().toTypeId()
}
private val metadataHittableTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_hittable_id().toTypeId()
}
private val metadataAccessibilityIdentifierTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_ignorable_metadata_accessibility_identifier_id().toTypeId()
}
private val metadataAccessibilityLabelTypeId by lazy { NativeBindings.waterui_ignorable_metadata_accessibility_label_id().toTypeId() }
private val metadataAccessibilityRoleTypeId by lazy { NativeBindings.waterui_ignorable_metadata_accessibility_role_id().toTypeId() }
private val metadataAccessibilityHiddenTypeId by lazy { NativeBindings.waterui_ignorable_metadata_accessibility_hidden_id().toTypeId() }
private val metadataAccessibilityChildrenTypeId by lazy { NativeBindings.waterui_ignorable_metadata_accessibility_children_id().toTypeId() }
private val metadataAccessibilityStateTypeId by lazy { NativeBindings.waterui_ignorable_metadata_accessibility_state_id().toTypeId() }
private val metadataAccessibilityStateSignalTypeId by lazy { NativeBindings.waterui_ignorable_metadata_accessibility_state_signal_id().toTypeId() }

private class HittableLayout(context: Context) : PassThroughFrameLayout(context) {
    var hitTestingEnabled = true

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        hitTestingEnabled && super.dispatchTouchEvent(ev)
}

private class AccessibilityMetadataLayout(context: Context) : PassThroughFrameLayout(context)

private fun pointerIconType(style: Int): Int = when (style) {
    0 -> PointerIcon.TYPE_ARROW
    1 -> PointerIcon.TYPE_HAND
    2 -> PointerIcon.TYPE_TEXT
    3 -> PointerIcon.TYPE_CROSSHAIR
    4 -> PointerIcon.TYPE_GRAB
    5 -> PointerIcon.TYPE_GRABBING
    6 -> PointerIcon.TYPE_NO_DROP
    7, 8, 11 -> PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
    9, 10, 12 -> PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
    13 -> PointerIcon.TYPE_ALL_SCROLL
    14 -> PointerIcon.TYPE_WAIT
    15 -> PointerIcon.TYPE_COPY
    else -> error("unknown WaterUI cursor style: $style")
}

private val metadataCursorRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_cursor(node.rawPtr)
    AccessibilityMetadataLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            val style = WuiComputed.cursorStyle(metadata.stylePtr)
            style.observe { value ->
                pointerIcon = PointerIcon.getSystemIcon(context, pointerIconType(value))
            }
            disposeWith(style)
        }
}

private val metadataHittableRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_hittable(node.rawPtr)
    HittableLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            val enabled = WuiComputed.bool(metadata.enabledPtr)
            enabled.observe { value -> hitTestingEnabled = value }
            disposeWith(enabled)
        }
}

private val metadataAccessibilityIdentifierRenderer = WuiRenderer { context, node, env, registry ->
    val metadata =
        NativeBindings.waterui_force_as_ignorable_metadata_accessibility_identifier(node.rawPtr)
    PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            // Expose the identifier as the node's view-id resource name so
            // UiAutomator/Espresso can match it, and mirror it on the view tag
            // for plain view-hierarchy lookups. Invisible to TalkBack.
            val target = semanticAccessibilityTarget(this)
            target.tag = metadata.identifier
            installAccessibilityMutation(target) { info ->
                info.viewIdResourceName = metadata.identifier
            }
        }
}

private fun semanticAccessibilityTarget(view: View): View {
    var target = view
    while (target is AccessibilityMetadataLayout && target.childCount == 1) {
        target = target.getChildAt(0)
    }
    return target
}

private fun installAccessibilityMutation(
    target: View,
    mutation: (AccessibilityNodeInfoCompat) -> Unit,
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
                    mutation(info)
                }
            })
}

private fun accessibilityRoleClassName(role: Int): String = when (role) {
    0 -> "android.widget.Button"
    1, 3, 4, 5 -> "android.widget.TextView"
    2 -> "android.widget.ImageView"
    11, 17, 19, 21, 22, 23, 27, 28 -> "android.view.ViewGroup"
    13, 24 -> "android.widget.CheckBox"
    14, 25 -> "android.widget.RadioButton"
    15 -> "android.widget.Switch"
    16 -> "android.widget.SeekBar"
    18, 26 -> "android.widget.Button"
    6, 7, 8, 9, 10, 12, 20 -> "android.view.View"
    else -> error("unknown WaterUI accessibility role: $role")
}

private val metadataAccessibilityLabelRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_ignorable_metadata_accessibility_label(node.rawPtr)
    AccessibilityMetadataLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            val target = semanticAccessibilityTarget(this)
            target.disposeWith(
                bindSemanticAccessibilityLabel(metadata.labelPtr, env) { label ->
                    target.contentDescription = label
                    target.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                },
            )
        }
}

private val metadataAccessibilityRoleRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_ignorable_metadata_accessibility_role(node.rawPtr)
    AccessibilityMetadataLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            installAccessibilityMutation(semanticAccessibilityTarget(this)) { info ->
                info.className = accessibilityRoleClassName(metadata.value)
            }
        }
}

private val metadataAccessibilityHiddenRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_ignorable_metadata_accessibility_hidden(node.rawPtr)
    AccessibilityMetadataLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            semanticAccessibilityTarget(this).importantForAccessibility = if (metadata.value != 0) {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
        }
}

private val metadataAccessibilityChildrenRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_ignorable_metadata_accessibility_children(node.rawPtr)
    AccessibilityMetadataLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            if (metadata.value != 0) {
                val target = semanticAccessibilityTarget(this)
                target.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                if (target is android.view.ViewGroup) {
                    for (index in 0 until target.childCount) {
                        target.getChildAt(index).importantForAccessibility =
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }
                }
            }
        }
}

private fun accessibilityStateRenderer(signal: Boolean) = WuiRenderer { context, node, env, registry ->
    val metadata = if (signal) {
        NativeBindings.waterui_force_as_ignorable_metadata_accessibility_state_signal(node.rawPtr)
    } else {
        NativeBindings.waterui_force_as_ignorable_metadata_accessibility_state(node.rawPtr)
    }
    AccessibilityMetadataLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            val target = semanticAccessibilityTarget(this)
            val disabled = WuiComputed.bool(metadata.disabledPtr)
            val selected = WuiComputed.bool(metadata.selectedPtr)
            val checked = WuiComputed.int(metadata.checkedPtr)
            val expanded = WuiComputed.int(metadata.expandedPtr)
            val busy = WuiComputed.bool(metadata.busyPtr)
            val hidden = WuiComputed.bool(metadata.hiddenPtr)
            var isDisabled = false
            var isSelected = false
            var checkedState = -1
            var expandedState = -1
            var isBusy = false
            val originalImportance = target.importantForAccessibility
            installAccessibilityMutation(target) { info ->
                info.isEnabled = !isDisabled
                info.isSelected = isSelected
                info.isCheckable = checkedState >= 0
                if (checkedState >= 0) {
                    info.setChecked(when (checkedState) {
                        0 -> AccessibilityNodeInfoCompat.CHECKED_STATE_FALSE
                        1 -> AccessibilityNodeInfoCompat.CHECKED_STATE_TRUE
                        2 -> AccessibilityNodeInfoCompat.CHECKED_STATE_PARTIAL
                        else -> error("unknown WaterUI accessibility checked state: $checkedState")
                    })
                }
                info.expandedState = when (expandedState) {
                    -1 -> AccessibilityNodeInfoCompat.EXPANDED_STATE_UNDEFINED
                    0 -> AccessibilityNodeInfoCompat.EXPANDED_STATE_COLLAPSED
                    1 -> AccessibilityNodeInfoCompat.EXPANDED_STATE_FULL
                    else -> error("unknown WaterUI accessibility expanded state: $expandedState")
                }
                val originalStateDescription = info.stateDescription
                info.stateDescription = when {
                    checkedState == 2 && isBusy -> context.getString(dev.waterui.android.runtime.R.string.wui_accessibility_mixed_busy)
                    checkedState == 2 -> context.getString(dev.waterui.android.runtime.R.string.wui_accessibility_mixed)
                    isBusy -> context.getString(dev.waterui.android.runtime.R.string.wui_accessibility_busy)
                    else -> originalStateDescription
                }
            }
            val changed = { target.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) }
            disabled.observe { isDisabled = it; changed() }
            selected.observe { isSelected = it; changed() }
            checked.observe { checkedState = it; changed() }
            expanded.observe { expandedState = it; changed() }
            busy.observe { isBusy = it; changed() }
            hidden.observe {
                target.importantForAccessibility = if (it) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else originalImportance
                changed()
            }
            disposeWith(disabled)
            disposeWith(selected)
            disposeWith(checked)
            disposeWith(expanded)
            disposeWith(busy)
            disposeWith(hidden)
        }
}

internal fun RegistryBuilder.registerWuiInteractionMetadata() {
    registerMetadata({ metadataCursorTypeId }, metadataCursorRenderer)
    registerMetadata({ metadataHittableTypeId }, metadataHittableRenderer)
    registerMetadata(
        { metadataAccessibilityIdentifierTypeId },
        metadataAccessibilityIdentifierRenderer,
    )
    registerMetadata({ metadataAccessibilityLabelTypeId }, metadataAccessibilityLabelRenderer)
    registerMetadata({ metadataAccessibilityRoleTypeId }, metadataAccessibilityRoleRenderer)
    registerMetadata({ metadataAccessibilityHiddenTypeId }, metadataAccessibilityHiddenRenderer)
    registerMetadata({ metadataAccessibilityChildrenTypeId }, metadataAccessibilityChildrenRenderer)
    registerMetadata({ metadataAccessibilityStateTypeId }, accessibilityStateRenderer(false))
    registerMetadata({ metadataAccessibilityStateSignalTypeId }, accessibilityStateRenderer(true))
}
