package dev.waterui.android.components

import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val metadataEnvTypeId: WuiTypeId by lazy { NativeBindings.waterui_metadata_env_id().toTypeId() }

private val metadataEnvRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_env(node.rawPtr)
    val newEnv = WuiEnvironment(metadata.envPtr).also { it.pxPerSp = env.pxPerSp }
    val container = PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, newEnv, registry)
    container.disposeWith(newEnv)
    container
}

internal fun RegistryBuilder.registerWuiWithEnv() {
    registerMetadata({ metadataEnvTypeId }, metadataEnvRenderer)
}
