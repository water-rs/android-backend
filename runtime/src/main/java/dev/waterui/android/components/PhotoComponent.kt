package dev.waterui.android.components

import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.ImageView
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.StretchAxis
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

private val photoTypeId: WuiTypeId by lazy { NativeBindings.waterui_photo_id().toTypeId() }

/**
 * Photo component renderer.
 *
 * Displays an image from a URL using Android's ImageView.
 * Loads the image asynchronously and displays it when ready.
 */
private val photoRenderer = WuiRenderer { context, node, env, registry ->
    val struct = NativeBindings.waterui_force_as_photo(node.rawPtr)

    val imageView = object : ImageView(context) {
        private var loadJob: Job? = null

        init {
            scaleType = ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setTag(TAG_STRETCH_AXIS, StretchAxis.BOTH)

            // Start loading the image
            loadImage(struct.source)
        }

        private fun loadImage(url: String) {
            loadJob?.cancel()
            loadJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    val connection = URL(url).openConnection()
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    val inputStream = connection.getInputStream()
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()

                    withContext(Dispatchers.Main) {
                        if (bitmap != null) {
                            setImageBitmap(bitmap)
                            // TODO: Emit Loaded event
                        } else {
                            // TODO: Emit Error event
                        }
                    }
                } catch (e: Exception) {
                    // TODO: Emit Error event with message
                }
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            loadJob?.cancel()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = MeasureSpec.getSize(heightMeasureSpec)

            val expandedWidthSpec = if (widthMode == MeasureSpec.AT_MOST || widthMode == MeasureSpec.EXACTLY) {
                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY) // Default width
            }

            val expandedHeightSpec = if (heightMode == MeasureSpec.AT_MOST || heightMode == MeasureSpec.EXACTLY) {
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
            } else {
                MeasureSpec.makeMeasureSpec(200, MeasureSpec.EXACTLY) // Default height
            }

            super.onMeasure(expandedWidthSpec, expandedHeightSpec)
        }
    }

    imageView
}

// Tag for stretch axis
private const val TAG_STRETCH_AXIS = 0x7f0f0001

internal fun RegistryBuilder.registerWuiPhoto() {
    register({ photoTypeId }, photoRenderer)
}
