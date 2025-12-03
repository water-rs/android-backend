package dev.waterui.android.components

import android.widget.FrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiEnvironment
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView


private val metadataEnvTypeId: WuiTypeId by lazy { NativeBindings.waterui_metadata_env_id().toTypeId() }

/**
 * Renderer for WithEnv / Metadata<Environment>.
 *
 * This component provides a new environment to its child view tree.
 * It extracts the environment from the metadata and uses it for inflating
 * the wrapped content.
 *
 * Metadata is transparent for layout - the stretch axis comes from the content.
 */
private val metadataEnvRenderer = WuiRenderer { context, node, _, registry ->
    val metadata = NativeBindings.waterui_force_as_metadata_env(node.rawPtr)

    // Create a WuiEnvironment wrapper for the new environment
    // This takes ownership of the envPtr
    val newEnv = WuiEnvironment(metadata.envPtr)

    // Container view
    val container = FrameLayout(context)

    // Inflate the content with the new environment
    if (metadata.contentPtr != 0L) {
        val child = inflateAnyView(context, metadata.contentPtr, newEnv, registry)
        container.addView(child)
        // Metadata is transparent - propagate child's stretch axis to container
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }

    // Cleanup when the container is detached
    container.disposeWith {
        // Drop the content view pointer
        if (metadata.contentPtr != 0L) {
            NativeBindings.waterui_drop_anyview(metadata.contentPtr)
        }
        // Close the environment (this drops the env pointer)
        newEnv.close()
    }

    container
}

internal fun RegistryBuilder.registerWuiWithEnv() {
    registerMetadata({ metadataEnvTypeId }, metadataEnvRenderer)
}
