package dev.waterui.android.components

import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import dev.waterui.android.runtime.NativeBindings

/**
 * Keyboard, IME and scroll input for GPU surfaces that ask for it.
 *
 * A `GpuSurface` whose renderer reports `wants_input_events` draws its own
 * interactive content — a browser engine, a terminal, an editor — and needs the
 * events themselves rather than the per-frame pointer snapshot
 * `waterui_gpu_surface_set_input` carries. This file is the Android half of
 * that: it translates `KeyEvent`s and `InputConnection` calls into the
 * backend-neutral surface vocabulary. No Android keycode crosses the ABI — keys
 * travel as their W3C `KeyboardEvent.key` / `.code` names.
 */

internal const val GPU_SURFACE_INPUT_LOG_TAG = "WaterUI.GpuSurfaceInput"

/** The event tag; the ordinals are the native `WuiSurfaceInputEventKind`. */
internal enum class WuiSurfaceInputEventKind {
    Focus,
    Modifiers,
    PointerMove,
    PointerButton,
    Scroll,
    Key,
    TextInput,
    CompositionStart,
    CompositionUpdate,
    CompositionCommit,
    CompositionCancel
}

/** The ordinals are the native `WuiSurfacePointerButton`. */
internal enum class WuiSurfacePointerButton {
    Primary,
    Secondary,
    Middle,
    Back,
    Forward
}

/** The ordinals are the native `WuiScrollUnit`. */
internal enum class WuiScrollUnit {
    Line,
    Pixel
}

internal const val WUI_SURFACE_MODIFIER_SHIFT = 0x200
internal const val WUI_SURFACE_MODIFIER_CONTROL = 0x8
internal const val WUI_SURFACE_MODIFIER_ALT = 0x1
internal const val WUI_SURFACE_MODIFIER_META = 0x40
internal const val WUI_SURFACE_MODIFIER_CAPS_LOCK = 0x4
internal const val WUI_SURFACE_MODIFIER_NUM_LOCK = 0x80

/** The W3C name for a key the model has no name for. */
internal const val WUI_SURFACE_UNIDENTIFIED = "Unidentified"

/** The absent-caret sentinel the carrier's `caret` field uses. */
internal const val WUI_SURFACE_CARET_NONE = -1L

/**
 * Android keycodes to W3C `KeyboardEvent.code` names.
 *
 * The table is the whole reason no platform keycode crosses the ABI: a GPU view
 * asks where a key *sits*, and every backend answers in the same vocabulary.
 */
