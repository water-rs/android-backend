package dev.waterui.android.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
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
 * [8]     : pixel format (u8): 0=RGBA8, 1=RGBA16F
 * [9]     : hdr flag (u8): 0=SDR, 1=HDR
 * [10..12): reserved
 * [12..]  : pixel bytes
 */
object ImageCodecBridge {
    @JvmStatic
    fun decodeToRgbaPacked(bytes: ByteArray): ByteArray? {
        return decodeToPackedImageV2(bytes, false)
    }

    @JvmStatic
    fun decodeToPackedImageV2(bytes: ByteArray, preferHdr: Boolean): ByteArray? {
        val decoded = decodeBitmap(bytes) ?: return null
        val bitmap = if (preferHdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            decoded.copy(Bitmap.Config.RGBA_F16, false) ?: return null
        } else if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        }

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return null
        }

        val isHdrBitmap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.RGBA_F16 &&
            bitmap.colorSpace?.isWideGamut == true

        return if (isHdrBitmap) {
            packRgba16f(bitmap)
        } else {
            packRgba8(bitmap)
        }
    }

    private fun packRgba8(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val payload = ByteArray(12 + argb.size * 4)
        putU32Le(payload, 0, width)
        putU32Le(payload, 4, height)
        payload[8] = 0 // RGBA8
        payload[9] = 0 // SDR
        payload[10] = 0
        payload[11] = 0

        var out = 12
        for (value in argb) {
            payload[out] = ((value ushr 16) and 0xFF).toByte() // R
            payload[out + 1] = ((value ushr 8) and 0xFF).toByte() // G
            payload[out + 2] = (value and 0xFF).toByte() // B
            payload[out + 3] = ((value ushr 24) and 0xFF).toByte() // A
            out += 4
        }
        return payload
    }

    private fun packRgba16f(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val payload = ByteArray(12 + width * height * 8)
        putU32Le(payload, 0, width)
        putU32Le(payload, 4, height)
        payload[8] = 1 // RGBA16F
        payload[9] = 1 // HDR
        payload[10] = 0
        payload[11] = 0

        var out = 12
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = bitmap.getColor(x, y)
                val r = android.util.Half.toHalf(c.red())
                val g = android.util.Half.toHalf(c.green())
                val b = android.util.Half.toHalf(c.blue())
                val a = android.util.Half.toHalf(c.alpha())
                putU16Le(payload, out, r.toInt())
                putU16Le(payload, out + 2, g.toInt())
                putU16Le(payload, out + 4, b.toInt())
                putU16Le(payload, out + 6, a.toInt())
                out += 8
            }
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
                    }
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

    private fun putU16Le(dst: ByteArray, offset: Int, value: Int) {
        dst[offset] = (value and 0xFF).toByte()
        dst[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
