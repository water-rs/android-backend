/* SWIG interface file for WaterUI Android bindings
 *
 * This file defines how SWIG should wrap the waterui.h C API for Java/Kotlin.
 * SWIG generates:
 *   - waterui_wrap.c: JNI implementation
 *   - WaterUIJNI.java: Java class with native method declarations
 */
% module(directors = "1") WaterUIJNI

    %
    {
/* Include the header in the wrapper code */
#include "waterui.h"
        % }

    /* Java package configuration */
    % pragma(java) jniclassimports = % {
import dev.waterui.android.runtime.*;
  %
}

/* ============================================================================
 * Type mappings
 * ============================================================================
 */

/* Standard integer types */
%
    include<stdint.i>

    /* Handle all opaque struct pointers as long (raw pointer) for Kotlin
       interop */
    % define OPAQUE_STRUCT(TYPE) % typemap(jni) struct TYPE * "jlong" %
    typemap(jtype) struct TYPE * "long" % typemap(jstype) struct TYPE * "long" %
    typemap(javain) struct TYPE * "$javainput" %
    typemap(javaout) struct TYPE * {
  return $jnicall;
}
% typemap(in) struct TYPE * % {
  $1 = (struct TYPE *)(intptr_t)$input;
  %
}
% typemap(out) struct TYPE * % {
  $result = (jlong)(intptr_t)$1;
  %
}

% typemap(jni) const struct TYPE * "jlong" % typemap(jtype) const struct TYPE *
    "long" % typemap(jstype) const struct TYPE * "long" %
    typemap(javain) const struct TYPE * "$javainput" %
    typemap(javaout) const struct TYPE * {
  return $jnicall;
}
% typemap(in) const struct TYPE * % {
  $1 = (const struct TYPE *)(intptr_t)$input;
  %
}
% typemap(out) const struct TYPE * % {
  $result = (jlong)(intptr_t)$1;
  %
}
%
    enddef

    /* Apply opaque handling to all WaterUI types */
    OPAQUE_STRUCT(WuiEnv) OPAQUE_STRUCT(WuiAnyView) OPAQUE_STRUCT(WuiAction) OPAQUE_STRUCT(
        WuiColor) OPAQUE_STRUCT(WuiLayout) OPAQUE_STRUCT(WuiWatcherGuard)
        OPAQUE_STRUCT(WuiWatcherMetadata) OPAQUE_STRUCT(WuiComputed_ResolvedColor) OPAQUE_STRUCT(
            WuiComputed_ResolvedFont) OPAQUE_STRUCT(WuiComputed_ColorScheme)
            OPAQUE_STRUCT(WuiComputed_Color) OPAQUE_STRUCT(WuiComputed_Font) OPAQUE_STRUCT(
                WuiComputed_AnyView) OPAQUE_STRUCT(WuiComputed_StyledStr)
                OPAQUE_STRUCT(WuiComputed_f64) OPAQUE_STRUCT(WuiComputed_i32) OPAQUE_STRUCT(
                    WuiComputed_bool) OPAQUE_STRUCT(WuiComputed_Str) OPAQUE_STRUCT(WuiComputed_PickerItems)
                    OPAQUE_STRUCT(WuiBinding_Color) OPAQUE_STRUCT(WuiBinding_Font) OPAQUE_STRUCT(
                        WuiBinding_AnyView) OPAQUE_STRUCT(WuiBinding_f64)
                        OPAQUE_STRUCT(WuiBinding_i32) OPAQUE_STRUCT(
                            WuiBinding_bool) OPAQUE_STRUCT(WuiBinding_Str)
                            OPAQUE_STRUCT(Binding_f64) OPAQUE_STRUCT(Binding_i32) OPAQUE_STRUCT(
                                Binding_bool) OPAQUE_STRUCT(Binding_Str)
                                OPAQUE_STRUCT(WuiWatcher_ResolvedColor) OPAQUE_STRUCT(
                                    WuiWatcher_ResolvedFont) OPAQUE_STRUCT(WuiWatcher_ColorScheme)
                                    OPAQUE_STRUCT(WuiWatcher_AnyView) OPAQUE_STRUCT(
                                        WuiWatcher_f64) OPAQUE_STRUCT(WuiWatcher_i32)
                                        OPAQUE_STRUCT(WuiWatcher_bool) OPAQUE_STRUCT(
                                            WuiWatcher_Str) OPAQUE_STRUCT(WuiWatcher_StyledStr)
                                            OPAQUE_STRUCT(WuiWatcher_PickerItems) OPAQUE_STRUCT(
                                                WuiText) OPAQUE_STRUCT(WuiButton)
                                                OPAQUE_STRUCT(WuiTextField) OPAQUE_STRUCT(
                                                    WuiToggle) OPAQUE_STRUCT(WuiSlider)
                                                    OPAQUE_STRUCT(WuiStepper) OPAQUE_STRUCT(
                                                        WuiProgress)
                                                        OPAQUE_STRUCT(WuiPicker)
                                                            OPAQUE_STRUCT(
                                                                WuiLabel)
                                                                OPAQUE_STRUCT(
                                                                    WuiDynamic)
                                                                    OPAQUE_STRUCT(
                                                                        WuiLazy)
                                                                        OPAQUE_STRUCT(
                                                                            WuiRenderer)

    /* Handle void* as long for callback data */
    % typemap(jni) void * "jlong" % typemap(jtype) void * "long" %
    typemap(jstype) void * "long" % typemap(javain) void * "$javainput" %
    typemap(javaout) void * {
  return $jnicall;
}
% typemap(in) void * % {
  $1 = (void *)(intptr_t)$input;
  %
}
% typemap(out) void * % {
  $result = (jlong)(intptr_t)$1;
  %
}