private val w3cCodes: Map<Int, String> = buildMap {
    for (offset in 0..25) {
        put(KeyEvent.KEYCODE_A + offset, "Key" + ('A' + offset))
    }
    for (digit in 0..9) {
        put(KeyEvent.KEYCODE_0 + digit, "Digit$digit")
        put(KeyEvent.KEYCODE_NUMPAD_0 + digit, "Numpad$digit")
    }
    for (function in 1..12) {
        put(KeyEvent.KEYCODE_F1 + function - 1, "F$function")
    }
    put(KeyEvent.KEYCODE_ENTER, "Enter")
    put(KeyEvent.KEYCODE_TAB, "Tab")
    put(KeyEvent.KEYCODE_SPACE, "Space")
    put(KeyEvent.KEYCODE_DEL, "Backspace")
    put(KeyEvent.KEYCODE_FORWARD_DEL, "Delete")
    put(KeyEvent.KEYCODE_ESCAPE, "Escape")
    put(KeyEvent.KEYCODE_DPAD_UP, "ArrowUp")
    put(KeyEvent.KEYCODE_DPAD_DOWN, "ArrowDown")
    put(KeyEvent.KEYCODE_DPAD_LEFT, "ArrowLeft")
    put(KeyEvent.KEYCODE_DPAD_RIGHT, "ArrowRight")
    put(KeyEvent.KEYCODE_MOVE_HOME, "Home")
    put(KeyEvent.KEYCODE_MOVE_END, "End")
    put(KeyEvent.KEYCODE_PAGE_UP, "PageUp")
    put(KeyEvent.KEYCODE_PAGE_DOWN, "PageDown")
    put(KeyEvent.KEYCODE_INSERT, "Insert")
    put(KeyEvent.KEYCODE_SHIFT_LEFT, "ShiftLeft")
    put(KeyEvent.KEYCODE_SHIFT_RIGHT, "ShiftRight")
    put(KeyEvent.KEYCODE_CTRL_LEFT, "ControlLeft")
    put(KeyEvent.KEYCODE_CTRL_RIGHT, "ControlRight")
    put(KeyEvent.KEYCODE_ALT_LEFT, "AltLeft")
    put(KeyEvent.KEYCODE_ALT_RIGHT, "AltRight")
    put(KeyEvent.KEYCODE_META_LEFT, "OSLeft")
    put(KeyEvent.KEYCODE_META_RIGHT, "OSRight")
    put(KeyEvent.KEYCODE_CAPS_LOCK, "CapsLock")
    put(KeyEvent.KEYCODE_NUM_LOCK, "NumLock")
    put(KeyEvent.KEYCODE_SCROLL_LOCK, "ScrollLock")
    put(KeyEvent.KEYCODE_GRAVE, "Backquote")
    put(KeyEvent.KEYCODE_MINUS, "Minus")
    put(KeyEvent.KEYCODE_EQUALS, "Equal")
    put(KeyEvent.KEYCODE_LEFT_BRACKET, "BracketLeft")
    put(KeyEvent.KEYCODE_RIGHT_BRACKET, "BracketRight")
    put(KeyEvent.KEYCODE_BACKSLASH, "Backslash")
    put(KeyEvent.KEYCODE_SEMICOLON, "Semicolon")
    put(KeyEvent.KEYCODE_APOSTROPHE, "Quote")
    put(KeyEvent.KEYCODE_SLASH, "Slash")
    put(KeyEvent.KEYCODE_COMMA, "Comma")
    put(KeyEvent.KEYCODE_PERIOD, "Period")
    put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, "NumpadDivide")
    put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, "NumpadMultiply")
    put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, "NumpadSubtract")
    put(KeyEvent.KEYCODE_NUMPAD_ADD, "NumpadAdd")
    put(KeyEvent.KEYCODE_NUMPAD_DOT, "NumpadDecimal")
    put(KeyEvent.KEYCODE_NUMPAD_COMMA, "NumpadComma")
    put(KeyEvent.KEYCODE_NUMPAD_ENTER, "NumpadEnter")
    put(KeyEvent.KEYCODE_NUMPAD_EQUALS, "NumpadEqual")
    put(KeyEvent.KEYCODE_MENU, "ContextMenu")
    put(KeyEvent.KEYCODE_BACK, "BrowserBack")
    put(KeyEvent.KEYCODE_FORWARD, "BrowserForward")
    put(KeyEvent.KEYCODE_VOLUME_UP, "VolumeUp")
    put(KeyEvent.KEYCODE_VOLUME_DOWN, "VolumeDown")
    put(KeyEvent.KEYCODE_VOLUME_MUTE, "VolumeMute")
}

/** W3C `key` names for keys that produce no character of their own. */
private val w3cNamedKeys: Map<String, String> = buildMap {
    put("Enter", "Enter")
    put("NumpadEnter", "Enter")
    put("Tab", "Tab")
    put("Backspace", "Backspace")
    put("Delete", "Delete")
    put("Escape", "Escape")
    put("ArrowUp", "ArrowUp")
    put("ArrowDown", "ArrowDown")
    put("ArrowLeft", "ArrowLeft")
    put("ArrowRight", "ArrowRight")
    put("Home", "Home")
    put("End", "End")
    put("PageUp", "PageUp")
    put("PageDown", "PageDown")
    put("Insert", "Insert")
    put("ShiftLeft", "Shift")
    put("ShiftRight", "Shift")
    put("ControlLeft", "Control")
    put("ControlRight", "Control")
    put("AltLeft", "Alt")
    put("AltRight", "Alt")
    put("OSLeft", "Meta")
    put("OSRight", "Meta")
    put("CapsLock", "CapsLock")
    put("NumLock", "NumLock")
    put("ScrollLock", "ScrollLock")
    put("ContextMenu", "ContextMenu")
    put("BrowserBack", "BrowserBack")
    put("BrowserForward", "BrowserForward")
    put("VolumeUp", "AudioVolumeUp")
    put("VolumeDown", "AudioVolumeDown")
    put("VolumeMute", "AudioVolumeMute")
    for (function in 1..12) {
        put("F$function", "F$function")
    }
}

