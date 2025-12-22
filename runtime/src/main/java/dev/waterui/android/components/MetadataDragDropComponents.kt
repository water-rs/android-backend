package dev.waterui.android.components

import android.content.ClipData
import android.content.ClipDescription
import android.view.DragEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import dev.waterui.android.layout.PassThroughFrameLayout
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.TAG_STRETCH_AXIS
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.getWuiStretchAxis
import dev.waterui.android.runtime.inflateAnyView

// ========== Metadata<Draggable> ==========

private val metadataDraggableTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_draggable_id().toTypeId()
}

/**
 * Renderer for Metadata<Draggable>.
 *
 * Makes the wrapped view draggable for native drag and drop operations.
 * Uses long-press to initiate drag on Android.
 */
private val metadataDraggableRenderer = WuiRenderer { context, node, env, registry ->
    val draggableData = NativeBindings.waterui_force_as_metadata_draggable(node.rawPtr)
    
    val container = PassThroughFrameLayout(context).apply {
        consumesTouches = true
        setTag(PassThroughFrameLayout.TAG_WANTS_TOUCHES, true)
    }
    
    // Inflate the content
    if (draggableData.contentPtr != 0L) {
        val child = inflateAnyView(context, draggableData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }
    
    // Long press gesture to initiate drag
    val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        
        override fun onLongPress(e: MotionEvent) {
            // Get the drag data from Rust
            val dragData = NativeBindings.waterui_draggable_get_data(draggableData.draggablePtr)
            
            // Create ClipData based on data type
            val clipData = when (dragData.tag) {
                DragDataTag.URL -> {
                    val uri = android.net.Uri.parse(dragData.value)
                    ClipData.newUri(context.contentResolver, "URI", uri)
                }
                else -> { // Text
                    ClipData.newPlainText("text", dragData.value)
                }
            }
            
            // Create drag shadow
            val shadowBuilder = View.DragShadowBuilder(container)
            
            // Start drag - use startDragAndDrop for API 24+
            @Suppress("DEPRECATION")
            container.startDragAndDrop(
                clipData,
                shadowBuilder,
                null, // Local state
                View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
            )
        }
    })
    
    container.setOnTouchListener { _, event ->
        gestureDetector.onTouchEvent(event)
        false // Allow other touch handling
    }
    
    // Cleanup
    container.disposeWith {
        NativeBindings.waterui_drop_draggable(draggableData.draggablePtr)
    }
    
    container
}

// ========== Metadata<DropDestination> ==========

private val metadataDropDestinationTypeId: WuiTypeId by lazy {
    NativeBindings.waterui_metadata_drop_destination_id().toTypeId()
}

/**
 * Renderer for Metadata<DropDestination>.
 *
 * Makes the wrapped view a drop destination for drag and drop operations.
 * Uses OnDragListener to handle drop events.
 */
private val metadataDropDestinationRenderer = WuiRenderer { context, node, env, registry ->
    val dropDestData = NativeBindings.waterui_force_as_metadata_drop_destination(node.rawPtr)
    val envPtr = env.raw()
    
    val container = PassThroughFrameLayout(context)
    
    // Inflate the content
    if (dropDestData.contentPtr != 0L) {
        val child = inflateAnyView(context, dropDestData.contentPtr, env, registry)
        container.addView(child)
        container.setTag(TAG_STRETCH_AXIS, child.getWuiStretchAxis())
    }
    
    // Set up drag listener
    container.setOnDragListener { _, event ->
        when (event.action) {
            DragEvent.ACTION_DRAG_ENTERED -> {
                NativeBindings.waterui_call_drop_enter_handler(
                    dropDestData.dropDestPtr,
                    envPtr
                )
                true
            }
            
            DragEvent.ACTION_DRAG_EXITED -> {
                NativeBindings.waterui_call_drop_exit_handler(
                    dropDestData.dropDestPtr,
                    envPtr
                )
                true
            }
            
            DragEvent.ACTION_DROP -> {
                val clipData = event.clipData
                if (clipData != null && clipData.itemCount > 0) {
                    val item = clipData.getItemAt(0)
                    val description = clipData.description
                    
                    // Determine tag and value
                    val (tag, value) = when {
                        description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) ||
                        description.hasMimeType("text/uri-list") -> {
                            val uri = item.uri?.toString() ?: item.text?.toString() ?: ""
                            Pair(DragDataTag.URL, uri)
                        }
                        else -> {
                            val text = item.coerceToText(context).toString()
                            Pair(DragDataTag.TEXT, text)
                        }
                    }
                    
                    NativeBindings.waterui_call_drop_handler(
                        dropDestData.dropDestPtr,
                        envPtr,
                        tag.ordinal,
                        value
                    )
                    true
                } else {
                    false
                }
            }
            
            DragEvent.ACTION_DRAG_STARTED -> {
                // Accept all drags that have text or URI
                val clipDesc = event.clipDescription
                clipDesc != null && (
                    clipDesc.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
                    clipDesc.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) ||
                    clipDesc.hasMimeType("text/uri-list")
                )
            }
            
            else -> true
        }
    }
    
    // Cleanup
    container.disposeWith {
        NativeBindings.waterui_drop_drop_destination(dropDestData.dropDestPtr)
    }
    
    container
}

// ========== Data Types ==========

/**
 * Represents the type of drag data.
 */
enum class DragDataTag {
    TEXT,
    URL
}

/**
 * Result struct from getting drag data.
 */
data class DragDataStruct(
    val tag: DragDataTag,
    val value: String
)

/**
 * Result struct from force_as_metadata_draggable.
 */
data class MetadataDraggableStruct(
    val contentPtr: Long,
    val draggablePtr: Long
)

/**
 * Result struct from force_as_metadata_drop_destination.
 */
data class MetadataDropDestinationStruct(
    val contentPtr: Long,
    val dropDestPtr: Long
)

// ========== Registration ==========

internal fun RegistryBuilder.registerWuiDraggable() {
    registerMetadata({ metadataDraggableTypeId }, metadataDraggableRenderer)
}

internal fun RegistryBuilder.registerWuiDropDestination() {
    registerMetadata({ metadataDropDestinationTypeId }, metadataDropDestinationRenderer)
}
