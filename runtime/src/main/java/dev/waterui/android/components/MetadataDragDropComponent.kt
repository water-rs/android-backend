package dev.waterui.android.components

import android.content.ClipData
import android.net.Uri
import android.view.DragEvent
import android.view.View
import dev.waterui.android.ffi.WatcherJni
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith

private val metadataDraggableTypeId: WuiTypeId by lazy {
    WatcherJni.metadataDraggableId().toTypeId()
}
private val metadataDropDestinationTypeId: WuiTypeId by lazy {
    WatcherJni.metadataDropDestinationId().toTypeId()
}

private const val DRAG_DATA_TAG_TEXT = 0
private const val DRAG_DATA_TAG_URL = 1

private val metadataDraggableRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataDraggable(node.rawPtr)
    PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            isLongClickable = true
            setOnLongClickListener { view ->
                val data = WatcherJni.draggableGetData(metadata.draggablePtr)
                val clip =
                    when (data.tag) {
                        DRAG_DATA_TAG_URL ->
                            ClipData.newUri(
                                context.contentResolver,
                                data.value,
                                Uri.parse(data.value),
                            )
                        else -> ClipData.newPlainText(data.value, data.value)
                    }
                view.startDragAndDrop(clip, View.DragShadowBuilder(view), null, 0)
            }
            disposeWith { WatcherJni.dropDraggable(metadata.draggablePtr) }
        }
}

private val metadataDropDestinationRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataDropDestination(node.rawPtr)
    PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            val envPtr = env.raw()
            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        WatcherJni.dropDestinationOnEnter(metadata.destinationPtr, envPtr)
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        WatcherJni.dropDestinationOnExit(metadata.destinationPtr, envPtr)
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        val item = event.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                        if (item == null) {
                            false
                        } else {
                            val (tag, value) =
                                when {
                                    item.uri != null -> DRAG_DATA_TAG_URL to item.uri.toString()
                                    else -> DRAG_DATA_TAG_TEXT to item.text?.toString().orEmpty()
                                }
                            WatcherJni.dropDestinationOnDrop(
                                metadata.destinationPtr,
                                envPtr,
                                tag,
                                value,
                            )
                            true
                        }
                    }
                    else -> true
                }
            }
            disposeWith { WatcherJni.dropDropDestination(metadata.destinationPtr) }
        }
}

internal fun RegistryBuilder.registerWuiDragDropMetadata() {
    registerMetadata({ metadataDraggableTypeId }, metadataDraggableRenderer)
    registerMetadata({ metadataDropDestinationTypeId }, metadataDropDestinationRenderer)
}