/** The W3C `KeyboardEvent.code` of the physical key this event came from. */
internal fun surfaceCode(event: KeyEvent): String {
    val code = w3cCodes[event.keyCode]
    if (code == null) {
        Log.d(GPU_SURFACE_INPUT_LOG_TAG, "no W3C code for Android keycode ${event.keyCode}")
        return WUI_SURFACE_UNIDENTIFIED
    }
    return code
}

/**
 * The W3C `KeyboardEvent.key` — the value the layout and modifiers produce.
 *
 * A key that types a character reports that character; every other key reports
 * the name the W3C model gives it.
 */
internal fun surfaceKey(event: KeyEvent, code: String): String {
    w3cNamedKeys[code]?.let { return it }
    val unicode = event.unicodeChar
    if (unicode != 0) {
        return unicode.toChar().toString()
    }
    // A chord such as Ctrl+A reports no character; the `key` is still the one
    // the physical key types on its own.
    val unmodified = event.getUnicodeChar(0)
    if (unmodified != 0) {
        return unmodified.toChar().toString()
    }
    return WUI_SURFACE_UNIDENTIFIED
}

/** The modifier chord, as `WUI_SURFACE_MODIFIER_*` bits. */
internal fun surfaceModifiers(event: KeyEvent): Int {
    var bits = 0
    if (event.isShiftPressed) bits = bits or WUI_SURFACE_MODIFIER_SHIFT
    if (event.isCtrlPressed) bits = bits or WUI_SURFACE_MODIFIER_CONTROL
    if (event.isAltPressed) bits = bits or WUI_SURFACE_MODIFIER_ALT
    if (event.isMetaPressed) bits = bits or WUI_SURFACE_MODIFIER_META
    if (event.isCapsLockOn) bits = bits or WUI_SURFACE_MODIFIER_CAPS_LOCK
    if (event.isNumLockOn) bits = bits or WUI_SURFACE_MODIFIER_NUM_LOCK
    return bits
}

/** The byte offset of a UTF-16 index into `text`, as the carrier counts carets. */
internal fun surfaceCaret(text: String, utf16Offset: Int): Long =
    text.substring(0, utf16Offset.coerceIn(0, text.length))
        .toByteArray(Charsets.UTF_8)
        .size
        .toLong()

/**
 * The GPU surface state an input source forwards its events to.
 *
 * The sink owns nothing: `GpuSurfaceView` drops the native state and calls
 * [invalidate] first, so an input connection the IME still holds afterwards
 * forwards nothing rather than writing through a freed handle.
 */
internal class GpuSurfaceInputSink(private var statePtr: Long) {
    fun invalidate() {
        statePtr = 0L
    }

    val isValid: Boolean get() = statePtr != 0L

    @Suppress("LongParameterList") // The signature is the flat native input carrier.
    fun send(
        kind: WuiSurfaceInputEventKind,
        focused: Boolean = false,
        modifiers: Int = 0,
        x: Double = 0.0,
        y: Double = 0.0,
        pressed: Boolean = false,
        button: WuiSurfacePointerButton = WuiSurfacePointerButton.Primary,
        deltaX: Double = 0.0,
        deltaY: Double = 0.0,
        scrollUnit: WuiScrollUnit = WuiScrollUnit.Pixel,
        finished: Boolean = false,
        key: String = "",
        code: String = "",
        text: String = "",
        isRepeat: Boolean = false,
        caret: Long = WUI_SURFACE_CARET_NONE
    ): Boolean {
        if (statePtr == 0L) {
            return false
        }
        return NativeBindings.waterui_gpu_surface_send_input_event(
            statePtr = statePtr,
            kind = kind.ordinal,
            focused = focused,
            modifiers = modifiers,
            x = x,
            y = y,
            pressed = pressed,
            button = button.ordinal,
            deltaX = deltaX,
            deltaY = deltaY,
            scrollUnit = scrollUnit.ordinal,
            finished = finished,
            key = key,
            code = code,
            text = text,
            isRepeat = isRepeat,
            caret = caret
        )
    }

    /** The view's caret as `left`, `top`, `width`, `height` in logical points. */
    fun imeCaret(): FloatArray? =
        if (statePtr == 0L) null else NativeBindings.waterui_gpu_surface_ime_caret(statePtr)
}

/**
 * The IME's connection to a GPU view that composes its own text.
 *
 * The surface owns its document and the neutral vocabulary is one-way plus a
 * caret rect, so this connection mirrors no text: what the IME edits is the
 * pre-edit buffer, and everything else is expressed as the key events the view
 * would have received from a hardware keyboard.
 */
