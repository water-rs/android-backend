package dev.waterui.android.runtime

import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
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
        val computedPtr = NativeBindings.waterui_resolve_font(raw(), env.raw())
        if (computedPtr == 0L) {
            return ResolvedFontStruct(size = 14f, weight = 3)
        }
        val resolved = NativeBindings.waterui_read_computed_resolved_font(computedPtr)
        NativeBindings.waterui_drop_computed_resolved_font(computedPtr)
        return resolved
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_drop_font(ptr)
    }
}

internal class WuiColor(
    handle: Long
) : NativePointer(handle) {

    fun resolveOnce(env: WuiEnvironment): ResolvedColorStruct {
        val computedPtr = NativeBindings.waterui_resolve_color(raw(), env.raw())
        if (computedPtr == 0L) {
            return ResolvedColorStruct(0f, 0f, 0f, 1f)
        }
        val color = NativeBindings.waterui_read_computed_resolved_color(computedPtr)
        NativeBindings.waterui_drop_computed_resolved_color(computedPtr)
        return color
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_drop_color(ptr)
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
