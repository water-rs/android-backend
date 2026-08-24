package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiSafeAreaManaging
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.applyRemainingInsets

private val metadataIgnoreSafeAreaTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_ignore_safe_area_id().toTypeId()
}

/**
 * Drops the safe area on the edges it was told to ignore, before the content
 * below ever sees it.
 *
 * The window reaches under the system bars on its own, so touching an edge is
 * not something to arrange — it is what happens unless something insets you.
 * This is that something, declining. It used to grow its child back outwards by
 * the inset instead, which was the right move while the root padded everything
 * and is double-counting now that nothing does.
 */
@SuppressLint("ViewConstructor")
private class IgnoreSafeAreaLayout(
    context: Context,
    private val top: Boolean,
    private val bottom: Boolean,
    private val leading: Boolean,
    private val trailing: Boolean
) : PassThroughFrameLayout(context), WuiSafeAreaManaging {
    override fun applySafeArea(insets: Insets) {
        val child = getChildAt(0) ?: return
        applyRemainingInsets(
            child,
            Insets.of(
                if (leading) 0 else insets.left,
                if (top) 0 else insets.top,
                if (trailing) 0 else insets.right,
                if (bottom) 0 else insets.bottom
            )
        )
    }
}

private val metadataIgnoreSafeAreaRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_ignore_safe_area(node.rawPtr)
    IgnoreSafeAreaLayout(
        context = context,
        top = metadata.top,
        bottom = metadata.bottom,
        leading = metadata.leading,
        trailing = metadata.trailing
    ).attachMetadataContent(
        context,
        metadata.contentPtr,
        env,
        registry,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
}

internal fun RegistryBuilder.registerWuiIgnoreSafeArea() {
    registerMetadata({ metadataIgnoreSafeAreaTypeId }, metadataIgnoreSafeAreaRenderer)
}
