package dev.waterui.android.runtime

/**
 * Data classes that mirror native FFI structs.
 * 
 * These are used for marshalling data between Kotlin and native code.
 */

const val RENDERER_BUFFER_FORMAT_RGBA8888: Int = 0

// ========== Layout Structs ==========

data class LayoutContainerStruct(val layoutPtr: Long, val childrenPtr: Long)

data class FixedContainerStruct(val layoutPtr: Long, val childPointers: LongArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FixedContainerStruct) return false
        return layoutPtr == other.layoutPtr && childPointers.contentEquals(other.childPointers)
    }
    override fun hashCode(): Int = 31 * layoutPtr.hashCode() + childPointers.contentHashCode()
}

data class ProposalStruct(val width: Float, val height: Float)

data class SizeStruct(val width: Float, val height: Float)

data class RectStruct(val x: Float, val y: Float, val width: Float, val height: Float)

data class ChildMetadataStruct(val proposal: ProposalStruct, val priority: Int, val stretch: Boolean) {
    fun isStretch(): Boolean = stretch
}

// ========== Safe Area Structs ==========

/**
 * Safe area insets in points (matches Rust SafeAreaInsets)
 */
data class SafeAreaInsetsStruct(
    val top: Float,
    val bottom: Float,
    val leading: Float,
    val trailing: Float
) {
    companion object {
        val ZERO = SafeAreaInsetsStruct(0f, 0f, 0f, 0f)
    }
}

/**
 * Safe area edges bitflags (matches Rust SafeAreaEdges)
 */
data class SafeAreaEdgesStruct(val bits: Int) {
    companion object {
        val NONE = SafeAreaEdgesStruct(0)
        val TOP = SafeAreaEdgesStruct(0b0001)
        val BOTTOM = SafeAreaEdgesStruct(0b0010)
        val LEADING = SafeAreaEdgesStruct(0b0100)
        val TRAILING = SafeAreaEdgesStruct(0b1000)
        val HORIZONTAL = SafeAreaEdgesStruct(LEADING.bits or TRAILING.bits)
        val VERTICAL = SafeAreaEdgesStruct(TOP.bits or BOTTOM.bits)
        val ALL = SafeAreaEdgesStruct(HORIZONTAL.bits or VERTICAL.bits)
    }
    
    operator fun plus(other: SafeAreaEdgesStruct) = SafeAreaEdgesStruct(bits or other.bits)
    fun contains(other: SafeAreaEdgesStruct) = (bits and other.bits) == other.bits
}

/**
 * Layout context passed through the layout hierarchy (matches Rust LayoutContext)
 */
data class LayoutContextStruct(
    val safeArea: SafeAreaInsetsStruct,
    val ignoresSafeArea: SafeAreaEdgesStruct
) {
    companion object {
        val EMPTY = LayoutContextStruct(SafeAreaInsetsStruct.ZERO, SafeAreaEdgesStruct.NONE)
    }
}

/**
 * Child placement result from layout (matches Rust ChildPlacement)
 */
data class ChildPlacementStruct(
    val rect: RectStruct,
    val context: LayoutContextStruct
)

// ========== Watcher Structs ==========

/**
 * Common watcher envelope for bindings/computed values.
 * Contains pointers to the callback data, call function, and drop function.
 */
data class WatcherStruct(val dataPtr: Long, val callPtr: Long, val dropPtr: Long)

// ========== View Structs ==========

data class ButtonStruct(val labelPtr: Long, val actionPtr: Long)

data class TextStruct(val contentPtr: Long)

data class PlainStruct(val textBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlainStruct) return false
        return textBytes.contentEquals(other.textBytes)
    }
    override fun hashCode(): Int = textBytes.contentHashCode()
}

data class ColorStruct(val colorPtr: Long)

data class TextFieldStruct(val labelPtr: Long, val valuePtr: Long, val promptPtr: Long, val keyboardType: Int)

data class ToggleStruct(val labelPtr: Long, val bindingPtr: Long)

data class SliderStruct(
    val labelPtr: Long,
    val minLabelPtr: Long,
    val maxLabelPtr: Long,
    val rangeStart: Double,
    val rangeEnd: Double,
    val bindingPtr: Long
)

data class StepperStruct(
    val bindingPtr: Long,
    val stepPtr: Long,
    val labelPtr: Long,
    val rangeStart: Int,
    val rangeEnd: Int
)

data class ProgressStruct(val labelPtr: Long, val valueLabelPtr: Long, val valuePtr: Long, val style: Int)

data class ScrollStruct(val axis: Int, val contentPtr: Long)

data class DynamicStruct(val dynamicPtr: Long)

data class PickerStruct(val itemsPtr: Long, val selectionPtr: Long)

// Alias for container struct returned by force_as_layout_container
data class ContainerStruct(val layoutPtr: Long, val contentsPtr: Long)

// ========== Text Styling Structs ==========

data class StyledStrStruct(val chunks: Array<StyledChunkStruct>) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StyledStrStruct) return false
        return chunks.contentEquals(other.chunks)
    }
    override fun hashCode(): Int = chunks.contentHashCode()
}

data class StyledChunkStruct(val text: String, val style: TextStyleStruct)

data class TextStyleStruct(
    val fontPtr: Long,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
    val foregroundPtr: Long,
    val backgroundPtr: Long
)

data class PickerItemStruct(val tag: Int, val label: StyledStrStruct)

// ========== Resolved Value Structs ==========

data class ResolvedColorStruct(val red: Float, val green: Float, val blue: Float, val opacity: Float)

data class ResolvedFontStruct(val size: Float, val weight: Int)

