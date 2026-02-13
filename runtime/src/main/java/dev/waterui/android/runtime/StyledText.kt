package dev.waterui.android.runtime

import dev.waterui.android.ffi.WatcherJni
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.widget.TextView
import java.io.Closeable
import kotlin.math.roundToInt

internal fun StyledStrStruct.toModel(): WuiStyledStr {
    val chunkModels = chunks.map { chunk ->
        StyledChunk(
            text = chunk.text,
            style = chunk.style.toModel()
        )
    }
    return WuiStyledStr(chunkModels)
}

internal fun StyledStrStruct.toPlainText(): String {
    if (chunks.isEmpty()) return ""
    return buildString {
        chunks.forEach { append(it.text) }
    }
}

internal fun styledPlain(text: String): StyledStrStruct {
    val plainStyle = TextStyleStruct(
        fontPtr = 0L,
        italic = false,
        underline = false,
        strikethrough = false,
        foregroundPtr = 0L,
        backgroundPtr = 0L
    )
    return StyledStrStruct(arrayOf(StyledChunkStruct(text, plainStyle)))
}

internal fun editableToStyled(editable: Editable, textView: TextView): StyledStrStruct {
    val text = editable.toString()
    if (text.isEmpty()) {
        return styledPlain("")
    }

    val boundaries = sortedSetOf(0, text.length)
    collectBoundaries(editable, text.length, StyleSpan::class.java, boundaries)
    collectBoundaries(editable, text.length, UnderlineSpan::class.java, boundaries)
    collectBoundaries(editable, text.length, StrikethroughSpan::class.java, boundaries)
    collectBoundaries(editable, text.length, ForegroundColorSpan::class.java, boundaries)
    collectBoundaries(editable, text.length, BackgroundColorSpan::class.java, boundaries)
    collectBoundaries(editable, text.length, AbsoluteSizeSpan::class.java, boundaries)
    collectBoundaries(editable, text.length, RelativeSizeSpan::class.java, boundaries)
    collectBoundaries(editable, text.length, TypefaceSpan::class.java, boundaries)

    val cuts = boundaries.toList()
    val chunks = ArrayList<StyledChunkStruct>(cuts.size)
    for (i in 0 until cuts.size - 1) {
        val start = cuts[i]
        val end = cuts[i + 1]
        if (start >= end) continue
        val piece = text.substring(start, end)
        chunks += StyledChunkStruct(piece, styleAt(editable, textView, start, end))
    }

    if (chunks.isEmpty()) {
        return styledPlain(text)
    }

    return StyledStrStruct(chunks.toTypedArray())
}

private fun <T> collectBoundaries(
    editable: Editable,
    textLength: Int,
    klass: Class<T>,
    boundaries: MutableSet<Int>
) {
    editable.getSpans(0, textLength, klass).forEach { span ->
        val start = editable.getSpanStart(span).coerceIn(0, textLength)
        val end = editable.getSpanEnd(span).coerceIn(0, textLength)
        boundaries += start
        boundaries += end
    }
}

