# WaterUI Android Backend

This Gradle project hosts the Android runtime glue that renders WaterUI view trees
with Jetpack Compose. The design mirrors the Swift backend: Rust defines the UI,
exports a C ABI via `waterui-ffi`, and the native platform renders that tree.

## Project layout

- `settings.gradle.kts` – standalone Gradle settings so the module can be built
  from the repository root with `./gradlew -p backends/android …`.
- `runtime/` – Android library that ships the JNI bridge, Kotlin runtime wrappers,
  and the production Compose renderer set.
  - `src/main/cpp/waterui_jni.cpp` – translates between the C ABI from
    `waterui.h` and JVM-friendly types (`String`, Kotlin data classes, etc.).
  - `src/main/java/dev/waterui/android/runtime/` – Kotlin wrappers (`WuiAnyView`,
    `WuiEnvironment`, layout structs) plus the render registry and entry points.
  - `src/main/java/dev/waterui/android/components/` – Jetpack Compose renderers
    for each WaterUI component (text, buttons, sliders, dynamic views, etc.).
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

1. **Application library** – produced by the WaterUI CLI (`cargo ndk` build).
   It statically links `waterui-ffi` and exports the symbols declared in
   `ffi/waterui.h`. Load it with `System.loadLibrary("waterui_sample")`
   (omit the `lib` prefix and `.so` suffix).
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
            System.loadLibrary("waterui_sample")
            System.loadLibrary("waterui_android")
            NativeBindings.bootstrapNativeBindings("waterui_sample")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WaterUiRoot() }
    }
}
```

`WaterUiRoot()` (or the lower-level `WaterUIApplication`) still takes care of
initialising/dropping the Rust environment and rendering the view hierarchy via
Jetpack Compose once the libraries are present.

## Current status

- All primitives rendered by the Swift backend now have Compose counterparts:
  text/label views, buttons, toggles, sliders, steppers, scroll views, colours,
  progress indicators, renderer views, spacers, and layout containers.
- `WuiBinding`/`WuiComputed` Kotlin wrappers watch Rust bindings via JNI so UI
  stays in sync with the environment without manual polling.
- Styled text, prompts, and resolved colours flow through the JNI layer so
  Compose can render the same content as Swift (styling metadata is still
  flattened to plain `Text` for now).
- Dynamic views, container layout negotiation, and progress bindings mirror the
  logic in `backends/apple`.

See `IMPLEMENTATION_STATUS.md` for the remaining platform gaps.

## Developing alongside the CLI

`water run --platform android` vendors this Gradle project into newly created
apps. When you change the backend you must regenerate the artifacts so the CLI
can consume the updated Kotlin/JNI layer:

1. Build the updated runtime (both Kotlin and C++) with:
   ```bash
   ./gradlew -p backends/android runtime:assembleDebug
   ```
2. Copy or rsync the refreshed `backends/android` directory into your test
   project (e.g. replace `<app>/backends/android`).
3. Re-run the CLI (`water run --platform android`) so it reuses the new backend.

If Gradle complains that `project :backends:android` has no variants, double-check
that the copied folder still contains both the root `build.gradle.kts` and the
`runtime/` module. Missing either file leaves the consumer project with an empty
Android library module, which causes the variant error observed during builds.

## Troubleshooting

- **`JNI DETECTED ERROR … ChildMetadataStruct;.isStretch()`** – the JNI shim calls
  `ChildMetadataStruct.isStretch()` when marshaling layout metadata. If the Kotlin
  runtime you vendored doesn't expose that method (older backends relied on the
  auto-generated getter), Compose will crash as soon as a spacer participates in
  layout negotiation. Build and copy a fresh backend (`./gradlew -p backends/android runtime:assembleDebug`)
  so the updated data class with the explicit `isStretch()` method ships with your app.
