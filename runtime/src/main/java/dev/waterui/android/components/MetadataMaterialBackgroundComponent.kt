package dev.waterui.android.components

import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.waterui.android.runtime.MaterialBackgroundStruct
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.ThemeBridge
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.attachTo
import dev.waterui.android.runtime.inflateAnyView
import dev.waterui.android.runtime.toColorInt

private val materialBackgroundTypeId: WuiTypeId by lazy {
    WatcherJni.ignorableMetadataMaterialBackgroundId().toTypeId()
}

private val materialBackgroundRenderer = WuiRenderer { context, node, env, registry ->
    val metadata: MaterialBackgroundStruct =
        WatcherJni.forceAsIgnorableMetadataMaterialBackground(node.rawPtr)

    val contentPtr = metadata.contentPtr
    val contentView = inflateAnyView(context, contentPtr, env, registry)

    val container = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addView(
            contentView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    // Android doesn't have a direct equivalent of UIKit/AppKit's backdrop blur without
    // additional dependencies. We approximate with a translucent surface color.
    val alpha = materialAlpha(metadata.material)
    val surface = ThemeBridge.surface(env)
    surface.observe { color ->
        val argb = color.toColorInt()
        val base = Color.argb(alpha, Color.red(argb), Color.green(argb), Color.blue(argb))
        container.setBackgroundColor(base)
    }
    surface.attachTo(container)

    container
}

private fun materialAlpha(material: Int): Int = when (material) {
    0 -> 80   // UltraThin
    1 -> 110  // Thin
    2 -> 150  // Regular
    3 -> 190  // Thick
    4 -> 220  // UltraThick
    else -> 150
}

internal fun RegistryBuilder.registerWuiMaterialBackground() {
    registerMetadata({ materialBackgroundTypeId }, materialBackgroundRenderer)
}

