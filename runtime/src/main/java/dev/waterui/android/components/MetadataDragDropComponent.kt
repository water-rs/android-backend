package dev.waterui.android.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.view.DragEvent
import android.view.View
import androidx.core.net.toUri
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

// WuiTransferKind ordinals from ffi/waterui.h.
private const val TRANSFER_KIND_TEXT = 0
private const val TRANSFER_KIND_URL = 1
private const val TRANSFER_KIND_FILES = 2
private const val TRANSFER_KIND_IN_PROCESS = 3

/**
 * The `WuiDragPayload` handle an in-flight WaterUI drag carries. Android keeps
 * it as the drag's local state so drop destinations in this process hand it
 * back to `dropDestinationAccepts`/`dropDestinationOnDrop` — for an
 * `InProcess` payload it is the only form the value ever takes.
 */
private class LocalDrag(val payloadPtr: Long)

/**
 * The clip a payload writes for the platform leg of a drag. An `InProcess`
 * payload has no clip form; the placeholder is inert and the real value rides
 * in `localState`.
 */
private fun payloadClipData(context: Context, payloadPtr: Long): ClipData =
    when (WatcherJni.dragPayloadKind(payloadPtr)) {
        TRANSFER_KIND_URL -> {
            val url = WatcherJni.dragPayloadUrl(payloadPtr)
            ClipData.newUri(context.contentResolver, url, url.toUri())
        }
        TRANSFER_KIND_FILES -> {
            val files = WatcherJni.dragPayloadFiles(payloadPtr)
            if (files.isEmpty()) {
                ClipData.newPlainText(null, "")
            } else {
                ClipData.newUri(context.contentResolver, null, files.first().toUri()).apply {
                    files.drop(1).forEach { addItem(ClipData.Item(it.toUri())) }
                }
            }
        }
        TRANSFER_KIND_TEXT -> {
            val text = WatcherJni.dragPayloadText(payloadPtr)
            ClipData.newPlainText(text, text)
        }
        else -> ClipData.newPlainText(null, "")
    }

/**
 * Whether a foreign drag's clip description could carry a payload of `kind`.
 * Clip contents are only readable at `ACTION_DROP`, so this is the hover-time
 * approximation of `dropDestinationAccepts`; delivery re-checks for real.
 */
private fun clipMatchesKind(description: ClipDescription?, kind: Int): Boolean {
    if (description == null) return false
    return when (kind) {
        TRANSFER_KIND_TEXT -> description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
        TRANSFER_KIND_URL -> description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)
        TRANSFER_KIND_FILES -> description.mimeTypeCount > 0
        else -> false
    }
}

/**
 * The payload a dropped `event` carries for a destination accepting `kind`:
 * the stashed handle for a WaterUI drag, else one built from the clip. `0`
 * when the clip holds nothing of that kind.
 */
private fun dropPayload(event: DragEvent, context: Context, kind: Int): Long {
    (event.localState as? LocalDrag)?.let { return it.payloadPtr }
    val clip = event.clipData ?: return 0L
    return when (kind) {
        TRANSFER_KIND_FILES -> {
            val uris = (0 until clip.itemCount)
                .mapNotNull { clip.getItemAt(it).uri }
                .filter { it.isAbsolute }
                .map { it.toString() }
                .toTypedArray()
            if (uris.isEmpty()) 0L else WatcherJni.dragPayloadFromFiles(uris)
        }
        TRANSFER_KIND_URL -> {
            val uri = (0 until clip.itemCount)
                .mapNotNull { clip.getItemAt(it).uri }
                .firstOrNull { it.isAbsolute }
            if (uri == null) 0L else WatcherJni.dragPayloadFromUrl(uri.toString())
        }
        TRANSFER_KIND_TEXT -> (0 until clip.itemCount)
            .firstNotNullOfOrNull { clip.getItemAt(it).coerceToText(context)?.toString() }
            ?.let { WatcherJni.dragPayloadFromText(it) }
            ?: 0L
        else -> 0L
    }
}

private val metadataDraggableRenderer = WuiRenderer { context, node, env, registry ->
    val metadata = WatcherJni.forceAsMetadataDraggable(node.rawPtr)
    PassThroughFrameLayout(context)
        .attachMetadataContent(context, metadata.contentPtr, env, registry)
        .apply {
            isLongClickable = true
            setOnLongClickListener { view ->
                val payloadPtr = WatcherJni.draggablePayload(metadata.draggablePtr)
                val inProcess = WatcherJni.dragPayloadKind(payloadPtr) == TRANSFER_KIND_IN_PROCESS
                view.startDragAndDrop(
                    payloadClipData(context, payloadPtr),
                    View.DragShadowBuilder(view),
                    LocalDrag(payloadPtr),
                    if (inProcess) 0 else View.DRAG_FLAG_GLOBAL,
                )
            }
            // The payload handle is owned for the drag's duration; DRAG_ENDED
            // releases it whether the drop landed or not.
            setOnDragListener { _, event ->
                if (event.action == DragEvent.ACTION_DRAG_ENDED) {
                    (event.localState as? LocalDrag)?.let {
                        WatcherJni.dropDragPayload(it.payloadPtr)
                    }
                }
                true
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
            val destinationPtr = metadata.destinationPtr
            var hovering = false

            fun acceptsDrag(event: DragEvent): Boolean =
                when (val local = event.localState) {
                    is LocalDrag ->
                        WatcherJni.dropDestinationAccepts(destinationPtr, local.payloadPtr)
                    else -> clipMatchesKind(event.clipDescription, metadata.acceptedKind)
                }

            setOnDragListener { _, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> acceptsDrag(event)
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        hovering = acceptsDrag(event)
                        if (hovering) {
                            WatcherJni.dropDestinationOnEnter(destinationPtr, envPtr)
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        if (hovering) {
                            hovering = false
                            WatcherJni.dropDestinationOnExit(destinationPtr, envPtr)
                        }
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        val payloadPtr = dropPayload(event, context, metadata.acceptedKind)
                        val accepted = payloadPtr != 0L &&
                            WatcherJni.dropDestinationAccepts(destinationPtr, payloadPtr)
                        if (accepted) {
                            WatcherJni.dropDestinationOnDrop(destinationPtr, envPtr, payloadPtr)
                        }
                        // A payload built for this drop is released here; a
                        // LocalDrag handle belongs to the drag source until
                        // DRAG_ENDED.
                        if (payloadPtr != 0L && event.localState !is LocalDrag) {
                            WatcherJni.dropDragPayload(payloadPtr)
                        }
                        hovering = false
                        accepted
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
