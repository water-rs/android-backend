package dev.waterui.android.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.nio.ByteBuffer

/**
 * Platform image decoder bridge used by Rust Photo/Image paths.
 *
 * - AV1/AVIF: prefer platform decoder (hardware-accelerated when available)
 * - HEVC/HEIF: platform decoder only (no Rust software fallback)
 *
 * Returns packed payload:
 * [0..4)  : width  (u32 little-endian)
 * [4..8)  : height (u32 little-endian)
 * [8..]   : RGBA8 pixel bytes
 */
object ImageCodecBridge {
    @JvmStatic
    fun decodeToRgbaPacked(bytes: ByteArray): ByteArray? {
        val decoded = decodeBitmap(bytes) ?: return null
        val bitmap = if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        }

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val payload = ByteArray(8 + argb.size * 4)
        putU32Le(payload, 0, width)
        putU32Le(payload, 4, height)

        var out = 8
        for (value in argb) {
            payload[out] = ((value ushr 16) and 0xFF).toByte() // R
            payload[out + 1] = ((value ushr 8) and 0xFF).toByte() // G
            payload[out + 2] = (value and 0xFF).toByte() // B
            payload[out + 3] = ((value ushr 24) and 0xFF).toByte() // A
            out += 4
        }
        return payload
    }

    private fun decodeBitmap(bytes: ByteArray): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    // Let the framework choose best codec path; we only normalize output later.
                    decoder.isMutableRequired = false
                }
            } else {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun putU32Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
