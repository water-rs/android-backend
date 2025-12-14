# WaterUI Android Backend

[![](https://jitpack.io/v/water-rs/android-backend.svg)](https://jitpack.io/#water-rs/android-backend)
[![License](https://img.shields.io/badge/License-MIT%20OR%20Apache--2.0-blue.svg)](#license)


This Gradle project hosts the Android runtime glue that renders WaterUI view trees
with the platform View system. The design mirrors the Swift backend: Rust
defines the UI, exports a C ABI via `waterui-ffi`, and the native platform
renders that tree.

## Project layout

- `settings.gradle.kts` – standalone Gradle settings so the module can be built
  from the repository root with `./gradlew -p backends/android …`.
- `runtime/` – Android library that ships the JNI bridge, Kotlin runtime
  wrappers, and the production Android View renderer set.
  - `src/main/cpp/waterui_jni.cpp` – translates between the C ABI from
    `waterui.h` and JVM-friendly types (`String`, Kotlin data classes, etc.).
  - `src/main/java/dev/waterui/android/runtime/` – Kotlin wrappers (`WuiAnyView`,
    `WuiEnvironment`, layout structs) plus the render registry and entry points.
  - `src/main/java/dev/waterui/android/components/` – View-based renderers for
    each WaterUI component (text, buttons, sliders, dynamic views, etc.).
  - `src/main/java/dev/waterui/android/reactive/` – binding/computed helpers that
    mirror the Swift `Binding`/`Computed` wrappers.

## Building the runtime

```bash
./gradlew -p backends/android runtime:assembleDebug
```

This produces an AAR under `backends/android/runtime/build/outputs/aar/`.

The JNI target is compiled with CMake. During development you can place the Rust
cdylib produced by the CLI under `runtime/src/main/jniLibs/<abi>/` so Gradle picks
it up automatically.

## Native libraries

Two shared libraries must be available at runtime:

1. **`libwaterui_app.so`** – produced by the WaterUI CLI (`water run android`).
   The CLI handles cross-compilation, NDK configuration, and automatically renames
   the output to this standardized name regardless of the crate name. It statically
   links `waterui-ffi` and exports the symbols declared in `ffi/waterui.h`.
2. **`libwaterui_android.so`** – the JNI shim provided by this module.
   It references the symbols exported by the application library, so make sure
   the application library is loaded first.

## Configuring the runtime

The host app is now responsible for loading both shared libraries. The CLI's
template wires this up inside `MainActivity` so Kotlin/JNI code can assume the
symbols exist before Compose starts rendering:

```kotlin
class MainActivity : ComponentActivity() {
    companion object {
        init {
            System.loadLibrary("waterui_app")  // Standardized name
            System.loadLibrary("waterui_android")
            bootstrapWaterUiRuntime()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(WaterUiRootView(this))
    }
}
```

`WaterUiRootView` owns the Rust environment, centres the content by default, and
renders the view hierarchy once the libraries are present.

## Current status

- All primitives rendered by the Swift backend now have Android View counterparts:
  text/label views, buttons, toggles, sliders, steppers, scroll views, colours,
  progress indicators, renderer views, spacers, and layout containers.
- `WuiBinding`/`WuiComputed` Kotlin wrappers watch Rust bindings via JNI so the
  view tree stays in sync with the environment without manual polling.
- Styled text, prompts, and resolved colours flow through the JNI layer so the
  Android UI renders the same content as Swift.
- Dynamic views, container layout negotiation, and progress bindings mirror the
  logic in `backends/apple`.
- Text fields respect the keyboard type requested in Rust (secure/email/url/number/phone),
  and animation metadata now drives default fade transitions for textual/colour
  updates.
- Generic picker views render WaterUI `picker()` controls via Android `Spinner`,
  keeping selections bound to the Rust environment.

See `IMPLEMENTATION_STATUS.md` for the remaining platform gaps.

## Implementing a new native view (example: Picker)

1. **Model the view in Rust** – add the Picker to the appropriate crate (for
   example `components/form`). Conform to `waterui_core::View`, update the view
   registry, and ensure the Picker can be instantiated from the demo app.
2. **Expose the type via `waterui-ffi`** – add the Picker struct to
   `ffi/src/views.rs`, update `ffi/Cargo.toml`, and run `cargo run -p waterui-ffi`
   to regenerate `ffi/waterui.h`. The exported struct should match the data the
   Android renderer needs (labels, bindings, ranges, etc.).
3. **Extend the JNI bridge** – in `backends/android/runtime/src/main/cpp/waterui_jni.cpp`
   add functions that mirror the new FFI symbols (e.g. `waterui_picker_id`,
   `waterui_force_as_picker`). Regenerate/commit the corresponding Kotlin data
   class and type identifiers inside `NativeBindings.kt`.
4. **Add Kotlin interoperability helpers** – if the Picker uses new pointer types
   (bindings, computed values, colours), implement the necessary wrappers under
   `dev.waterui.android.runtime` or `dev.waterui.android.reactive` so the Android
   View layer can observe or mutate state.
5. **Create the Android renderer** – add `PickerComponent.kt` under
   `runtime/src/main/java/dev/waterui/android/components/`. Implement
   `WuiRenderer` by inflating Android widgets (e.g. `Spinner`, `NumberPicker`)
   and wiring them to the bindings/computed values you exposed earlier. Remember
   to call `disposeWith` or `Closeable` helpers so JNI resources are dropped when
   the view leaves the hierarchy.
6. **Register the renderer** – call `registerWuiPicker()` from
   `RenderRegistry.defaultComponents`. The registry resolves type IDs obtained
   from the Rust tree and instantiates the corresponding View hierarchy.
7. **Test end-to-end** – rebuild the runtime
   (`./gradlew -p backends/android runtime:assembleDebug`), copy the backend into
   your sample WaterUI app, and run `water run --platform android` to verify the
   Picker appears and syncs with the Rust environment.

`waterui-ffi` acts as the contract between Rust and Kotlin. Any time you add or
change a native view you must update the FFI struct, regenerate `waterui.h`, and
keep the JNI/Kotlin mirrors in lockstep to avoid crashes.