/* Handle const char* as String */
% typemap(jni) const char * "jstring" % typemap(jtype) const char * "String" %
    typemap(jstype) const char * "String" % typemap(javain) const char *
    "$javainput" % typemap(in) const char * % {
  $1 = ($1_ltype)JCALL2(GetStringUTFChars, jenv, $input, NULL);
  %
}
% typemap(freearg) const char * % {
  if ($1)
    JCALL2(ReleaseStringUTFChars, jenv, $input, $1);
  %
}

/* WuiStr return type - convert to Java String */
% typemap(jni) struct WuiStr "jstring" % typemap(jtype) struct WuiStr "String" %
    typemap(jstype) struct WuiStr "String" % typemap(javaout) struct WuiStr {
  return $jnicall;
} % typemap(out) struct WuiStr % {
  if ($1.ptr && $1.len > 0) {
    char *temp = (char *)malloc($1.len + 1);
    memcpy(temp, $1.ptr, $1.len);
    temp[$1.len] = '\0';
    $result = JCALL1(NewStringUTF, jenv, temp);
    free(temp);
  } else {
    $result = JCALL1(NewStringUTF, jenv, "");
  }
  %
}

/* ============================================================================
 * Enum mappings - expose as int constants
 * ============================================================================
 */

/* Enums are handled as integers */
% typemap(jni) enum WuiAnimation, enum WuiAxis, enum WuiFontWeight,
    enum WuiKeyboardType, enum WuiProgressStyle, enum WuiRendererBufferFormat,
    enum WuiColorScheme, enum WuiColorSlot,
    enum WuiFontSlot "jint" % typemap(jtype) enum WuiAnimation, enum WuiAxis,
    enum WuiFontWeight, enum WuiKeyboardType, enum WuiProgressStyle,
    enum WuiRendererBufferFormat, enum WuiColorScheme, enum WuiColorSlot,
    enum WuiFontSlot "int" % typemap(jstype) enum WuiAnimation, enum WuiAxis,
    enum WuiFontWeight, enum WuiKeyboardType, enum WuiProgressStyle,
    enum WuiRendererBufferFormat, enum WuiColorScheme, enum WuiColorSlot,
    enum WuiFontSlot "int" % typemap(javain) enum WuiAnimation, enum WuiAxis,
    enum WuiFontWeight, enum WuiKeyboardType, enum WuiProgressStyle,
    enum WuiRendererBufferFormat, enum WuiColorScheme, enum WuiColorSlot,
    enum WuiFontSlot "$javainput" % typemap(javaout) enum WuiAnimation,
    enum WuiAxis, enum WuiFontWeight, enum WuiKeyboardType,
    enum WuiProgressStyle, enum WuiRendererBufferFormat, enum WuiColorScheme,
    enum WuiColorSlot, enum WuiFontSlot {
      return $jnicall;
    }
