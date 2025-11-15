package dev.waterui.android.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import java.io.Closeable

internal fun StyledStrStruct.toModel(): WuiStyledStr {
    val chunkModels = chunks.map { chunk ->
        WuiStyledChunk(
            text = chunk.text,
            style = chunk.style.toModel()
        )
    }
    return WuiStyledStr(chunkModels)
}

internal class WuiStyledStr(
    val chunks: List<WuiStyledChunk>
) : Closeable {

    fun toAnnotatedString(env: WuiEnvironment): AnnotatedString {
        val builder = AnnotatedString.Builder()
        chunks.forEach { chunk ->
            val start = builder.length
            builder.append(chunk.text)
            val end = builder.length
            if (start != end) {
                builder.addStyle(chunk.style.spanStyle(env), start, end)
            }
        }
        return builder.toAnnotatedString()
    }

    override fun close() {
        chunks.forEach { it.close() }
    }
}

internal class WuiStyledChunk(
    val text: String,
    val style: WuiTextStyle
) : Closeable {
    override fun close() {
        style.close()
    }
}

internal class WuiTextStyle(
    private val font: WuiFont,
    private val italic: Boolean,
    private val underline: Boolean,
    private val strikethrough: Boolean,
    private val foreground: WuiColor?,
    private val background: WuiColor?
) : Closeable {

    fun spanStyle(env: WuiEnvironment): SpanStyle {
        val resolvedFont = font.resolveOnce(env)
        val fontWeight = resolvedFont.toFontWeight()
        val fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal
        val textDecoration = when {
            underline && strikethrough -> TextDecoration.combine(
                listOf(TextDecoration.Underline, TextDecoration.LineThrough)
            )
            underline -> TextDecoration.Underline
            strikethrough -> TextDecoration.LineThrough
            else -> TextDecoration.None
        }

        val foregroundColor = foreground?.resolveOnce(env)?.toComposeColor() ?: Color.Unspecified
        val backgroundColor = background?.resolveOnce(env)?.toComposeColor() ?: Color.Unspecified

        return SpanStyle(
            color = foregroundColor,
            background = backgroundColor,
            fontSize = resolvedFont.size.sp,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textDecoration = textDecoration
        )
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

private fun TextStyleStruct.toModel(): WuiTextStyle {
    val foregroundColor = foregroundPtr.takeIf { it != 0L }?.let(::WuiColor)
    val backgroundColor = when {
        backgroundPtr == 0L -> null
        backgroundPtr == foregroundPtr && foregroundColor != null -> foregroundColor
        else -> WuiColor(backgroundPtr)
    }
    return WuiTextStyle(
        font = WuiFont(fontPtr),
        italic = italic,
        underline = underline,
        strikethrough = strikethrough,
        foreground = foregroundColor,
        background = backgroundColor
    )
}

private fun ResolvedColorStruct.toComposeColor(): Color =
    Color(red = red, green = green, blue = blue, alpha = opacity)

private fun ResolvedFontStruct.toFontWeight(): FontWeight {
    return when (weight) {
        0 -> FontWeight.Thin
        1 -> FontWeight.ExtraLight
        2 -> FontWeight.Light
        3 -> FontWeight.Normal
        4 -> FontWeight.Medium
        5 -> FontWeight.SemiBold
        6 -> FontWeight.Bold
        7 -> FontWeight.ExtraBold
        8 -> FontWeight.Black
        else -> FontWeight.Normal
    }
}
