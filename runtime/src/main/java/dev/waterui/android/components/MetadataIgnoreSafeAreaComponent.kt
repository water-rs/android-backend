package dev.waterui.android.components

import android.view.ViewGroup
import android.widget.FrameLayout
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

private val metadataIgnoreSafeAreaRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_ignore_safe_area(node.rawPtr)
    val container = PassThroughFrameLayout(context).attachMetadataContent(
        context,
        metadata.contentPtr,
        env,
        registry,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )

    ViewCompat.setOnApplyWindowInsetsListener(container) { view, windowInsets ->
        val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
        val leftPadding = if (metadata.leading) 0 else systemBars.left
        val topPadding = if (metadata.top) 0 else systemBars.top
        val rightPadding = if (metadata.trailing) 0 else systemBars.right
        val bottomPadding = if (metadata.bottom) 0 else systemBars.bottom

        view.setPadding(leftPadding, topPadding, rightPadding, bottomPadding)

        WindowInsetsCompat.Builder(windowInsets)
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                androidx.core.graphics.Insets.of(
                    if (metadata.leading) systemBars.left else 0,
                    if (metadata.top) systemBars.top else 0,
                    if (metadata.trailing) systemBars.right else 0,
                    if (metadata.bottom) systemBars.bottom else 0
                )
            )
            .build()
    }

    ViewCompat.requestApplyInsets(container)

    container
}

internal fun RegistryBuilder.registerWuiIgnoreSafeArea() {
    registerMetadata({ metadataIgnoreSafeAreaTypeId }, metadataIgnoreSafeAreaRenderer)
}
