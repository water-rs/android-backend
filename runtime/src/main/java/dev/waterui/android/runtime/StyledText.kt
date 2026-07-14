package dev.waterui.android.runtime

import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.widget.TextView
import dev.waterui.android.reactive.WuiComputed
import java.io.Closeable
import kotlin.math.roundToInt

internal fun StyledStrStruct.toModel(): WuiStyledStr = WuiStyledStr(
    chunks.map { chunk -> StyledChunk(chunk.text, chunk.style.toModel()) }
)

class WuiStyledStr internal constructor(
    private val chunks: List<StyledChunk>
) : Closeable {
    fun bind(env: WuiEnvironment, onValue: (CharSequence) -> Unit): Closeable =
        ResolvedStyledTextBinding(chunks, env, onValue)

    override fun close() {
        chunks.forEach(StyledChunk::close)
    }
}

internal class ReactiveStyledText(
    computedPtr: Long,
    env: WuiEnvironment
) : Closeable {
    private val computed = WuiComputed.styledString(computedPtr)
    private var resolvedBinding: Closeable? = null
    private var value: CharSequence = ""
    private var updateNative: (CharSequence) -> Unit = {}

    init {
        computed.observe { styled ->
            resolvedBinding?.close()
            resolvedBinding = styled.bind(env) { resolved ->
                value = resolved
                updateNative(resolved)
            }
        }
    }

    fun attach(update: (CharSequence) -> Unit) {
        updateNative = update
        update(value)
    }

    fun detach() {
        updateNative = {}
    }

    override fun close() {
        detach()
        resolvedBinding?.close()
        resolvedBinding = null
        computed.close()
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
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
    private val foreground: WuiColor?,
    private val background: WuiColor?
) : Closeable {
    fun resolve(env: WuiEnvironment, onChange: () -> Unit): ResolvedStyledTextStyle =
        ResolvedStyledTextStyle(
            font = font.resolve(env),
            italic = italic,
            underline = underline,
            strikethrough = strikethrough,
            foreground = foreground?.resolve(env),
            background = when {
                background == null -> null
                background === foreground -> null
                else -> background.resolve(env)
            },
            sharedForegroundAndBackground = foreground != null && foreground === background,
            onChange = onChange
        )

    override fun close() {
        foreground?.close()
        if (background !== foreground) {
            background?.close()
        }
        font.close()
    }
}

private class ResolvedStyledTextBinding(
    chunks: List<StyledChunk>,
    env: WuiEnvironment,
    private val onValue: (CharSequence) -> Unit
) : Closeable {
    private var initializing = true
    private val chunks = chunks.map { chunk ->
        ResolvedStyledChunk(chunk.text, chunk.style.resolve(env, ::publishIfReady))
    }

    init {
        initializing = false
        publish()
    }

    private fun publishIfReady() {
        if (!initializing) {
            publish()
        }
    }

    private fun publish() {
        val builder = SpannableStringBuilder()
        chunks.forEach { chunk -> chunk.appendTo(builder) }
        onValue(builder)
    }

    override fun close() {
        chunks.forEach(ResolvedStyledChunk::close)
    }
}

private data class ResolvedStyledChunk(
    val text: String,
    val style: ResolvedStyledTextStyle
) : Closeable {
    fun appendTo(builder: SpannableStringBuilder) {
        val start = builder.length
        builder.append(text)
        val end = builder.length
        if (start != end) {
            style.applySpans(builder, start, end)
        }
    }

    override fun close() {
        style.close()
    }
}

internal class ResolvedStyledTextStyle(
    font: WuiComputed<ResolvedFontStruct>,
    private val italic: Boolean,
    private val underline: Boolean,
    private val strikethrough: Boolean,
    foreground: WuiComputed<ResolvedColorStruct>?,
    background: WuiComputed<ResolvedColorStruct>?,
    private val sharedForegroundAndBackground: Boolean,
    private val onChange: () -> Unit
) : Closeable {
    private val fontSignal = font
    private val foregroundSignal = foreground
    private val backgroundSignal = background
    private var resolvedFont: ResolvedFontStruct? = null
    private var resolvedForeground: ResolvedColorStruct? = null
    private var resolvedBackground: ResolvedColorStruct? = null

    init {
        fontSignal.observe { value ->
            resolvedFont = value
            onChange()
        }
        foregroundSignal?.observe { value ->
            resolvedForeground = value
            if (sharedForegroundAndBackground) {
                resolvedBackground = value
            }
            onChange()
        }
        backgroundSignal?.observe { value ->
            resolvedBackground = value
            onChange()
        }
    }

    fun applySpans(builder: SpannableStringBuilder, start: Int, end: Int) {
        val font = requireNotNull(resolvedFont) { "styled-text font did not resolve synchronously" }
        builder.setSpan(
            ResolvedTypefaceSpan(font, italic),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        builder.setSpan(
            AbsoluteSizeSpan(font.size.roundToInt(), true),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        resolvedForeground?.let { color ->
            builder.setSpan(
                ForegroundColorSpan(color.toColorInt()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        resolvedBackground?.let { color ->
            builder.setSpan(
                BackgroundColorSpan(color.toColorInt()),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (underline) {
            builder.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (strikethrough) {
            builder.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    override fun close() {
        fontSignal.close()
        foregroundSignal?.close()
        backgroundSignal?.close()
    }
}

private class ResolvedTypefaceSpan(
    private val font: ResolvedFontStruct,
    private val italic: Boolean
) : MetricAffectingSpan() {
    override fun updateMeasureState(textPaint: TextPaint) {
        textPaint.typeface = font.toTypeface(italic)
    }

    override fun updateDrawState(textPaint: TextPaint) {
        textPaint.typeface = font.toTypeface(italic)
    }
}

internal class WuiFont(handle: Long) : NativePointer(handle) {
    fun resolve(env: WuiEnvironment): WuiComputed<ResolvedFontStruct> {
        val ptr = NativeBindings.waterui_resolve_font(raw(), env.raw())
        check(ptr != 0L) { "waterui_resolve_font returned a null computed" }
        return WuiComputed.fontFromComputed(ptr)
    }

    override fun release(ptr: Long) {
        NativeBindings.waterui_drop_font(ptr)
    }
}

internal class WuiColor(handle: Long) : NativePointer(handle) {
    fun resolve(env: WuiEnvironment): WuiComputed<ResolvedColorStruct> {
        val ptr = NativeBindings.waterui_resolve_color(raw(), env.raw())
        check(ptr != 0L) { "waterui_resolve_color returned a null computed" }
        return WuiComputed.colorFromComputed(ptr)
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
    require(fontPtr != 0L) { "styled-text font pointer is null" }
    return StyledTextStyle(
        font = WuiFont(fontPtr),
        italic = italic,
        underline = underline,
        strikethrough = strikethrough,
        foreground = foregroundColor,
        background = backgroundColor
    )
}

internal fun TextView.applyResolvedFont(font: ResolvedFontStruct) {
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, font.size)
    typeface = font.toTypeface()
}

fun ResolvedFontStruct.toTypeface(italic: Boolean = false): Typeface {
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
        else -> error("unknown font weight: $weight")
    }
    val base = family?.let { familyName ->
        Typeface.create(familyName, Typeface.NORMAL)
    } ?: Typeface.DEFAULT
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(base, weightValue, italic)
    } else {
        val style = when {
            weightValue >= 600 && italic -> Typeface.BOLD_ITALIC
            weightValue >= 600 -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        Typeface.create(base, style)
    }
}
