package dev.waterui.android.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.createBitmap
import dev.waterui.android.reactive.WuiComputed
import dev.waterui.android.runtime.BitmapStruct
import dev.waterui.android.runtime.NativeBindings
import dev.waterui.android.runtime.PictureStruct
import dev.waterui.android.runtime.RegistryBuilder
import dev.waterui.android.runtime.WuiRenderer
import dev.waterui.android.runtime.WuiTypeId
import dev.waterui.android.runtime.disposeWith
import dev.waterui.android.runtime.dp
import kotlin.math.roundToInt

private val pictureTypeId: WuiTypeId by lazy { NativeBindings.waterui_picture_id().toTypeId() }

private val pictureRenderer = WuiRenderer { context, node, _, _ ->
    PictureView(context, NativeBindings.waterui_force_as_picture(node.rawPtr))
}

/**
 * Shows a `Picture` rasterised at the display density. A new drawing rewrites the
 * pixels of the bitmap already on screen, never the view, and nothing here draws
 * per frame.
 */
// The picture this view shows is a native handle it takes ownership of, so it
// has no meaningful context-only constructor and is never inflated from XML.
@SuppressLint("ViewConstructor")
private class PictureView(context: Context, picture: PictureStruct) :
    AppCompatImageView(context) {
    private val widthPx = picture.width.dp(context).roundToInt().coerceAtLeast(1)
    private val heightPx = picture.height.dp(context).roundToInt().coerceAtLeast(1)

    init {
        scaleType = ScaleType.FIT_CENTER
        val density = context.resources.displayMetrics.density
        val bitmaps = WuiComputed.bitmapFromComputed(
            NativeBindings.waterui_picture_bitmap(picture.picturePtr, density)
        )
        bitmaps.observe(::show)
        disposeWith(bitmaps)
        disposeWith { NativeBindings.waterui_drop_picture(picture.picturePtr) }
    }

    /** The bitmap on screen; a re-draw of the same size copies into it in place. */
    private var image: Bitmap? = null

    private fun show(bitmap: BitmapStruct) {
        val target = image?.takeIf { it.width == bitmap.width && it.height == bitmap.height }
            ?: createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                image = it
                setImageBitmap(it)
            }
        bitmap.pixels.rewind()
        target.copyPixelsFromBuffer(bitmap.pixels)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(widthPx, widthMeasureSpec),
            resolveSize(heightPx, heightMeasureSpec)
        )
    }
}

internal fun RegistryBuilder.registerWuiPicture() {
    register({ pictureTypeId }, pictureRenderer)
}
