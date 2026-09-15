package dev.waterui.android.components

import android.widget.FrameLayout
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

private val badgeTypeId: WuiTypeId by lazy { NativeBindings.waterui_badge_id().toTypeId() }

private val badgeRenderer = WuiRenderer { context, node, env, registry ->
    val badge = NativeBindings.waterui_force_as_badge(node.rawPtr)

    // BadgedBox in Compose terms: the anchor sizes the container, and the
    // indicator rides the anchor's top end. `BadgeDrawable` is the View-side
    // MD3 primitive — it handles the dot/number variants, the top-end anchor
    // offset, RTL mirroring, and the "999+" cap.
    val container = FrameLayout(context)
    val anchor = inflateAnyView(context, badge.contentPtr, env, registry)
    container.addView(anchor)

    val indicator = BadgeDrawable.create(context)
    BadgeUtils.attachBadgeDrawable(indicator, anchor, container)

    val value = WuiComputed.int(badge.valuePtr)
    value.observe { count ->
        // MaterialBadge documents `0` as the small dot; negative counts are
        // meaningless and collapse to the same shape.
        if (count > 0) indicator.number = count else indicator.clearNumber()
        indicator.isVisible = true
    }

    // `color` arrives as an unresolved `Computed<Color>`; resolve it against
    // this environment to a resolved-color signal before observing.
    val colorPtr = NativeBindings.waterui_resolve_computed_color(badge.colorPtr, env.raw())
    val color = WuiComputed.colorFromComputed(colorPtr)
    color.observe { resolved ->
        // `Color::default()` (opaque black) is the unset sentinel: MD3 badges
        // default to the theme's error color, which `BadgeDrawable` already
        // carries — only an explicitly chosen color overrides it.
        if (resolved.red != 0f || resolved.green != 0f || resolved.blue != 0f) {
            indicator.backgroundColor = resolved.toColorInt()
        }
    }

    container.disposeWith(value)
    container.disposeWith(color)
    container
}

internal fun RegistryBuilder.registerWuiBadge() {
    // The badge surface landed in waterui-ffi after this backend shipped:
    // an app pinned to an older framework lacks the export, so registering
    // unconditionally would crash every such app at registry build. Absence
    // means no Badge view can exist either, so skipping is complete.
    val typeId = try {
        badgeTypeId
    } catch (_: UnsatisfiedLinkError) {
        return
    }
    register({ typeId }, badgeRenderer)
}
