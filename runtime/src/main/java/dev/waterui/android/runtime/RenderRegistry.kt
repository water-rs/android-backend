package dev.waterui.android.runtime

import android.content.Context
import android.view.View
import dev.waterui.android.components.*

/**
 * Registry of known view renderers. Each renderer creates a concrete Android [View]
 * for a WaterUI node.
 *
 * Metadata types are tracked separately - they don't implement NativeView and are
 * transparent for layout (stretch axis comes from their content).
 */
class RenderRegistry private constructor(
    private val entries: Map<WuiTypeId, WuiRenderer>,
    private val metadataTypes: Set<WuiTypeId>
) {
    fun resolve(typeId: WuiTypeId): WuiRenderer? = entries[typeId]

    /** Returns true if this type is a Metadata<T> type (transparent for layout). */
    fun isMetadata(typeId: WuiTypeId): Boolean = typeId in metadataTypes

    fun with(typeId: WuiTypeId, renderer: WuiRenderer): RenderRegistry =
        RenderRegistry(entries + (typeId to renderer), metadataTypes)

    fun withMetadata(typeId: WuiTypeId, renderer: WuiRenderer): RenderRegistry =
        RenderRegistry(entries + (typeId to renderer), metadataTypes + typeId)

    companion object {
        fun default(): RenderRegistry = RenderRegistry(defaultComponents, defaultMetadataTypes)
    }
}

/**
 * Functional renderer for a WaterUI node. Implementations must return a fully
 * configured [View] hierarchy rooted at a platform widget.
 */
fun interface WuiRenderer {
    fun createView(context: Context, node: WuiNode, env: WuiEnvironment, registry: RenderRegistry): View
}

/**
 * Simple node descriptor that wraps the native pointer and metadata received via JNI.
 */
data class WuiNode(
    val rawPtr: Long,
    val typeId: WuiTypeId
)

/**
 * Builder context for populating the registry.
 */
class RegistryBuilder {
    internal val components = mutableMapOf<WuiTypeId, WuiRenderer>()
    internal val metadataTypes = mutableSetOf<WuiTypeId>()

    /** Register a native view component. */
    fun register(idProvider: () -> WuiTypeId, renderer: WuiRenderer) {
        components[idProvider()] = renderer
    }

    /** Register a metadata component (transparent for layout). */
    fun registerMetadata(idProvider: () -> WuiTypeId, renderer: WuiRenderer) {
        val typeId = idProvider()
        components[typeId] = renderer
        metadataTypes.add(typeId)
    }
}

/**
 * Populated lazily to avoid referencing components before they are defined.
 */
private val registryData: Pair<Map<WuiTypeId, WuiRenderer>, Set<WuiTypeId>> by lazy {
    val builder = RegistryBuilder()
    with(builder) {
        registerWuiEmptyView()
        registerWuiText()
        registerWuiPlain()
        registerWuiButton()
        registerWuiColor()
        registerWuiFilledShape()
        registerWuiTextField()
        registerWuiSecureField()
        registerWuiStepper()
        registerWuiDatePicker()
        registerWuiColorPicker()
        registerWuiProgress()
        registerWuiDynamic()
        registerWuiScroll()
        registerWuiContainers()
        registerWuiToggle()
        registerWuiSpacer()
        registerWuiSlider()
        registerWuiPicker()
        registerWuiWithEnv()
        registerWuiPhoto()
        registerWuiVideo()
        registerWuiVideoPlayer()
        // MediaPicker removed - now uses Button wrapper in Rust
        // registerWuiMediaPicker()
        registerWuiGpuSurface()

        // Navigation components
        registerWuiNavigationStack()
        registerWuiNavigationView()
        registerWuiTabs()

        // List component
        registerWuiList()

        // Metadata components
        registerWuiSecure()
        registerWuiGesture()
        registerWuiLifeCycleHook()
        registerWuiOnEvent()
        registerWuiCursor()
        registerWuiBackground()
        registerWuiForeground()
        registerWuiShadow()
        registerWuiClipShape()
        registerWuiContextMenu()
        registerWuiFocused()
        registerWuiIgnoreSafeArea()
        registerWuiRetain()
        registerWuiTransform()

        // Filter components
        registerWuiBlur()
        registerWuiOpacity()
        registerWuiBrightness()
        registerWuiSaturation()
        registerWuiContrast()
        registerWuiHueRotation()
        registerWuiGrayscale()
    }
    builder.components to builder.metadataTypes
}

private val defaultComponents: Map<WuiTypeId, WuiRenderer> get() = registryData.first
private val defaultMetadataTypes: Set<WuiTypeId> get() = registryData.second
