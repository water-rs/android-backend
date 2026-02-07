package dev.waterui.android.components

/**
 * @deprecated Photo component is now a composite view in Rust.
 *
 * Photo resolves to GpuSurface internally, so this native component renderer is no longer needed.
 * The view tree will naturally resolve to GpuSurfaceComponent instead.
 *
 * This file is kept for reference but the component is not registered.
 */

import dev.waterui.android.runtime.RegistryBuilder

// Photo component renderer is deprecated - Photo is now a composite view (resolves to GpuSurface)
// Keeping this stub to prevent compile errors

internal fun RegistryBuilder.registerWuiPhoto() {
    // No-op: Photo is now a composite view in Rust
    // The view tree will naturally resolve to GpuSurface which has its own renderer
}
