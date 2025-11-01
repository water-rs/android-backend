# WaterUI Android Backend

This Gradle project hosts the Android runtime glue that renders WaterUI view trees
with Jetpack Compose. The design mirrors the Swift backend: Rust defines the UI,
exports a C ABI via `waterui-ffi`, and the native platform renders that tree.

## Project layout

- `settings.gradle.kts` – standalone Gradle settings so the module can be built
  from the repository root with `./gradlew -p backends/android …`.
- `runtime/` – Android library that ships the JNI bridge, Kotlin runtime wrappers,
  and placeholder Compose renderers.
  - `src/main/cpp/waterui_jni.cpp` – translates between the C ABI from
    `waterui.h` and JVM-friendly types (`String`, Kotlin data classes, etc.).
  - `src/main/java/dev/waterui/android/runtime/` – Kotlin wrappers (`WuiAnyView`,
    `WuiEnvironment`, layout structs) plus the render registry and entry points.
- `TASKS.md` – living checklist tracking the remaining backend work.

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

1. **Application library** – produced by the WaterUI CLI (`cargo ndk` build).
   It statically links `waterui-ffi` and exports the symbols declared in
   `ffi/waterui.h`. When loading from Kotlin, omit the `lib` prefix and `.so`
   suffix (e.g. `configureWaterUiNativeLibrary("waterui_sample")` loads
   `libwaterui_sample.so`).
2. **`libwaterui_android.so`** – the JNI shim provided by this module.
   It is linked with unresolved WaterUI symbols; make sure the application
   library is loaded first so the dynamic linker can resolve them.

## Configuring the runtime

Before using any WaterUI APIs on Android, initialise the runtime once, ideally
from your `Application` class:

```kotlin
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        configureWaterUiNativeLibrary("waterui_sample")
    }
}
```

This call stores the library name; the first access to `NativeBindings` will load
both the application library and `libwaterui_android.so`.

After configuration you can render the root view via `WaterUiRoot()` or build
your own renderer by looking up component IDs with `WuiAnyView.viewId()`.

## Current status

- Kotlin wrappers exist for `WuiEnv`, `WuiAnyView`, and layout negotiation structs.
- JNI bindings convert `WuiStr`, proposal/size/rect arrays, and child metadata.
- Compose rendering currently falls back to a placeholder that prints identifiers
  until component adapters land.

See `TASKS.md` for the remaining milestones.