private fun styleAt(
    editable: Editable,
    textView: TextView,
    start: Int,
    end: Int
): TextStyleStruct {
    val scaledDensity = textView.resources.displayMetrics.scaledDensity
    val baseTypeface = textView.typeface

    var italic = baseTypeface?.isItalic ?: false
    var weight = baseTypeface?.let(::wuiWeightFromTypeface) ?: FONT_WEIGHT_NORMAL
    var underline = false
    var strikethrough = false

    var foregroundPtr = 0L
    var backgroundPtr = 0L

    var sizeSp = (textView.textSize / scaledDensity).coerceAtLeast(1f)
    var family = ""

    editable.getSpans(start, end, StyleSpan::class.java).forEach { span ->
        when (span.style) {
            Typeface.BOLD -> weight = maxOf(weight, FONT_WEIGHT_BOLD)
            Typeface.ITALIC -> italic = true
            Typeface.BOLD_ITALIC -> {
                weight = maxOf(weight, FONT_WEIGHT_BOLD)
                italic = true
            }
        }
    }

    editable.getSpans(start, end, UnderlineSpan::class.java).forEach { _ ->
        underline = true
    }
    editable.getSpans(start, end, StrikethroughSpan::class.java).forEach { _ ->
        strikethrough = true
    }

    editable.getSpans(start, end, AbsoluteSizeSpan::class.java).forEach { span ->
        sizeSp = if (span.dip) {
            span.size.toFloat().coerceAtLeast(1f)
        } else {
            (span.size / scaledDensity).coerceAtLeast(1f)
        }
    }

    editable.getSpans(start, end, RelativeSizeSpan::class.java).forEach { span ->
        sizeSp = (sizeSp * span.sizeChange).coerceAtLeast(1f)
    }

    editable.getSpans(start, end, TypefaceSpan::class.java).forEach { span ->
        val candidate = span.family.orEmpty()
        if (candidate.isNotEmpty()) {
            family = candidate
        }
    }

    editable.getSpans(start, end, ForegroundColorSpan::class.java).forEach { span ->
        foregroundPtr = colorFromArgb(span.foregroundColor)
    }

    editable.getSpans(start, end, BackgroundColorSpan::class.java).forEach { span ->
        backgroundPtr = colorFromArgb(span.backgroundColor)
    }

    val fontPtr = WatcherJni.fontFromResolved(sizeSp, weight, family)

    return TextStyleStruct(
        fontPtr = fontPtr,
        italic = italic,
        underline = underline,
        strikethrough = strikethrough,
        foregroundPtr = foregroundPtr,
        backgroundPtr = backgroundPtr
    )
}

private fun colorFromArgb(color: Int): Long {
    val alpha = ((color ushr 24) and 0xFF) / 255f
    val red = ((color ushr 16) and 0xFF) / 255f
    val green = ((color ushr 8) and 0xFF) / 255f
    val blue = (color and 0xFF) / 255f
    return WatcherJni.colorFromSrgba(red, green, blue, alpha)
}

private fun wuiWeightFromTypeface(typeface: Typeface): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return androidWeightToWui(typeface.weight)
    }
    return if (typeface.isBold) FONT_WEIGHT_BOLD else FONT_WEIGHT_NORMAL
}

private fun androidWeightToWui(androidWeight: Int): Int {
    return when {
        androidWeight <= 150 -> FONT_WEIGHT_THIN
        androidWeight <= 250 -> FONT_WEIGHT_ULTRA_LIGHT
        androidWeight <= 350 -> FONT_WEIGHT_LIGHT
        androidWeight <= 450 -> FONT_WEIGHT_NORMAL
        androidWeight <= 550 -> FONT_WEIGHT_MEDIUM
        androidWeight <= 650 -> FONT_WEIGHT_SEMI_BOLD
        androidWeight <= 750 -> FONT_WEIGHT_BOLD
        androidWeight <= 850 -> FONT_WEIGHT_ULTRA_BOLD
        else -> FONT_WEIGHT_BLACK
    }
}

private const val FONT_WEIGHT_THIN = 0
private const val FONT_WEIGHT_ULTRA_LIGHT = 1
private const val FONT_WEIGHT_LIGHT = 2
private const val FONT_WEIGHT_NORMAL = 3
private const val FONT_WEIGHT_MEDIUM = 4
private const val FONT_WEIGHT_SEMI_BOLD = 5
private const val FONT_WEIGHT_BOLD = 6
private const val FONT_WEIGHT_ULTRA_BOLD = 7
private const val FONT_WEIGHT_BLACK = 8

class WuiStyledStr internal constructor(
    private val chunks: List<StyledChunk>
) : Closeable {

    fun toCharSequence(env: WuiEnvironment): CharSequence {
        val builder = SpannableStringBuilder()
        chunks.forEach { chunk ->
            val start = builder.length
            builder.append(chunk.text)
            val end = builder.length
            if (start != end) {
                chunk.style.applySpans(env, builder, start, end)
            }
        }
        return builder
    }

    override fun close() {
        chunks.forEach { it.close() }
    }
}