internal class GpuSurfaceInputConnection(
    private val host: View,
    private val sink: GpuSurfaceInputSink
) : BaseInputConnection(host, false) {
    private var composing = ""

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString() ?: ""
        val wasComposing = composing.isNotEmpty()
        composing = ""
        sink.send(
            kind = if (wasComposing) {
                WuiSurfaceInputEventKind.CompositionCommit
            } else {
                WuiSurfaceInputEventKind.TextInput
            },
            text = value
        )
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString() ?: ""
        val wasComposing = composing.isNotEmpty()
        if (value.isEmpty()) {
            composing = ""
            if (wasComposing) {
                sink.send(WuiSurfaceInputEventKind.CompositionCancel)
            }
            return true
        }
        if (!wasComposing) {
            sink.send(WuiSurfaceInputEventKind.CompositionStart)
        }
        composing = value
        // `newCursorPosition` is relative to the composing text: positive puts
        // the caret after it, anything else puts it before.
        val utf16Offset = if (newCursorPosition > 0) value.length else 0
        sink.send(
            kind = WuiSurfaceInputEventKind.CompositionUpdate,
            text = value,
            caret = surfaceCaret(value, utf16Offset)
        )
        return true
    }

    override fun finishComposingText(): Boolean {
        if (composing.isEmpty()) {
            return true
        }
        val value = composing
        composing = ""
        sink.send(kind = WuiSurfaceInputEventKind.CompositionCommit, text = value)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        // Composing over already-committed text needs a document the host does
        // not have; the IME falls back to `setComposingText` when this is
        // refused, which the surface can honour.
        return false
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        // The host holds no document to delete from, so the edit is expressed as
        // the key presses that produce it — the same ones a hardware keyboard
        // would have sent.
        repeat(beforeLength) { sendSyntheticKey("Backspace") }
        repeat(afterLength) { sendSyntheticKey("Delete") }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        val keyEvent = event ?: return false
        return when (keyEvent.action) {
            KeyEvent.ACTION_DOWN -> sendSurfaceKey(sink, keyEvent, pressed = true)
            KeyEvent.ACTION_UP -> sendSurfaceKey(sink, keyEvent, pressed = false)
            else -> false
        }
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        val caret = sink.imeCaret() ?: return false
        val manager = host.context.getSystemService(InputMethodManager::class.java) ?: return false
        val density = host.resources.displayMetrics.density
        val location = IntArray(2)
        host.getLocationOnScreen(location)
        val left = location[0] + caret[0] * density
        val top = location[1] + caret[1] * density
        val bottom = top + caret[3] * density
        manager.updateCursorAnchorInfo(
            host,
            CursorAnchorInfo.Builder()
                .setInsertionMarkerLocation(
                    left,
                    top,
                    bottom,
                    bottom,
                    CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION
                )
                .build()
        )
        return true
    }

    private fun sendSyntheticKey(name: String) {
        for (pressed in listOf(true, false)) {
            sink.send(
                kind = WuiSurfaceInputEventKind.Key,
                pressed = pressed,
                key = name,
                code = name
            )
        }
    }
}

/** Forwards one Android key event as the neutral key event it stands for. */
internal fun sendSurfaceKey(
    sink: GpuSurfaceInputSink,
    event: KeyEvent,
    pressed: Boolean
): Boolean {
    val code = surfaceCode(event)
    val modifiers = surfaceModifiers(event)
    sink.send(kind = WuiSurfaceInputEventKind.Modifiers, modifiers = modifiers)
    val consumed = sink.send(
        kind = WuiSurfaceInputEventKind.Key,
        modifiers = modifiers,
        pressed = pressed,
        key = surfaceKey(event, code),
        code = code,
        isRepeat = pressed && event.repeatCount > 0
    )
    // A key that types a character is followed by the text it produced, exactly
    // as the web platform does.
    if (pressed && consumed) {
        val unicode = event.unicodeChar
        if (unicode != 0 && !Character.isISOControl(unicode)) {
            sink.send(
                kind = WuiSurfaceInputEventKind.TextInput,
                text = unicode.toChar().toString()
            )
        }
    }
    return consumed
}

/** Describes the surface to the IME as a plain text field it may compose into. */
internal fun describeSurfaceEditor(outAttrs: EditorInfo) {
    outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
    outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
    outAttrs.initialSelStart = 0
    outAttrs.initialSelEnd = 0
}
