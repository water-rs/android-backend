package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val metadataIgnoreSafeAreaTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_ignore_safe_area_id().toTypeId()
}

@SuppressLint("ViewConstructor")
private class IgnoreSafeAreaLayout(
    context: Context,
    private val top: Boolean,
    private val bottom: Boolean,
    private val leading: Boolean,
    private val trailing: Boolean
) : PassThroughFrameLayout(context) {
    private var safeArea = Insets.NONE

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
            val nextSafeArea = windowInsets.getInsets(safeAreaTypes())
            if (safeArea != nextSafeArea) {
                safeArea = nextSafeArea
                requestLayout()
            }
            WindowInsetsCompat.Builder(windowInsets)
                .setInsets(
                    safeAreaTypes(),
                    Insets.of(
                        if (leading) 0 else safeArea.left,
                        if (top) 0 else safeArea.top,
                        if (trailing) 0 else safeArea.right,
                        if (bottom) 0 else safeArea.bottom
                    )
                )
                .build()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val child = getChildAt(0) ?: return
        child.measure(
            View.MeasureSpec.makeMeasureSpec(
                measuredWidth +
                    (if (leading) safeArea.left else 0) +
                    (if (trailing) safeArea.right else 0),
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(
                measuredHeight +
                    (if (top) safeArea.top else 0) +
                    (if (bottom) safeArea.bottom else 0),
                View.MeasureSpec.EXACTLY
            )
        )
    }

    override fun onLayout(changed: Boolean, left: Int, topEdge: Int, right: Int, bottomEdge: Int) {
        val child = getChildAt(0) ?: return
        val childLeft = if (leading) -safeArea.left else 0
        val childTop = if (top) -safeArea.top else 0
        child.layout(
            childLeft,
            childTop,
            width + if (trailing) safeArea.right else 0,
            height + if (bottom) safeArea.bottom else 0
        )
    }
}

private fun safeAreaTypes(): Int =
    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

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