internal class StyledChunk(
    val text: String,
    val style: StyledTextStyle
) : Closeable {
    override fun close() {
        style.close()
    }
}

internal class StyledTextStyle(
    private val font: WuiFont,
    private val italic: Boolean,
    private val underline: Boolean,
    private val strikethrough: Boolean,
    private val foreground: WuiColor?,
    private val background: WuiColor?
) : Closeable {

    fun applySpans(env: WuiEnvironment, builder: SpannableStringBuilder, start: Int, end: Int) {
        val resolvedFont = font.resolveOnce(env)
        val typefaceStyle = resolveTypefaceStyle(resolvedFont.weight, italic)
        if (typefaceStyle != Typeface.NORMAL) {
            builder.setSpan(StyleSpan(typefaceStyle), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        builder.setSpan(AbsoluteSizeSpan(resolvedFont.size.roundToInt(), true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val foregroundColor = foreground?.resolveOnce(env)?.toColorInt()
        if (foregroundColor != null) {
            builder.setSpan(ForegroundColorSpan(foregroundColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val backgroundColor = background?.resolveOnce(env)?.toColorInt()
        if (backgroundColor != null) {
            builder.setSpan(BackgroundColorSpan(backgroundColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (underline) {
            builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (strikethrough) {
            builder.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun resolveTypefaceStyle(weight: Int, italic: Boolean): Int {
        val isBold = weight >= 5
        return when {
            isBold && italic -> Typeface.BOLD_ITALIC
            isBold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
    }

    override fun close() {
        foreground?.close()
        if (background !== foreground) {
            background?.close()
        }
        font.close()
    }
}

internal class WuiFont(
    handle: Long
) : NativePointer(handle) {

    fun resolveOnce(env: WuiEnvironment): ResolvedFontStruct {
        val computedPtr = WatcherJni.resolveFont(raw(), env.raw())
        val resolved = WatcherJni.readComputedResolvedFont(computedPtr)
        WatcherJni.dropComputedResolvedFont(computedPtr)
        return resolved
    }

    override fun release(ptr: Long) {
        if (ptr != 0L) {
            WatcherJni.dropFont(ptr)
        }
    }
}

internal class WuiColor(
    handle: Long
) : NativePointer(handle) {

    fun resolveOnce(env: WuiEnvironment): ResolvedColorStruct {
        val computedPtr = WatcherJni.resolveColor(raw(), env.raw())
        val color = WatcherJni.readComputedResolvedColor(computedPtr)
        WatcherJni.dropComputedResolvedColor(computedPtr)
        return color
    }

    override fun release(ptr: Long) {
        if (ptr != 0L) {
            WatcherJni.dropColor(ptr)
        }
    }
}

private fun TextStyleStruct.toModel(): StyledTextStyle {
    val foregroundColor = foregroundPtr.takeIf { it != 0L }?.let(::WuiColor)
    val backgroundColor = when {
        backgroundPtr == 0L -> null
        backgroundPtr == foregroundPtr && foregroundColor != null -> foregroundColor
        else -> WuiColor(backgroundPtr)
    }
    return StyledTextStyle(
        font = WuiFont(fontPtr),
        italic = italic,
        underline = underline,
        strikethrough = strikethrough,
        foreground = foregroundColor,
        background = backgroundColor
    )
}

fun ResolvedFontStruct.toTypeface(): Typeface {
    val weightValue = when (weight) {
        0 -> 100
        1 -> 200
        2 -> 300
        3 -> 400
        4 -> 500
        5 -> 600
        6 -> 700
        7 -> 800
        8 -> 900
        else -> 400
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(Typeface.DEFAULT, weightValue, false)
    } else {
        val style = if (weightValue >= 600) Typeface.BOLD else Typeface.NORMAL
        Typeface.create(Typeface.DEFAULT, style)
    }
}
