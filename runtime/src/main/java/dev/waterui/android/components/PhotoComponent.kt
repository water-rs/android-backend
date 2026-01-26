package dev.waterui.android.components

import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.ImageView
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.LivePhotoSourceStruct
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.dp
import dev.waterui.android.runtime.disposeWith
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

    var sourceComputed: WuiComputed<LivePhotoSourceStruct>? = null

    val imageView = object : ImageView(context) {
        private var loadJob: Job? = null
        private val defaultSizePx: Int = 200f.dp(context).toInt()

        init {
            scaleType = ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Watch live photo source and render its image URL.
            if (struct.sourcePtr != 0L) {
                sourceComputed = WuiComputed.livePhotoSource(struct.sourcePtr, env).also { computed ->
                    computed.observe { source ->
                        loadImage(source.image)
                    }
                }
            }
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

            val measuredWidth = when (widthMode) {
                MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> widthSize
                else -> defaultSizePx
            }

            val measuredHeight = when (heightMode) {
                MeasureSpec.EXACTLY, MeasureSpec.AT_MOST -> heightSize
                else -> defaultSizePx
            }

            setMeasuredDimension(measuredWidth, measuredHeight)
        }
    }

    imageView.disposeWith {
        sourceComputed?.close()
        sourceComputed = null
    }

    imageView
}

internal fun RegistryBuilder.registerWuiPhoto() {
    register({ photoTypeId }, photoRenderer)
}
