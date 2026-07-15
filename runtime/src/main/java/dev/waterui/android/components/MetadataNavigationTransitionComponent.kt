package dev.waterui.android.components

import android.content.Context
import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.MetadataNavigationTransitionStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.RenderRegistry
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiNode
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId

private val metadataNavigationTransitionSourceTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_navigation_transition_source_id().toTypeId()
}

private val metadataNavigationTransitionDestinationTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_navigation_transition_destination_id().toTypeId()
}

internal fun navigationTransitionName(id: Int): String = "dev.waterui.navigation.transition.$id"

private fun navigationTransitionView(
    context: Context,
    node: WuiNode,
    env: WuiEnvironment,
    registry: RenderRegistry,
    extract: (Long) -> MetadataNavigationTransitionStruct
): View {
    val metadata = extract(node.rawPtr)
    return PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            tag = metadata.id
            transitionName = navigationTransitionName(metadata.id)
        }
}

private val sourceRenderer = WuiRenderer { context, node, env, registry ->
    navigationTransitionView(
        context,
        node,
        env,
        registry,
        NativeBindings::waterui_force_as_metadata_navigation_transition_source
    )
}

private val destinationRenderer = WuiRenderer { context, node, env, registry ->
    navigationTransitionView(
        context,
        node,
        env,
        registry,
        NativeBindings::waterui_force_as_metadata_navigation_transition_destination
    )
}

internal fun RegistryBuilder.registerWuiNavigationTransitionMetadata() {
    registerMetadata({ metadataNavigationTransitionSourceTypeId }, sourceRenderer)
    registerMetadata({ metadataNavigationTransitionDestinationTypeId }, destinationRenderer)
}