% typemap(in) enum WuiAnimation, enum WuiAxis, enum WuiFontWeight,
    enum WuiKeyboardType, enum WuiProgressStyle, enum WuiRendererBufferFormat,
    enum WuiColorScheme, enum WuiColorSlot, enum WuiFontSlot % {
  $1 = ($1_ltype)$input;
  %
}
% typemap(out) enum WuiAnimation, enum WuiAxis, enum WuiFontWeight,
    enum WuiKeyboardType, enum WuiProgressStyle, enum WuiRendererBufferFormat,
    enum WuiColorScheme, enum WuiColorSlot, enum WuiFontSlot % {
  $result = (jint)$1;
  %
}

/* ============================================================================
 * Struct mappings for value types
 * ============================================================================
 */

/* WuiResolvedColor - map to Java object */
% typemap(jni) struct WuiResolvedColor "jobject" %
    typemap(jtype) struct WuiResolvedColor "ResolvedColorStruct" %
    typemap(jstype) struct WuiResolvedColor "ResolvedColorStruct" %
    typemap(javaout) struct WuiResolvedColor {
  return $jnicall;
} % typemap(out) struct WuiResolvedColor % {
  jclass cls = JCALL1(FindClass, jenv,
                      "dev/waterui/android/runtime/ResolvedColorStruct");
  jmethodID ctor = JCALL3(GetMethodID, jenv, cls, "<init>", "(FFFF)V");
  $result =
      JCALL6(NewObject, jenv, cls, ctor, $1.red, $1.green, $1.blue, $1.opacity);
  JCALL1(DeleteLocalRef, jenv, cls);
  %
}

/* WuiResolvedFont - map to Java object */
% typemap(jni) struct WuiResolvedFont "jobject" %
    typemap(jtype) struct WuiResolvedFont "ResolvedFontStruct" %
    typemap(jstype) struct WuiResolvedFont "ResolvedFontStruct" %
    typemap(javaout) struct WuiResolvedFont {
  return $jnicall;
} % typemap(out) struct WuiResolvedFont % {
  jclass cls =
      JCALL1(FindClass, jenv, "dev/waterui/android/runtime/ResolvedFontStruct");
  jmethodID ctor = JCALL3(GetMethodID, jenv, cls, "<init>", "(FI)V");
  $result = JCALL6(NewObject, jenv, cls, ctor, $1.size, (jint)$1.weight);
  JCALL1(DeleteLocalRef, jenv, cls);
  %
}

/* ============================================================================
 * Ignore function pointer parameters (callbacks need special handling)
 * ============================================================================
 */

/* Ignore functions that take function pointers - these need manual JNI */
% ignore waterui_new_computed_resolved_color;
% ignore waterui_new_computed_resolved_font;
% ignore waterui_new_computed_color_scheme;
% ignore waterui_new_watcher_resolved_color;
% ignore waterui_new_watcher_resolved_font;
% ignore waterui_new_watcher_color_scheme;
% ignore waterui_new_watcher_guard;

/* Ignore array types that need special handling */
% ignore WuiArray;
% ignore WuiArraySlice;
% ignore WuiArrayVTable;

/* ============================================================================
 * Include the header to parse declarations
 * ============================================================================
 */

% include "waterui.h"
