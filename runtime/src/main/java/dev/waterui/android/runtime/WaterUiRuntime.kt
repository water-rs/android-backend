package dev.waterui.android.runtime

/**
 * Entry point for configuring the WaterUI Android runtime.
 *
 * Call this once (typically in your Application class) with the name of the
 * Rust-generated shared library that exports WaterUI's C ABI.
 *
 * Example:
 * ```
 * class SampleApp : Application() {
 *     override fun onCreate() {
 *         super.onCreate()
 *         configureWaterUiNativeLibrary("waterui_sample") // loads libwaterui_sample.so
 *     }
 * }
 * ```
 */
fun configureWaterUiNativeLibrary(nativeLibraryName: String) {
    NativeLibraryLoader.configure(nativeLibraryName)
}
