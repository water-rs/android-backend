package dev.waterui.android.runtime

/**
 * 128-bit type identifier for O(1) comparison.
 *
 * This maps directly to Rust's `WuiTypeId` struct with two u64 fields.
 * - Normal build: Contains the view's `TypeId` (guaranteed unique by Rust)
 * - Hot reload: Contains 128-bit FNV-1a hash of `type_name()` (stable across dylibs)
 *
 * Using 128-bit virtually eliminates collision risk (birthday paradox threshold: ~10^19).
 */
data class WuiTypeId(val low: Long, val high: Long)
