# WaterUI Android Backend – Agent Notes

This directory is a standalone Gradle project that ships the Android backend for WaterUI. Rust
defines the declarative UI, exports it through `waterui-ffi`, and this module renders it with
Jetpack Compose plus a JNI shim.

## Project Structure

- `build.gradle.kts`, `settings.gradle.kts` – allow running Gradle from the repository root via
  `./gradlew -p backends/android …`.
- `runtime/src/main/java/dev/waterui/android` – Kotlin source:
  - `runtime/` – platform runtime, environment wrappers, native pointer helpers, and the Compose
    component registry (`components/`, `runtime/`, `reactive/`).
  - `components/` – one file per WaterUI component (Text, Button, Slider, etc.).
  - `reactive/` – `WuiBinding`/`WuiComputed` wrappers that subscribe to native signals.
- `runtime/src/main/cpp/waterui_jni.cpp` – JNI bridge that loads symbols from the app’s Rust SO.
- `ffi/waterui.h` (imported via relative include) – C ABI definition shared with JNI/C++.

## Building & Testing

```bash
# Kotlin + Compose
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew -p backends/android :runtime:compileDebugKotlin

# Native bridge (CMake/Ninja)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew -p backends/android ':runtime:buildCMakeDebug[arm64-v8a]'
```

Notes:

- Gradle/Kotlin DSL currently fails when run under JDK 25 with `IllegalArgumentException: 25`. Set
  `JAVA_HOME` to a supported LTS (17 or 21) before running any Gradle task.
- The CLI vendors this backend into generated demo apps under
  `target/debug/water-demo/backends/android`. When debugging there, the same commands apply.

## Coding Guidelines

- Compose components should read/watch WaterUI signals via `WuiBinding`/`WuiComputed`. Remember to
  call `watch()` immediately after instantiating and `close()` via `DisposableEffect`.
- Keep Kotlin visibility consistent: anything returned from a `public`/`internal` API must not expose
  `private` types (`WuiStyledStr` was failing compilation until helper classes were made `internal`).
- JNI glue uses the `WATERUI_SYMBOL_LIST` macro to load symbols via `dlsym`. Whenever you add a new
  FFI function, extend this list so the symbols are resolved at startup.
- Avoid leaking native pointers. Wrap raw `Long` handles with `NativePointer` and ensure `release`
  drops the native resource (`waterui_drop_*`).

## Pitfalls Encountered

1. **Wrong JDK** – Running Gradle with the default Temurin 25 triggers
   `IllegalArgumentException: 25` when Kotlin tries to parse the Java version. Set `JAVA_HOME` to
   a supported version (17 in our builds) before invoking Gradle.
2. **Visibility leakage** – Returning `WuiStyledStr` that held `private` helper classes caused
   Kotlin errors like `'internal' function exposes its 'private-in-file' parameter type`. Make helper
   classes (`StyledChunk`, `WuiFont`, `WuiColor`, etc.) `internal` when they cross API boundaries.
3. **Missing JNI symbols** – After adding font/color resolution helpers we forgot to update
   `WATERUI_SYMBOL_LIST`, so the native build failed with
   `no member named 'waterui_drop_font' in WaterUiSymbols`. Always extend the macro when introducing
   new FFI functions. Follow the “fast fail” rule: if the Rust cdylib is missing a required symbol
   (e.g., `waterui_configure_hot_reload_endpoint`), let JNI throw and rebuild the Rust side instead
   of adding best-effort fallbacks. This keeps CI honest and prevents silent runtime regressions.
4. **Don’t hand-edit `waterui.h`** – Regenerate the header via
   `cargo run -p waterui-ffi --bin generate_header --features cbindgen`. The script writes
   `ffi/waterui.h` and copies it into this backend’s `runtime/src/main/cpp/waterui.h` as well as the
   Apple Swift package. Avoid diverging copies; Android CI assumes the header and Rust exports match.

Keep this file up to date so future LLM agents understand the environment, common commands, and
avoid repeating the same build/debug issues.
