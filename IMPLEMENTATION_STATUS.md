# Android Backend Implementation Status

This document compares the Android View backend with the functionality exported
by the WaterUI Rust crates. Status codes:

- **✅ complete** – Feature behaves equivalently to the Swift/Rust implementation.
- **⚠️ partial** – Implemented but missing behaviour or polish.
- **❌ missing** – Not wired up yet.

## Core Runtime

| Rust Feature | Android Status | Notes |
| --- | --- | --- |
| `waterui_init` / environment lifecycle | ✅ complete | `WaterUiRootView` initialises/drops `WuiEnvironment`; Gradle toolchain set to JDK 17 for Kotlin compatibility. |
| `WuiAnyView` render loop | ⚠️ partial | Registry-driven renderers exist; fallback body traversal still lacks diagnostics for invalid nodes. |
| `RenderRegistry` overrides | ✅ complete | Functional renderer map replaces Swift-style class tree. |
| `NativePointer` lifecycle helpers | ✅ complete | Guard classes drop native handles safely. |
| Dynamic view (`waterui_dynamic_*`) | ✅ complete | JNI watcher plumbing replaces child views when the Rust node invalidates. |

## Layout

| Rust Feature | Android Status | Notes |
| --- | --- | --- |
| Container layout (`waterui_container`) | ⚠️ partial | Rust propose/measure/place is hooked up inside a custom ViewGroup; child diffing/priorities remain TODO. |
| Spacer (`waterui_spacer`) | ✅ complete | Renders a zero-sized `Space` view. |
| Scroll view | ✅ complete | Single-child scroll supports horizontal, vertical, or both axes using nested `ScrollView`/`HorizontalScrollView`. |
| Stack/overlay/padding helpers | ❌ missing | No dedicated View implementations yet. |

## Text & Styling

| Rust Feature | Android Status | Notes |
| --- | --- | --- |
| Styled text (`waterui_text`) | ⚠️ partial | Styled strings flow through JNI and re-render, but spans still map to basic Android text spans (no custom fonts yet). |
| Label (`waterui_label`) | ✅ complete | Decodes UTF-8 label bytes. |
| Colour view (`waterui_color`) | ✅ complete | Colours are resolved in the receiving environment and update reactively. |

## Controls

| Rust Feature | Android Status | Notes |
| --- | --- | --- |
| Button (`waterui_button`) | ✅ complete | Calls native action on click and drops the action handle when disposed. |
| Progress view (`waterui_progress`) | ✅ complete | Binds to computed progress value and renders label/value-label children. |
| Text field (`waterui_text_field`) | ⚠️ partial | Two-way binding and prompt text work; keyboard types still TODO. |
| Toggle (`waterui_toggle`) | ✅ complete | Boolean binding stays in sync with native state. |
| Slider (`waterui_slider`) | ✅ complete | Double binding with live updates + labels. |
| Stepper (`waterui_stepper`) | ⚠️ partial | Value binding + range hooked up; visual treatment still placeholder. |
| Picker / colour picker | ❌ missing | No JNI bindings or Compose UI yet. |

## Reactive System

| Rust Feature | Android Status | Notes |
| --- | --- | --- |
| Bindings (bool/int/double/string) | ✅ complete | JNI watcher factories stream updates into View callbacks. |
| Computed values | ⚠️ partial | Common types (i32/f64/styled text/resolved colour) implemented; remaining computed families TBD. |
| Animation metadata (`waterui_get_animation`) | ❌ missing | JNI function declared but not wired to any Android animations. |

## Navigation, Media & Advanced Components

| Rust Feature | Android Status | Notes |
| --- | --- | --- |
| Navigation view/link/search | ❌ missing | No Compose implementation yet. |
| Lists/Lazy collections (`for_each`) | ❌ missing | Container provides raw child list but no higher-level list abstractions. |
| Media components (image, video, live photo) | ❌ missing | Placeholders not yet added. |
| Graphics/canvas APIs | ⚠️ partial | `components/RendererViewComponent.kt` renders CPU buffers; GPU path TODO. |

## Tooling & Build

| Topic | Status | Notes |
| --- | --- | --- |
| Gradle module | ✅ complete | `settings.gradle.kts` includes `:backends:android`; wrapper pinned to Gradle 8.7. |
| Android SDK integration | ✅ complete | `local.properties` expected to point at SDK; `.gitignore` updated accordingly. |
| Kotlin/JVM toolchain | ✅ complete | Uses Kotlin 1.9.25 targeting Java 17 with coroutines. |

## Summary

The Android backend now mirrors the Swift renderer for the core UI primitives:
text/labels, colours, form controls, dynamic views, and scroll/layout
containers. Remaining work focuses on richer components (pickers, navigation,
media), span-level text styling, and platform polish such as keyboard
configuration and animation metadata.
