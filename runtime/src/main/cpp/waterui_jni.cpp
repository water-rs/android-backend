/**
 * Minimal JNI for WaterUI Android Backend
 *
 * This file contains ONLY the JNI functions that require native-to-Java
 * callbacks. All other FFI calls use JavaCPP-generated bindings in WaterUILib.
 *
 * The callbacks are needed because:
 * 1. Rust calls back into Java when reactive values change
 * 2. JavaCPP cannot automatically generate these callback bridges
 */

#include "waterui.h"
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <string>
#include <vector>

namespace {

constexpr char LOG_TAG[] = "WaterUI.JNI";

// Symbols we need for watcher operations and complex struct handling
#define WATCHER_SYMBOL_LIST(X)                                                 \
  X(waterui_drop_watcher_metadata)                                             \
  X(waterui_new_watcher_guard)                                                 \
  X(waterui_new_watcher_any_view)                                              \
  X(waterui_new_watcher_bool)                                                  \
  X(waterui_new_watcher_f64)                                                   \
  X(waterui_new_watcher_i32)                                                   \
  X(waterui_new_watcher_picker_items)                                          \
  X(waterui_new_watcher_resolved_color)                                        \
  X(waterui_new_watcher_resolved_font)                                         \
  X(waterui_new_watcher_str)                                                   \
  X(waterui_new_watcher_styled_str)                                            \
  X(waterui_watch_binding_bool)                                                \
  X(waterui_watch_binding_f64)                                                 \
  X(waterui_watch_binding_i32)                                                 \
  X(waterui_watch_binding_str)                                                 \
  X(waterui_watch_computed_f64)                                                \
  X(waterui_watch_computed_i32)                                                \
  X(waterui_watch_computed_resolved_font)                                      \
  X(waterui_watch_computed_resolved_color)                                     \
  X(waterui_watch_computed_styled_str)                                         \
  X(waterui_watch_computed_picker_items)                                       \
  X(waterui_watch_computed_color_scheme)                                       \
  X(waterui_new_watcher_color_scheme)                                          \
  X(waterui_dynamic_connect)                                                   \
  X(waterui_read_computed_styled_str)                                          \
  X(waterui_read_computed_picker_items)                                        \
  X(waterui_read_binding_str)                                                  \
  X(waterui_set_binding_str)                                                   \
  X(waterui_call_watcher_resolved_color)                                       \
  X(waterui_call_watcher_resolved_font)                                        \
  X(waterui_drop_watcher_resolved_color)                                       \
  X(waterui_drop_watcher_resolved_font)                                        \
  X(waterui_new_computed_resolved_color)                                       \
  X(waterui_new_computed_resolved_font)                                        \
  X(waterui_layout_size_that_fits)                                             \
  X(waterui_layout_place)                                                      \
  X(waterui_view_id)                                                           \
  X(waterui_view_stretch_axis)                                                 \
  X(waterui_force_as_plain)                                                    \
  X(waterui_empty_id)                                                          \
  X(waterui_text_id)                                                           \
  X(waterui_plain_id)                                                          \
  X(waterui_button_id)                                                         \
  X(waterui_color_id)                                                          \
  X(waterui_text_field_id)                                                     \
  X(waterui_stepper_id)                                                        \
  X(waterui_progress_id)                                                       \
  X(waterui_dynamic_id)                                                        \
  X(waterui_scroll_view_id)                                                    \
  X(waterui_spacer_id)                                                         \
  X(waterui_toggle_id)                                                         \
  X(waterui_slider_id)                                                         \
  X(waterui_renderer_view_id)                                                  \
  X(waterui_fixed_container_id)                                                \
  X(waterui_picker_id)                                                         \
  X(waterui_layout_container_id)                                               \
  X(waterui_init)                                                              \
  X(waterui_main)                                                              \
  X(waterui_view_body)                                                         \
  X(waterui_clone_env)                                                         \
  X(waterui_drop_env)                                                          \
  X(waterui_drop_anyview)                                                      \
  X(waterui_configure_hot_reload_endpoint)                                     \
  X(waterui_configure_hot_reload_directory)                                    \
  X(waterui_force_as_button)                                                   \
  X(waterui_force_as_text)                                                     \
  X(waterui_force_as_color)                                                    \
  X(waterui_force_as_text_field)                                               \
  X(waterui_force_as_toggle)                                                   \
  X(waterui_force_as_slider)                                                   \
  X(waterui_force_as_stepper)                                                  \
  X(waterui_force_as_progress)                                                 \
  X(waterui_force_as_scroll_view)                                              \
  X(waterui_force_as_picker)                                                   \
  X(waterui_force_as_layout_container)                                         \
  X(waterui_force_as_fixed_container)                                          \
  X(waterui_force_as_dynamic)                                                  \
  X(waterui_force_as_renderer_view)                                            \
  X(waterui_drop_layout)                                                       \
  X(waterui_drop_action)                                                       \
  X(waterui_call_action)                                                       \
  X(waterui_drop_dynamic)                                                      \
  X(waterui_drop_color)                                                        \
  X(waterui_drop_font)                                                         \
  X(waterui_resolve_color)                                                     \
  X(waterui_resolve_font)                                                      \
  X(waterui_drop_box_watcher_guard)                                            \
  X(waterui_get_animation)                                                     \
  X(waterui_anyviews_len)                                                      \
  X(waterui_anyviews_get_view)                                                 \
  X(waterui_anyviews_get_id)                                                   \
  X(waterui_drop_anyviews)                                                     \
  X(waterui_read_binding_bool)                                                 \
  X(waterui_read_binding_f64)                                                  \
  X(waterui_read_binding_i32)                                                  \
  X(waterui_set_binding_bool)                                                  \
  X(waterui_set_binding_f64)                                                   \
  X(waterui_set_binding_i32)                                                   \
  X(waterui_drop_binding_bool)                                                 \
  X(waterui_drop_binding_f64)                                                  \
  X(waterui_drop_binding_i32)                                                  \
  X(waterui_drop_binding_str)                                                  \
  X(waterui_read_computed_f64)                                                 \
  X(waterui_read_computed_i32)                                                 \
  X(waterui_read_computed_resolved_color)                                      \
  X(waterui_read_computed_resolved_font)                                       \
  X(waterui_drop_computed_f64)                                                 \
  X(waterui_drop_computed_i32)                                                 \
  X(waterui_drop_computed_resolved_color)                                      \
  X(waterui_drop_computed_resolved_font)                                       \
  X(waterui_drop_computed_styled_str)                                          \
  X(waterui_drop_computed_picker_items)                                        \
  X(waterui_theme_color_background)                                            \
  X(waterui_theme_color_surface)                                               \
  X(waterui_theme_color_surface_variant)                                       \
  X(waterui_theme_color_border)                                                \
  X(waterui_theme_color_foreground)                                            \
  X(waterui_theme_color_muted_foreground)                                      \
  X(waterui_theme_color_accent)                                                \
  X(waterui_theme_color_accent_foreground)                                     \
  X(waterui_theme_font_body)                                                   \
  X(waterui_theme_font_title)                                                  \
  X(waterui_theme_font_headline)                                               \
  X(waterui_theme_font_subheadline)                                            \
  X(waterui_theme_font_caption)                                                \
  X(waterui_theme_font_footnote)                                               \
  X(waterui_theme_install_color)                                               \
  X(waterui_theme_install_font)                                                \
  X(waterui_theme_install_color_scheme)                                        \
  X(waterui_theme_color)                                                       \
  X(waterui_theme_font)                                                        \
  X(waterui_theme_color_scheme)                                                \
  X(waterui_computed_color_scheme_constant)                                    \
  X(waterui_read_computed_color_scheme)                                        \
  X(waterui_drop_computed_color_scheme)                                        \
  X(waterui_renderer_view_width)                                               \
  X(waterui_renderer_view_height)                                              \
  X(waterui_renderer_view_preferred_format)                                    \
  X(waterui_renderer_view_render_cpu)                                          \
  X(waterui_drop_renderer_view)

struct WatcherSymbols {
#define DECLARE_SYMBOL(name) decltype(&::name) name = nullptr;
  WATCHER_SYMBOL_LIST(DECLARE_SYMBOL)
#undef DECLARE_SYMBOL
};

WatcherSymbols g_sym{};
bool g_symbols_ready = false;

static JavaVM *g_vm = nullptr;
static jclass gBooleanClass = nullptr;
static jmethodID gBooleanValueOf = nullptr;
static jclass gIntegerClass = nullptr;
static jmethodID gIntegerValueOf = nullptr;
static jclass gDoubleClass = nullptr;
static jmethodID gDoubleValueOf = nullptr;
static jclass gLongClass = nullptr;
static jmethodID gLongValueOf = nullptr;
static jclass gMetadataClass = nullptr;
static jmethodID gMetadataCtor = nullptr;
static jclass gWatcherStructClass = nullptr;
static jmethodID gWatcherStructCtor = nullptr;

void throw_unsatisfied(JNIEnv *env, const std::string &message) {
  jclass errorClass = env->FindClass("java/lang/UnsatisfiedLinkError");
  if (errorClass == nullptr) {
    env->FatalError(message.c_str());
    return;
  }
  env->ThrowNew(errorClass, message.c_str());
}

class ScopedEnv {
public:
  JNIEnv *env = nullptr;
  bool attached = false;

  ScopedEnv() {
    if (g_vm == nullptr)
      return;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) !=
        JNI_OK) {
      if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        attached = true;
      } else {
        env = nullptr;
      }
    }
  }

  ~ScopedEnv() {
    if (attached && g_vm != nullptr) {
      g_vm->DetachCurrentThread();
    }
  }
};

template <typename T> inline jlong ptr_to_jlong(T *ptr) {
  return reinterpret_cast<jlong>(ptr);
}

template <typename T> inline T *jlong_to_ptr(jlong value) {
  return reinterpret_cast<T *>(value);
}

// ============================================================================
// String Conversion Utilities
// ============================================================================

jstring wui_str_to_jstring(JNIEnv *env, WuiStr value) {
  WuiArray_u8 bytes = value._0;
  WuiArraySlice_u8 slice = bytes.vtable.slice(bytes.data);
  auto *data = static_cast<uint8_t *>(slice.head);
  std::string utf8(reinterpret_cast<const char *>(data), slice.len);
  bytes.vtable.drop(bytes.data);
  return env->NewStringUTF(utf8.c_str());
}

jbyteArray wui_str_to_byte_array(JNIEnv *env, WuiStr value) {
  WuiArray_u8 bytes = value._0;
  WuiArraySlice_u8 slice = bytes.vtable.slice(bytes.data);
  auto *data = static_cast<uint8_t *>(slice.head);
  jbyteArray array = env->NewByteArray(static_cast<jsize>(slice.len));
  if (array != nullptr && slice.len > 0) {
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(slice.len),
                            reinterpret_cast<const jbyte *>(data));
  }
  bytes.vtable.drop(bytes.data);
  return array;
}

std::string wui_str_to_std_string(WuiStr value) {
  WuiArray_u8 bytes = value._0;
  WuiArraySlice_u8 slice = bytes.vtable.slice(bytes.data);
  auto *data = static_cast<uint8_t *>(slice.head);
  std::string utf8(reinterpret_cast<const char *>(data), slice.len);
  bytes.vtable.drop(bytes.data);
  return utf8;
}

struct ByteArrayHolder {
  uint8_t *data;
  size_t len;
};

WuiArraySlice_u8 byte_slice(const void *opaque) {
  const auto *holder = static_cast<const ByteArrayHolder *>(opaque);
  WuiArraySlice_u8 slice{};
  slice.head = holder->data;
  slice.len = holder->len;
  return slice;
}

void byte_drop(void *opaque) {
  auto *holder = static_cast<ByteArrayHolder *>(opaque);
  if (holder == nullptr)
    return;
  std::free(holder->data);
  std::free(holder);
}

WuiStr str_from_byte_array(JNIEnv *env, jbyteArray array) {
  jsize len = env->GetArrayLength(array);
  auto *holder =
      static_cast<ByteArrayHolder *>(std::malloc(sizeof(ByteArrayHolder)));
  holder->len = static_cast<size_t>(len);
  holder->data = static_cast<uint8_t *>(std::malloc(holder->len));
  env->GetByteArrayRegion(array, 0, len,
                          reinterpret_cast<jbyte *>(holder->data));

  WuiArray_u8 ffiArray{};
  ffiArray.data = holder;
  ffiArray.vtable.slice = byte_slice;
  ffiArray.vtable.drop = byte_drop;

  WuiStr str{};
  str._0 = ffiArray;
  return str;
}

// ============================================================================
// Watcher Callback Infrastructure
// ============================================================================

struct WatcherCallbackState {
  jobject callback;
  jmethodID method;
};

WatcherCallbackState *create_watcher_state(JNIEnv *env, jobject callback) {
  auto *state = new WatcherCallbackState();
  state->callback = env->NewGlobalRef(callback);
  jclass cls = env->GetObjectClass(callback);
  state->method = env->GetMethodID(
      cls, "onChanged",
      "(Ljava/lang/Object;Ldev/waterui/android/reactive/WuiWatcherMetadata;)V");
  env->DeleteLocalRef(cls);
  return state;
}

void drop_watcher_state(JNIEnv *env, WatcherCallbackState *state) {
  if (state == nullptr)
    return;
  env->DeleteGlobalRef(state->callback);
  delete state;
}

jobject new_metadata(JNIEnv *env, WuiWatcherMetadata *metadata) {
  return env->NewObject(gMetadataClass, gMetadataCtor, ptr_to_jlong(metadata));
}

jobject new_watcher_struct(JNIEnv *env, jlong data, jlong call, jlong drop) {
  return env->NewObject(gWatcherStructClass, gWatcherStructCtor, data, call,
                        drop);
}

jobject box_boolean(JNIEnv *env, bool value) {
  return env->CallStaticObjectMethod(gBooleanClass, gBooleanValueOf,
                                     value ? JNI_TRUE : JNI_FALSE);
}

jobject box_int(JNIEnv *env, jint value) {
  return env->CallStaticObjectMethod(gIntegerClass, gIntegerValueOf, value);
}

jobject box_double(JNIEnv *env, jdouble value) {
  return env->CallStaticObjectMethod(gDoubleClass, gDoubleValueOf, value);
}

jobject box_long(JNIEnv *env, jlong value) {
  return env->CallStaticObjectMethod(gLongClass, gLongValueOf, value);
}

void invoke_watcher(JNIEnv *env, WatcherCallbackState *state, jobject value_obj,
                    WuiWatcherMetadata *metadata) {
  if (env == nullptr || state == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  jobject metadata_obj = new_metadata(env, metadata);
  env->CallVoidMethod(state->callback, state->method, value_obj, metadata_obj);
  env->DeleteLocalRef(metadata_obj);
  g_sym.waterui_drop_watcher_metadata(metadata);
}

// ============================================================================
// Watcher Callback Implementations
// ============================================================================

void watcher_bool_call(const void *data, bool value,
                       WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject boxed = box_boolean(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed,
                 metadata);
  scoped.env->DeleteLocalRef(boxed);
}

void watcher_bool_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_int_call(const void *data, int32_t value,
                      WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject boxed = box_int(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed,
                 metadata);
  scoped.env->DeleteLocalRef(boxed);
}

void watcher_int_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_double_call(const void *data, double value,
                         WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject boxed = box_double(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed,
                 metadata);
  scoped.env->DeleteLocalRef(boxed);
}

void watcher_double_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_str_call(const void *data, WuiStr value,
                      WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jstring str = wui_str_to_jstring(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), str,
                 metadata);
  scoped.env->DeleteLocalRef(str);
}

void watcher_str_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

jobject new_resolved_color(JNIEnv *env, const WuiResolvedColor &color) {
  jclass cls =
      env->FindClass("dev/waterui/android/runtime/ResolvedColorStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FFFF)V");
  jobject obj = env->NewObject(cls, ctor, color.red, color.green, color.blue,
                               color.opacity);
  env->DeleteLocalRef(cls);
  return obj;
}

jobject new_resolved_font(JNIEnv *env, const WuiResolvedFont &font) {
  jclass cls = env->FindClass("dev/waterui/android/runtime/ResolvedFontStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FI)V");
  jobject obj =
      env->NewObject(cls, ctor, font.size, static_cast<jint>(font.weight));
  env->DeleteLocalRef(cls);
  return obj;
}

jobject new_text_style(JNIEnv *env, const WuiTextStyle &style, jclass cls,
                       jmethodID ctor) {
  return env->NewObject(
      cls, ctor, ptr_to_jlong(style.font), style.italic ? JNI_TRUE : JNI_FALSE,
      style.underline ? JNI_TRUE : JNI_FALSE,
      style.strikethrough ? JNI_TRUE : JNI_FALSE,
      ptr_to_jlong(style.foreground), ptr_to_jlong(style.background));
}

jobject new_styled_chunk(JNIEnv *env, const WuiStyledChunk &chunk,
                         jclass chunkCls, jmethodID chunkCtor, jclass styleCls,
                         jmethodID styleCtor) {
  jstring text = wui_str_to_jstring(env, chunk.text);
  jobject styleObj = new_text_style(env, chunk.style, styleCls, styleCtor);
  jobject chunkObj = env->NewObject(chunkCls, chunkCtor, text, styleObj);
  env->DeleteLocalRef(text);
  env->DeleteLocalRef(styleObj);
  return chunkObj;
}

jobject new_styled_str(JNIEnv *env, WuiStyledStr styled) {
  WuiArray_WuiStyledChunk chunks = styled.chunks;
  WuiArraySlice_WuiStyledChunk slice = chunks.vtable.slice(chunks.data);

  jclass styleCls =
      env->FindClass("dev/waterui/android/runtime/TextStyleStruct");
  jmethodID styleCtor = env->GetMethodID(styleCls, "<init>", "(JZZZJJ)V");
  jclass chunkCls =
      env->FindClass("dev/waterui/android/runtime/StyledChunkStruct");
  jmethodID chunkCtor = env->GetMethodID(
      chunkCls, "<init>",
      "(Ljava/lang/String;Ldev/waterui/android/runtime/TextStyleStruct;)V");
  jclass strCls = env->FindClass("dev/waterui/android/runtime/StyledStrStruct");
  jmethodID strCtor = env->GetMethodID(
      strCls, "<init>", "([Ldev/waterui/android/runtime/StyledChunkStruct;)V");

  jobjectArray chunkArray =
      env->NewObjectArray(static_cast<jsize>(slice.len), chunkCls, nullptr);

  for (uintptr_t i = 0; i < slice.len; ++i) {
    jobject chunkObj = new_styled_chunk(env, slice.head[i], chunkCls, chunkCtor,
                                        styleCls, styleCtor);
    env->SetObjectArrayElement(chunkArray, static_cast<jsize>(i), chunkObj);
    env->DeleteLocalRef(chunkObj);
  }

  jobject result = env->NewObject(strCls, strCtor, chunkArray);

  env->DeleteLocalRef(chunkArray);
  env->DeleteLocalRef(strCls);
  env->DeleteLocalRef(chunkCls);
  env->DeleteLocalRef(styleCls);

  chunks.vtable.drop(chunks.data);
  return result;
}

void watcher_styled_str_call(const void *data, WuiStyledStr value,
                             WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject styled = new_styled_str(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), styled,
                 metadata);
  scoped.env->DeleteLocalRef(styled);
}

void watcher_styled_str_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_resolved_color_call(const void *data, WuiResolvedColor value,
                                 WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject color_obj = new_resolved_color(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state),
                 color_obj, metadata);
  scoped.env->DeleteLocalRef(color_obj);
}

void watcher_resolved_color_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_resolved_font_call(const void *data, WuiResolvedFont value,
                                WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject font_obj = new_resolved_font(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state),
                 font_obj, metadata);
  scoped.env->DeleteLocalRef(font_obj);
}

void watcher_resolved_font_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

jobjectArray picker_items_to_java(JNIEnv *env, WuiArray_WuiPickerItem items) {
  WuiArraySlice_WuiPickerItem slice = items.vtable.slice(items.data);
  jclass itemCls =
      env->FindClass("dev/waterui/android/runtime/PickerItemStruct");
  jmethodID itemCtor = env->GetMethodID(
      itemCls, "<init>", "(ILdev/waterui/android/runtime/StyledStrStruct;)V");

  jobjectArray array =
      env->NewObjectArray(static_cast<jsize>(slice.len), itemCls, nullptr);
  for (uintptr_t i = 0; i < slice.len; ++i) {
    const WuiPickerItem &item = slice.head[i];
    WuiStyledStr styled =
        g_sym.waterui_read_computed_styled_str(item.content.content);
    jobject label = new_styled_str(env, styled);
    jobject pickerItem = env->NewObject(
        itemCls, itemCtor, static_cast<jint>(item.tag.inner), label);
    env->SetObjectArrayElement(array, static_cast<jsize>(i), pickerItem);
    env->DeleteLocalRef(label);
    env->DeleteLocalRef(pickerItem);
  }

  env->DeleteLocalRef(itemCls);
  items.vtable.drop(items.data);
  return array;
}

void watcher_picker_items_call(const void *data, WuiArray_WuiPickerItem value,
                               WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject array = picker_items_to_java(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), array,
                 metadata);
  scoped.env->DeleteLocalRef(array);
}

void watcher_picker_items_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_anyview_call(const void *data, WuiAnyView *value,
                          WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject boxed = box_long(scoped.env, ptr_to_jlong(value));
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed,
                 metadata);
  scoped.env->DeleteLocalRef(boxed);
}

void watcher_anyview_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

// ============================================================================
// Reactive State for Theme Colors/Fonts
// ============================================================================

struct WatcherEntry {
  WuiWatcher_ResolvedColor *watcher;
  bool active;
};

struct ReactiveColorState {
  WuiResolvedColor color;
  std::vector<WatcherEntry> watchers;

  void set_color(const WuiResolvedColor &new_color) {
    color = new_color;
    for (auto &entry : watchers) {
      if (entry.active && entry.watcher != nullptr) {
        g_sym.waterui_call_watcher_resolved_color(entry.watcher, color);
      }
    }
  }

  size_t add_watcher(WuiWatcher_ResolvedColor *watcher) {
    size_t index = watchers.size();
    watchers.push_back({watcher, true});
    return index;
  }

  void remove_watcher(size_t index) {
    if (index < watchers.size()) {
      watchers[index].active = false;
      if (watchers[index].watcher != nullptr) {
        g_sym.waterui_drop_watcher_resolved_color(watchers[index].watcher);
      }
      watchers[index].watcher = nullptr;
    }
  }
};

struct ReactiveGuardState {
  ReactiveColorState *color_state;
  size_t watcher_index;
};

WuiResolvedColor reactive_color_get(const void *data) {
  auto *state = static_cast<const ReactiveColorState *>(data);
  return state->color;
}

void reactive_guard_drop(void *data) {
  auto *guard_state = static_cast<ReactiveGuardState *>(data);
  if (guard_state->color_state) {
    guard_state->color_state->remove_watcher(guard_state->watcher_index);
  }
  delete guard_state;
}

WuiWatcherGuard *reactive_color_watch(const void *data,
                                      WuiWatcher_ResolvedColor *watcher) {
  auto *state = const_cast<ReactiveColorState *>(
      static_cast<const ReactiveColorState *>(data));
  size_t index = state->add_watcher(watcher);
  auto *guard_state = new ReactiveGuardState{state, index};
  return g_sym.waterui_new_watcher_guard(guard_state, reactive_guard_drop);
}

void reactive_color_drop(void *data) {
  delete static_cast<ReactiveColorState *>(data);
}

// Reactive Font State
struct WatcherEntryFont {
  WuiWatcher_ResolvedFont *watcher;
  bool active;
};

struct ReactiveFontState {
  WuiResolvedFont font;
  std::vector<WatcherEntryFont> watchers;

  void set_font(const WuiResolvedFont &new_font) {
    font = new_font;
    for (auto &entry : watchers) {
      if (entry.active && entry.watcher != nullptr) {
        g_sym.waterui_call_watcher_resolved_font(entry.watcher, font);
      }
    }
  }

  size_t add_watcher(WuiWatcher_ResolvedFont *watcher) {
    size_t index = watchers.size();
    watchers.push_back({watcher, true});
    return index;
  }

  void remove_watcher(size_t index) {
    if (index < watchers.size()) {
      watchers[index].active = false;
      if (watchers[index].watcher != nullptr) {
        g_sym.waterui_drop_watcher_resolved_font(watchers[index].watcher);
      }
      watchers[index].watcher = nullptr;
    }
  }
};

struct ReactiveGuardStateFont {
  ReactiveFontState *font_state;
  size_t watcher_index;
};

WuiResolvedFont reactive_font_get(const void *data) {
  auto *state = static_cast<const ReactiveFontState *>(data);
  return state->font;
}

void reactive_font_guard_drop(void *data) {
  auto *guard_state = static_cast<ReactiveGuardStateFont *>(data);
  if (guard_state->font_state) {
    guard_state->font_state->remove_watcher(guard_state->watcher_index);
  }
  delete guard_state;
}

WuiWatcherGuard *reactive_font_watch(const void *data,
                                     WuiWatcher_ResolvedFont *watcher) {
  auto *state = const_cast<ReactiveFontState *>(
      static_cast<const ReactiveFontState *>(data));
  size_t index = state->add_watcher(watcher);
  auto *guard_state = new ReactiveGuardStateFont{state, index};
  return g_sym.waterui_new_watcher_guard(guard_state, reactive_font_guard_drop);
}

void reactive_font_drop(void *data) {
  delete static_cast<ReactiveFontState *>(data);
}

WuiResolvedColor argb_to_resolved_color(jint color) {
  const uint32_t argb = static_cast<uint32_t>(color);
  const float a = ((argb >> 24) & 0xFFu) / 255.0f;
  const float r = ((argb >> 16) & 0xFFu) / 255.0f;
  const float g = ((argb >> 8) & 0xFFu) / 255.0f;
  const float b = (argb & 0xFFu) / 255.0f;
  WuiResolvedColor resolved{};
  resolved.red = r;
  resolved.green = g;
  resolved.blue = b;
  resolved.opacity = a;
  resolved.headroom = 0.0f;
  return resolved;
}

// ============================================================================
// WatcherStruct field extraction
// ============================================================================

struct WatcherStructFields {
  jlong data;
  jlong call;
  jlong drop;
};

WatcherStructFields watcher_struct_from_java(JNIEnv *env, jobject watcher_obj) {
  WatcherStructFields fields{0, 0, 0};
  if (watcher_obj == nullptr)
    return fields;
  jclass cls = env->GetObjectClass(watcher_obj);
  jfieldID dataField = env->GetFieldID(cls, "dataPtr", "J");
  jfieldID callField = env->GetFieldID(cls, "callPtr", "J");
  jfieldID dropField = env->GetFieldID(cls, "dropPtr", "J");
  fields.data = env->GetLongField(watcher_obj, dataField);
  fields.call = env->GetLongField(watcher_obj, callField);
  fields.drop = env->GetLongField(watcher_obj, dropField);
  env->DeleteLocalRef(cls);
  return fields;
}

template <typename WatcherT, typename ValueT>
WatcherT *
create_watcher(const WatcherStructFields &fields,
               WatcherT *(*ctor)(void *,
                                 void (*)(void *, ValueT, WuiWatcherMetadata *),
                                 void (*)(void *))) {
  return ctor(jlong_to_ptr<void>(fields.data),
              reinterpret_cast<void (*)(void *, ValueT, WuiWatcherMetadata *)>(
                  fields.call),
              reinterpret_cast<void (*)(void *)>(fields.drop));
}

} // namespace

// ============================================================================
// JNI Lifecycle
// ============================================================================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  g_vm = vm;
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  auto init_class = [&](const char *name) -> jclass {
    jclass local = env->FindClass(name);
    if (local == nullptr)
      return nullptr;
    jclass global = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    return global;
  };

  gBooleanClass = init_class("java/lang/Boolean");
  gIntegerClass = init_class("java/lang/Integer");
  gDoubleClass = init_class("java/lang/Double");
  gLongClass = init_class("java/lang/Long");
  gMetadataClass =
      init_class("dev/waterui/android/reactive/WuiWatcherMetadata");
  gWatcherStructClass = init_class("dev/waterui/android/runtime/WatcherStruct");

  if (!gBooleanClass || !gIntegerClass || !gDoubleClass || !gLongClass ||
      !gMetadataClass || !gWatcherStructClass) {
    return JNI_ERR;
  }

  gBooleanValueOf = env->GetStaticMethodID(gBooleanClass, "valueOf",
                                           "(Z)Ljava/lang/Boolean;");
  gIntegerValueOf = env->GetStaticMethodID(gIntegerClass, "valueOf",
                                           "(I)Ljava/lang/Integer;");
  gDoubleValueOf =
      env->GetStaticMethodID(gDoubleClass, "valueOf", "(D)Ljava/lang/Double;");
  gLongValueOf =
      env->GetStaticMethodID(gLongClass, "valueOf", "(J)Ljava/lang/Long;");
  gMetadataCtor = env->GetMethodID(gMetadataClass, "<init>", "(J)V");
  gWatcherStructCtor =
      env->GetMethodID(gWatcherStructClass, "<init>", "(JJJ)V");

  if (!gBooleanValueOf || !gIntegerValueOf || !gDoubleValueOf ||
      !gLongValueOf || !gMetadataCtor || !gWatcherStructCtor) {
    return JNI_ERR;
  }

  return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
  ScopedEnv scoped;
  if (scoped.env == nullptr)
    return;
  auto release = [&](jclass &cls) {
    if (cls != nullptr) {
      scoped.env->DeleteGlobalRef(cls);
      cls = nullptr;
    }
  };
  release(gBooleanClass);
  release(gIntegerClass);
  release(gDoubleClass);
  release(gLongClass);
  release(gMetadataClass);
  release(gWatcherStructClass);
  gBooleanValueOf = nullptr;
  gIntegerValueOf = nullptr;
  gDoubleValueOf = nullptr;
  gLongValueOf = nullptr;
  gMetadataCtor = nullptr;
  gWatcherStructCtor = nullptr;
  g_vm = nullptr;
}

// ============================================================================
// JNI Exports - WatcherJni class
// ============================================================================

extern "C" {

// Bootstrap - loads symbols from libwaterui_app.so
JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_nativeInit(JNIEnv *env, jclass) {
  constexpr const char *so_name = "libwaterui_app.so";

  void *handle = dlopen(so_name, RTLD_NOW | RTLD_GLOBAL);
  if (handle == nullptr) {
    std::string message = "dlopen failed for ";
    message += so_name;
    message += ": ";
    message += dlerror();
    throw_unsatisfied(env, message);
    return;
  }

  dlerror();
#define LOAD_SYMBOL(name)                                                      \
  g_sym.name = reinterpret_cast<decltype(&::name)>(dlsym(handle, #name));      \
  if (g_sym.name == nullptr) {                                                 \
    std::string error = "Unable to resolve symbol ";                           \
    error += #name;                                                            \
    error += ": ";                                                             \
    error += dlerror();                                                        \
    throw_unsatisfied(env, error);                                             \
    return;                                                                    \
  }
  WATCHER_SYMBOL_LIST(LOAD_SYMBOL)
#undef LOAD_SYMBOL
  g_symbols_ready = true;
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                      "Loaded watcher symbols from %s", so_name);
}

// ========== Watcher Creation ==========

#define DEFINE_WATCHER_CREATOR(JavaName, WatcherType, ValueType)               \
  JNIEXPORT jobject JNICALL                                                    \
      Java_dev_waterui_android_ffi_WatcherJni_##JavaName(JNIEnv *env, jclass,  \
                                                         jobject callback) {   \
    auto *state = create_watcher_state(env, callback);                         \
    return new_watcher_struct(                                                 \
        env, ptr_to_jlong(state),                                              \
        ptr_to_jlong(reinterpret_cast<void *>(watcher_##ValueType##_call)),    \
        ptr_to_jlong(reinterpret_cast<void *>(watcher_##ValueType##_drop)));   \
  }

DEFINE_WATCHER_CREATOR(createBoolWatcher, WuiWatcher_bool, bool)
DEFINE_WATCHER_CREATOR(createIntWatcher, WuiWatcher_i32, int)
DEFINE_WATCHER_CREATOR(createDoubleWatcher, WuiWatcher_f64, double)
DEFINE_WATCHER_CREATOR(createStringWatcher, WuiWatcher_Str, str)
DEFINE_WATCHER_CREATOR(createAnyViewWatcher, WuiWatcher_AnyView, anyview)
DEFINE_WATCHER_CREATOR(createStyledStrWatcher, WuiWatcher_StyledStr, styled_str)
DEFINE_WATCHER_CREATOR(createResolvedColorWatcher, WuiWatcher_ResolvedColor,
                       resolved_color)
DEFINE_WATCHER_CREATOR(createResolvedFontWatcher, WuiWatcher_ResolvedFont,
                       resolved_font)
DEFINE_WATCHER_CREATOR(createPickerItemsWatcher, WuiWatcher_Vec_PickerItem_Id,
                       picker_items)

#undef DEFINE_WATCHER_CREATOR

// ========== Watch Binding ==========

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchBindingBool(JNIEnv *env, jclass,
                                                         jlong bindingPtr,
                                                         jobject watcher) {
  auto *binding = jlong_to_ptr<WuiBinding_bool>(bindingPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_bool, bool>(
      fields, g_sym.waterui_new_watcher_bool);
  return ptr_to_jlong(g_sym.waterui_watch_binding_bool(binding, w));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_watchBindingInt(
    JNIEnv *env, jclass, jlong bindingPtr, jobject watcher) {
  auto *binding = jlong_to_ptr<WuiBinding_i32>(bindingPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_i32, int32_t>(
      fields, g_sym.waterui_new_watcher_i32);
  return ptr_to_jlong(g_sym.waterui_watch_binding_i32(binding, w));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchBindingDouble(JNIEnv *env, jclass,
                                                           jlong bindingPtr,
                                                           jobject watcher) {
  auto *binding = jlong_to_ptr<WuiBinding_f64>(bindingPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_f64, double>(
      fields, g_sym.waterui_new_watcher_f64);
  return ptr_to_jlong(g_sym.waterui_watch_binding_f64(binding, w));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_watchBindingStr(
    JNIEnv *env, jclass, jlong bindingPtr, jobject watcher) {
  auto *binding = jlong_to_ptr<WuiBinding_Str>(bindingPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_Str, WuiStr>(
      fields, g_sym.waterui_new_watcher_str);
  return ptr_to_jlong(g_sym.waterui_watch_binding_str(binding, w));
}

// ========== Watch Computed ==========

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedF64(JNIEnv *env, jclass,
                                                         jlong computedPtr,
                                                         jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_f64>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_f64, double>(
      fields, g_sym.waterui_new_watcher_f64);
  return ptr_to_jlong(g_sym.waterui_watch_computed_f64(computed, w));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedI32(JNIEnv *env, jclass,
                                                         jlong computedPtr,
                                                         jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_i32>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_i32, int32_t>(
      fields, g_sym.waterui_new_watcher_i32);
  return ptr_to_jlong(g_sym.waterui_watch_computed_i32(computed, w));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedStyledStr(
    JNIEnv *env, jclass, jlong computedPtr, jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_StyledStr>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_StyledStr, WuiStyledStr>(
      fields, g_sym.waterui_new_watcher_styled_str);
  return ptr_to_jlong(g_sym.waterui_watch_computed_styled_str(computed, w));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedResolvedColor(
    JNIEnv *env, jclass, jlong computedPtr, jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_ResolvedColor>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_ResolvedColor, WuiResolvedColor>(
      fields, g_sym.waterui_new_watcher_resolved_color);
  return ptr_to_jlong(g_sym.waterui_watch_computed_resolved_color(computed, w));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedResolvedFont(
    JNIEnv *env, jclass, jlong computedPtr, jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_ResolvedFont>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_ResolvedFont, WuiResolvedFont>(
      fields, g_sym.waterui_new_watcher_resolved_font);
  return ptr_to_jlong(g_sym.waterui_watch_computed_resolved_font(computed, w));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedPickerItems(
    JNIEnv *env, jclass, jlong computedPtr, jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_Vec_PickerItem_Id>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w =
      create_watcher<WuiWatcher_Vec_PickerItem_Id, WuiArray_WuiPickerItem>(
          fields, g_sym.waterui_new_watcher_picker_items);
  return ptr_to_jlong(g_sym.waterui_watch_computed_picker_items(computed, w));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedColorScheme(
    JNIEnv *env, jclass, jlong computedPtr, jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_ColorScheme>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_ColorScheme, WuiColorScheme>(
      fields, g_sym.waterui_new_watcher_color_scheme);
  return ptr_to_jlong(g_sym.waterui_watch_computed_color_scheme(computed, w));
}

// ========== Dynamic Connect ==========

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dynamicConnect(
    JNIEnv *env, jclass, jlong dynamicPtr, jobject watcher) {
  auto *dynamic = jlong_to_ptr<WuiDynamic>(dynamicPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_AnyView, WuiAnyView *>(
      fields, g_sym.waterui_new_watcher_any_view);
  g_sym.waterui_dynamic_connect(dynamic, w);
}

// ========== Reactive State Creation ==========

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_createReactiveColorState(JNIEnv *,
                                                                 jclass,
                                                                 jint argb) {
  if (!g_symbols_ready)
    return 0;
  auto *state = new ReactiveColorState{};
  state->color = argb_to_resolved_color(argb);
  return ptr_to_jlong(state);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_reactiveColorStateToComputed(
    JNIEnv *, jclass, jlong statePtr) {
  if (!g_symbols_ready || statePtr == 0)
    return 0;
  auto *state = jlong_to_ptr<ReactiveColorState>(statePtr);
  return ptr_to_jlong(g_sym.waterui_new_computed_resolved_color(
      state, reactive_color_get, reactive_color_watch, reactive_color_drop));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_reactiveColorStateSet(JNIEnv *, jclass,
                                                              jlong statePtr,
                                                              jint argb) {
  if (statePtr == 0)
    return;
  auto *state = jlong_to_ptr<ReactiveColorState>(statePtr);
  state->set_color(argb_to_resolved_color(argb));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_createReactiveFontState(JNIEnv *,
                                                                jclass,
                                                                jfloat size,
                                                                jint weight) {
  if (!g_symbols_ready)
    return 0;
  auto *state = new ReactiveFontState{};
  state->font.size = size;
  state->font.weight = static_cast<WuiFontWeight>(weight);
  return ptr_to_jlong(state);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_reactiveFontStateToComputed(
    JNIEnv *, jclass, jlong statePtr) {
  if (!g_symbols_ready || statePtr == 0)
    return 0;
  auto *state = jlong_to_ptr<ReactiveFontState>(statePtr);
  return ptr_to_jlong(g_sym.waterui_new_computed_resolved_font(
      state, reactive_font_get, reactive_font_watch, reactive_font_drop));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_reactiveFontStateSet(JNIEnv *, jclass,
                                                             jlong statePtr,
                                                             jfloat size,
                                                             jint weight) {
  if (statePtr == 0)
    return;
  auto *state = jlong_to_ptr<ReactiveFontState>(statePtr);
  WuiResolvedFont new_font{};
  new_font.size = size;
  new_font.weight = static_cast<WuiFontWeight>(weight);
  state->set_font(new_font);
}

// ========== Complex Struct Accessors ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedStyledStr(
    JNIEnv *env, jclass, jlong computedPtr) {
  auto *computed = jlong_to_ptr<WuiComputed_StyledStr>(computedPtr);
  WuiStyledStr styled = g_sym.waterui_read_computed_styled_str(computed);
  return new_styled_str(env, styled);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedPickerItems(
    JNIEnv *env, jclass, jlong computedPtr) {
  auto *computed = jlong_to_ptr<WuiComputed_Vec_PickerItem_Id>(computedPtr);
  WuiArray_WuiPickerItem items =
      g_sym.waterui_read_computed_picker_items(computed);
  return picker_items_to_java(env, items);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readBindingStr(JNIEnv *env, jclass,
                                                       jlong bindingPtr) {
  auto *binding = jlong_to_ptr<WuiBinding_Str>(bindingPtr);
  WuiStr value = g_sym.waterui_read_binding_str(binding);
  return wui_str_to_byte_array(env, value);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_setBindingStr(
    JNIEnv *env, jclass, jlong bindingPtr, jbyteArray bytes) {
  auto *binding = jlong_to_ptr<WuiBinding_Str>(bindingPtr);
  WuiStr str = str_from_byte_array(env, bytes);
  g_sym.waterui_set_binding_str(binding, str);
  str._0.vtable.drop(str._0.data);
}

// ========== String Conversion ==========

JNIEXPORT jstring JNICALL
Java_dev_waterui_android_ffi_WatcherJni_wuiStrToString(JNIEnv *env, jclass,
                                                       jlong ptrHi,
                                                       jlong ptrLo) {
  // Reconstruct WuiStr from two longs (pointer to data, vtable)
  // This is a simplified version - we use the view_id function
  auto *view = jlong_to_ptr<WuiAnyView>(ptrHi);
  WuiStr str = g_sym.waterui_view_id(view);
  return wui_str_to_jstring(env, str);
}

JNIEXPORT jstring JNICALL Java_dev_waterui_android_ffi_WatcherJni_viewId(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto *view = jlong_to_ptr<WuiAnyView>(viewPtr);
  WuiStr str = g_sym.waterui_view_id(view);
  return wui_str_to_jstring(env, str);
}

JNIEXPORT jint JNICALL Java_dev_waterui_android_ffi_WatcherJni_viewStretchAxis(
    JNIEnv *, jclass, jlong viewPtr) {
  auto *view = jlong_to_ptr<WuiAnyView>(viewPtr);
  return static_cast<jint>(g_sym.waterui_view_stretch_axis(view));
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsPlain(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto *view = jlong_to_ptr<WuiAnyView>(viewPtr);
  WuiStr str = g_sym.waterui_force_as_plain(view);
  jbyteArray bytes = wui_str_to_byte_array(env, str);
  jclass cls = env->FindClass("dev/waterui/android/runtime/PlainStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "([B)V");
  jobject obj = env->NewObject(cls, ctor, bytes);
  env->DeleteLocalRef(cls);
  env->DeleteLocalRef(bytes);
  return obj;
}

// ========== Layout Functions ==========

// Context for SubView callbacks - holds JNI references for measuring
struct SubViewContext {
  JavaVM *jvm;
  jobject subviewRef;      // Global reference to the SubViewStruct
  jmethodID measureMethod; // Method to measure the view
  jclass subviewClass;     // Global reference to the SubViewStruct class
};

// Measure callback - called by Rust to measure a child view
WuiSize subview_measure(void *context, WuiProposalSize proposal) {
  auto *ctx = static_cast<SubViewContext *>(context);
  JNIEnv *env = nullptr;
  bool wasAttached = false;

  // Get JNI environment
  jint result = ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  if (result == JNI_EDETACHED) {
    ctx->jvm->AttachCurrentThread(&env, nullptr);
    wasAttached = true;
  }

  // Call the measureForLayout method on the SubViewStruct
  // measureForLayout(float proposalWidth, float proposalHeight) returns SizeStruct
  jobject sizeObj = env->CallObjectMethod(ctx->subviewRef, ctx->measureMethod,
                                          proposal.width, proposal.height);

  WuiSize size{};
  if (sizeObj != nullptr) {
    jclass sizeCls = env->GetObjectClass(sizeObj);
    jfieldID widthField = env->GetFieldID(sizeCls, "width", "F");
    jfieldID heightField = env->GetFieldID(sizeCls, "height", "F");
    size.width = env->GetFloatField(sizeObj, widthField);
    size.height = env->GetFloatField(sizeObj, heightField);
    env->DeleteLocalRef(sizeCls);
    env->DeleteLocalRef(sizeObj);
  }

  if (wasAttached) {
    ctx->jvm->DetachCurrentThread();
  }

  return size;
}

// Drop callback - cleans up JNI references
void subview_drop(void *context) {
  auto *ctx = static_cast<SubViewContext *>(context);
  if (ctx == nullptr)
    return;

  JNIEnv *env = nullptr;
  bool wasAttached = false;
  jint result = ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  if (result == JNI_EDETACHED) {
    ctx->jvm->AttachCurrentThread(&env, nullptr);
    wasAttached = true;
  }

  env->DeleteGlobalRef(ctx->subviewRef);
  env->DeleteGlobalRef(ctx->subviewClass);

  if (wasAttached) {
    ctx->jvm->DetachCurrentThread();
  }

  delete ctx;
}

// Holder for the SubView array
struct SubViewArrayHolder {
  WuiSubView *data;
  size_t len;
  JavaVM *jvm; // Keep JVM reference for cleanup
};

WuiArraySlice_WuiSubView subview_slice(const void *opaque) {
  const auto *holder = static_cast<const SubViewArrayHolder *>(opaque);
  WuiArraySlice_WuiSubView slice{};
  slice.head = holder->data;
  slice.len = holder->len;
  return slice;
}

void subview_array_drop(void *opaque) {
  auto *holder = static_cast<SubViewArrayHolder *>(opaque);
  if (holder == nullptr)
    return;
  // Each SubView's drop will clean up its context
  for (size_t i = 0; i < holder->len; ++i) {
    if (holder->data[i].vtable.drop != nullptr) {
      holder->data[i].vtable.drop(holder->data[i].context);
    }
  }
  std::free(holder->data);
  delete holder;
}

WuiProposalSize proposal_from_java(JNIEnv *env, jobject proposal_obj) {
  jclass cls = env->GetObjectClass(proposal_obj);
  jmethodID getWidth = env->GetMethodID(cls, "getWidth", "()F");
  jmethodID getHeight = env->GetMethodID(cls, "getHeight", "()F");
  float width = env->CallFloatMethod(proposal_obj, getWidth);
  float height = env->CallFloatMethod(proposal_obj, getHeight);
  env->DeleteLocalRef(cls);
  WuiProposalSize proposal{};
  proposal.width = width;
  proposal.height = height;
  return proposal;
}

WuiRect rect_from_java(JNIEnv *env, jobject rect_obj) {
  jclass cls = env->GetObjectClass(rect_obj);
  jmethodID getX = env->GetMethodID(cls, "getX", "()F");
  jmethodID getY = env->GetMethodID(cls, "getY", "()F");
  jmethodID getWidth = env->GetMethodID(cls, "getWidth", "()F");
  jmethodID getHeight = env->GetMethodID(cls, "getHeight", "()F");
  float x = env->CallFloatMethod(rect_obj, getX);
  float y = env->CallFloatMethod(rect_obj, getY);
  float width = env->CallFloatMethod(rect_obj, getWidth);
  float height = env->CallFloatMethod(rect_obj, getHeight);
  env->DeleteLocalRef(cls);
  WuiRect rect{};
  rect.origin.x = x;
  rect.origin.y = y;
  rect.size.width = width;
  rect.size.height = height;
  return rect;
}

// Create a WuiSubView from Java SubViewStruct
// SubViewStruct contains: view (View), stretchAxis (StretchAxis), priority (Int)
WuiSubView subview_from_java(JNIEnv *env, JavaVM *jvm, jobject subviewObj) {
  jclass cls = env->GetObjectClass(subviewObj);

  // Get stretchAxis field
  jfieldID stretchField =
      env->GetFieldID(cls, "stretchAxis", "Ldev/waterui/android/runtime/StretchAxis;");
  jobject stretchObj = env->GetObjectField(subviewObj, stretchField);
  jclass stretchCls = env->GetObjectClass(stretchObj);
  jmethodID getValueMethod = env->GetMethodID(stretchCls, "getValue", "()I");
  jint stretchAxis = env->CallIntMethod(stretchObj, getValueMethod);
  env->DeleteLocalRef(stretchCls);
  env->DeleteLocalRef(stretchObj);

  // Get priority field
  jfieldID priorityField = env->GetFieldID(cls, "priority", "I");
  jint priority = env->GetIntField(subviewObj, priorityField);

  // Create the context with global references to SubViewStruct
  auto *ctx = new SubViewContext();
  ctx->jvm = jvm;
  ctx->subviewRef = env->NewGlobalRef(subviewObj);
  ctx->subviewClass = static_cast<jclass>(env->NewGlobalRef(cls));

  // Get the measureForLayout method from SubViewStruct
  ctx->measureMethod = env->GetMethodID(
      cls, "measureForLayout", "(FF)Ldev/waterui/android/runtime/SizeStruct;");

  env->DeleteLocalRef(cls);

  WuiSubView subview{};
  subview.context = ctx;
  subview.vtable.measure = subview_measure;
  subview.vtable.drop = subview_drop;
  subview.stretch_axis = static_cast<WuiStretchAxis>(stretchAxis);
  subview.priority = static_cast<int32_t>(priority);

  return subview;
}

// Create WuiArray_WuiSubView from Java array of SubViewStruct
WuiArray_WuiSubView subviews_from_java(JNIEnv *env, JavaVM *jvm,
                                       jobjectArray subviewsArr) {
  jsize len = env->GetArrayLength(subviewsArr);
  auto *holder = new SubViewArrayHolder();
  holder->len = static_cast<size_t>(len);
  holder->jvm = jvm;
  holder->data =
      static_cast<WuiSubView *>(std::calloc(holder->len, sizeof(WuiSubView)));

  for (jsize i = 0; i < len; ++i) {
    jobject subviewObj = env->GetObjectArrayElement(subviewsArr, i);
    holder->data[i] = subview_from_java(env, jvm, subviewObj);
    env->DeleteLocalRef(subviewObj);
  }

  WuiArray_WuiSubView array{};
  array.data = holder;
  array.vtable.drop = subview_array_drop;
  array.vtable.slice = subview_slice;
  return array;
}

jobject proposal_to_java(JNIEnv *env, const WuiProposalSize &proposal) {
  jclass cls = env->FindClass("dev/waterui/android/runtime/ProposalStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FF)V");
  jobject obj = env->NewObject(cls, ctor, proposal.width, proposal.height);
  env->DeleteLocalRef(cls);
  return obj;
}

jobject size_to_java(JNIEnv *env, const WuiSize &size) {
  jclass cls = env->FindClass("dev/waterui/android/runtime/SizeStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FF)V");
  jobject obj = env->NewObject(cls, ctor, size.width, size.height);
  env->DeleteLocalRef(cls);
  return obj;
}

jobject rect_to_java(JNIEnv *env, const WuiRect &rect) {
  jclass cls = env->FindClass("dev/waterui/android/runtime/RectStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FFFF)V");
  jobject obj = env->NewObject(cls, ctor, rect.origin.x, rect.origin.y,
                               rect.size.width, rect.size.height);
  env->DeleteLocalRef(cls);
  return obj;
}

// ========== Layout Functions ==========

// Get JavaVM from JNIEnv for use in callbacks
static JavaVM *get_java_vm(JNIEnv *env) {
  JavaVM *jvm = nullptr;
  env->GetJavaVM(&jvm);
  return jvm;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_layoutSizeThatFits(
    JNIEnv *env, jclass, jlong layoutPtr, jobject proposalObj,
    jobjectArray subviewsArr) {
  auto *layout = jlong_to_ptr<WuiLayout>(layoutPtr);
  WuiProposalSize proposal = proposal_from_java(env, proposalObj);
  JavaVM *jvm = get_java_vm(env);
  WuiArray_WuiSubView subviews = subviews_from_java(env, jvm, subviewsArr);

  WuiSize size =
      g_sym.waterui_layout_size_that_fits(layout, proposal, subviews);

  return size_to_java(env, size);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_waterui_android_ffi_WatcherJni_layoutPlace(JNIEnv *env, jclass,
                                                    jlong layoutPtr,
                                                    jobject boundsObj,
                                                    jobjectArray subviewsArr) {
  auto *layout = jlong_to_ptr<WuiLayout>(layoutPtr);
  WuiRect bounds = rect_from_java(env, boundsObj);
  JavaVM *jvm = get_java_vm(env);
  WuiArray_WuiSubView subviews = subviews_from_java(env, jvm, subviewsArr);

  WuiArray_WuiRect result = g_sym.waterui_layout_place(layout, bounds, subviews);
  WuiArraySlice_WuiRect slice = result.vtable.slice(result.data);

  jclass cls = env->FindClass("dev/waterui/android/runtime/RectStruct");
  jobjectArray resultArr = env->NewObjectArray(slice.len, cls, nullptr);
  for (uintptr_t i = 0; i < slice.len; ++i) {
    jobject rectObj = rect_to_java(env, slice.head[i]);
    env->SetObjectArrayElement(resultArr, static_cast<jsize>(i), rectObj);
    env->DeleteLocalRef(rectObj);
  }
  env->DeleteLocalRef(cls);
  result.vtable.drop(result.data);
  return resultArr;
}

// ========== Type ID Functions ==========

#define DEFINE_TYPE_ID_FN(javaName, cName)                                     \
  JNIEXPORT jstring JNICALL                                                    \
      Java_dev_waterui_android_ffi_WatcherJni_##javaName(JNIEnv *env,          \
                                                         jclass) {             \
    WuiStr str = g_sym.cName();                                                \
    return wui_str_to_jstring(env, str);                                       \
  }

DEFINE_TYPE_ID_FN(emptyId, waterui_empty_id)
DEFINE_TYPE_ID_FN(textId, waterui_text_id)
DEFINE_TYPE_ID_FN(plainId, waterui_plain_id)
DEFINE_TYPE_ID_FN(buttonId, waterui_button_id)
DEFINE_TYPE_ID_FN(colorId, waterui_color_id)
DEFINE_TYPE_ID_FN(textFieldId, waterui_text_field_id)
DEFINE_TYPE_ID_FN(stepperId, waterui_stepper_id)
DEFINE_TYPE_ID_FN(progressId, waterui_progress_id)
DEFINE_TYPE_ID_FN(dynamicId, waterui_dynamic_id)
DEFINE_TYPE_ID_FN(scrollViewId, waterui_scroll_view_id)
DEFINE_TYPE_ID_FN(spacerId, waterui_spacer_id)
DEFINE_TYPE_ID_FN(toggleId, waterui_toggle_id)
DEFINE_TYPE_ID_FN(sliderId, waterui_slider_id)
DEFINE_TYPE_ID_FN(rendererViewId, waterui_renderer_view_id)
DEFINE_TYPE_ID_FN(fixedContainerId, waterui_fixed_container_id)
DEFINE_TYPE_ID_FN(pickerId, waterui_picker_id)
DEFINE_TYPE_ID_FN(layoutContainerId, waterui_layout_container_id)

#undef DEFINE_TYPE_ID_FN

// ========== Core Functions ==========

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_init(JNIEnv *,
                                                                     jclass) {
  return ptr_to_jlong(g_sym.waterui_init());
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_main(JNIEnv *,
                                                                     jclass) {
  return ptr_to_jlong(g_sym.waterui_main());
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_viewBody(
    JNIEnv *, jclass, jlong viewPtr, jlong envPtr) {
  auto *view = jlong_to_ptr<WuiAnyView>(viewPtr);
  auto *env = jlong_to_ptr<WuiEnv>(envPtr);
  return ptr_to_jlong(g_sym.waterui_view_body(view, env));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_cloneEnv(
    JNIEnv *, jclass, jlong envPtr) {
  auto *env = jlong_to_ptr<WuiEnv>(envPtr);
  return ptr_to_jlong(g_sym.waterui_clone_env(env));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropEnv(
    JNIEnv *, jclass, jlong envPtr) {
  g_sym.waterui_drop_env(jlong_to_ptr<WuiEnv>(envPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropAnyview(
    JNIEnv *, jclass, jlong viewPtr) {
  g_sym.waterui_drop_anyview(jlong_to_ptr<WuiAnyView>(viewPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_configureHotReloadEndpoint(JNIEnv *env,
                                                                   jclass,
                                                                   jstring host,
                                                                   jint port) {
  const char *hostChars = env->GetStringUTFChars(host, nullptr);
  g_sym.waterui_configure_hot_reload_endpoint(hostChars,
                                              static_cast<uint16_t>(port));
  env->ReleaseStringUTFChars(host, hostChars);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_configureHotReloadDirectory(
    JNIEnv *env, jclass, jstring path) {
  const char *pathChars = env->GetStringUTFChars(path, nullptr);
  g_sym.waterui_configure_hot_reload_directory(pathChars);
  env->ReleaseStringUTFChars(path, pathChars);
}

// ========== Force-As Functions ==========

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsText(
    JNIEnv *, jclass, jlong viewPtr) {
  auto text = g_sym.waterui_force_as_text(jlong_to_ptr<WuiAnyView>(viewPtr));
  return ptr_to_jlong(text.content);
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsButton(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto button =
      g_sym.waterui_force_as_button(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = env->FindClass("dev/waterui/android/runtime/ButtonStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(button.label),
                               ptr_to_jlong(button.action));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsColor(
    JNIEnv *, jclass, jlong viewPtr) {
  auto color = g_sym.waterui_force_as_color(jlong_to_ptr<WuiAnyView>(viewPtr));
  return ptr_to_jlong(color);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsTextField(JNIEnv *env, jclass,
                                                         jlong viewPtr) {
  auto field =
      g_sym.waterui_force_as_text_field(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = env->FindClass("dev/waterui/android/runtime/TextFieldStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJI)V");
  jobject obj = env->NewObject(
      cls, ctor, ptr_to_jlong(field.label), ptr_to_jlong(field.value),
      ptr_to_jlong(field.prompt.content), static_cast<jint>(field.keyboard));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsToggle(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto toggle =
      g_sym.waterui_force_as_toggle(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = env->FindClass("dev/waterui/android/runtime/ToggleStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(toggle.label),
                               ptr_to_jlong(toggle.toggle));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsSlider(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto slider =
      g_sym.waterui_force_as_slider(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = env->FindClass("dev/waterui/android/runtime/SliderStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJDDJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(slider.label),
                               ptr_to_jlong(slider.min_value_label),
                               ptr_to_jlong(slider.max_value_label),
                               static_cast<jdouble>(slider.range.start),
                               static_cast<jdouble>(slider.range.end),
                               ptr_to_jlong(slider.value));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsStepper(JNIEnv *env, jclass,
                                                       jlong viewPtr) {
  auto stepper =
      g_sym.waterui_force_as_stepper(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = env->FindClass("dev/waterui/android/runtime/StepperStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJII)V");
  jobject obj = env->NewObject(
      cls, ctor, ptr_to_jlong(stepper.value), ptr_to_jlong(stepper.step),
      ptr_to_jlong(stepper.label), static_cast<jint>(stepper.range.start),
      static_cast<jint>(stepper.range.end));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsProgress(JNIEnv *env, jclass,
                                                        jlong viewPtr) {
  auto progress =
      g_sym.waterui_force_as_progress(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = env->FindClass("dev/waterui/android/runtime/ProgressStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJI)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(progress.label),
                               ptr_to_jlong(progress.value_label),
                               ptr_to_jlong(progress.value),
                               static_cast<jint>(progress.style));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsScrollView(JNIEnv *env, jclass,
                                                          jlong viewPtr) {
  auto scroll =
      g_sym.waterui_force_as_scroll_view(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = env->FindClass("dev/waterui/android/runtime/ScrollStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJ)V");
  jobject obj = env->NewObject(cls, ctor, static_cast<jint>(scroll.axis),
                               ptr_to_jlong(scroll.content));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsPicker(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto picker =
      g_sym.waterui_force_as_picker(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = env->FindClass("dev/waterui/android/runtime/PickerStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(picker.items),
                               ptr_to_jlong(picker.selection));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsLayoutContainer(JNIEnv *env,
                                                               jclass,
                                                               jlong viewPtr) {
  auto container = g_sym.waterui_force_as_layout_container(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      env->FindClass("dev/waterui/android/runtime/LayoutContainerStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(container.layout),
                               ptr_to_jlong(container.contents));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsFixedContainer(JNIEnv *env,
                                                              jclass,
                                                              jlong viewPtr) {
  auto container =
      g_sym.waterui_force_as_fixed_container(jlong_to_ptr<WuiAnyView>(viewPtr));
  // Use the vtable to access the array contents
  WuiArraySlice_____WuiAnyView slice =
      container.contents.vtable.slice(container.contents.data);
  uintptr_t len = slice.len;
  jlongArray childPointers = env->NewLongArray(static_cast<jsize>(len));
  for (uintptr_t i = 0; i < len; ++i) {
    jlong ptr = ptr_to_jlong(slice.head[i]);
    env->SetLongArrayRegion(childPointers, static_cast<jsize>(i), 1, &ptr);
  }
  jclass cls =
      env->FindClass("dev/waterui/android/runtime/FixedContainerStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(J[J)V");
  jobject obj =
      env->NewObject(cls, ctor, ptr_to_jlong(container.layout), childPointers);
  env->DeleteLocalRef(cls);
  env->DeleteLocalRef(childPointers);
  // Don't drop the array here - it's owned by the view
  return obj;
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsDynamic(
    JNIEnv *, jclass, jlong viewPtr) {
  auto dynamic =
      g_sym.waterui_force_as_dynamic(jlong_to_ptr<WuiAnyView>(viewPtr));
  return ptr_to_jlong(dynamic);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsRendererView(JNIEnv *, jclass,
                                                            jlong viewPtr) {
  auto rv =
      g_sym.waterui_force_as_renderer_view(jlong_to_ptr<WuiAnyView>(viewPtr));
  return ptr_to_jlong(rv);
}

// ========== Drop Functions ==========

// ========== AnyViews Functions ==========

JNIEXPORT jint JNICALL Java_dev_waterui_android_ffi_WatcherJni_anyViewsLen(
    JNIEnv *, jclass, jlong handle) {
  return static_cast<jint>(
      g_sym.waterui_anyviews_len(jlong_to_ptr<WuiAnyViews>(handle)));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_anyViewsGetView(
    JNIEnv *, jclass, jlong handle, jint index) {
  return ptr_to_jlong(g_sym.waterui_anyviews_get_view(
      jlong_to_ptr<WuiAnyViews>(handle), static_cast<uintptr_t>(index)));
}

JNIEXPORT jint JNICALL Java_dev_waterui_android_ffi_WatcherJni_anyViewsGetId(
    JNIEnv *, jclass, jlong handle, jint index) {
  return static_cast<jint>(
      g_sym
          .waterui_anyviews_get_id(jlong_to_ptr<WuiAnyViews>(handle),
                                   static_cast<uintptr_t>(index))
          .inner);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropAnyViews(
    JNIEnv *, jclass, jlong handle) {
  g_sym.waterui_drop_anyviews(jlong_to_ptr<WuiAnyViews>(handle));
}

// ========== Binding Read/Write/Drop ==========

JNIEXPORT jboolean JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readBindingBool(JNIEnv *, jclass,
                                                        jlong bindingPtr) {
  return g_sym.waterui_read_binding_bool(
             jlong_to_ptr<WuiBinding_bool>(bindingPtr))
             ? JNI_TRUE
             : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_dev_waterui_android_ffi_WatcherJni_readBindingInt(
    JNIEnv *, jclass, jlong bindingPtr) {
  return g_sym.waterui_read_binding_i32(
      jlong_to_ptr<WuiBinding_i32>(bindingPtr));
}

JNIEXPORT jdouble JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readBindingDouble(JNIEnv *, jclass,
                                                          jlong bindingPtr) {
  return g_sym.waterui_read_binding_f64(
      jlong_to_ptr<WuiBinding_f64>(bindingPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_setBindingBool(
    JNIEnv *, jclass, jlong bindingPtr, jboolean value) {
  g_sym.waterui_set_binding_bool(jlong_to_ptr<WuiBinding_bool>(bindingPtr),
                                 value == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_setBindingInt(
    JNIEnv *, jclass, jlong bindingPtr, jint value) {
  g_sym.waterui_set_binding_i32(jlong_to_ptr<WuiBinding_i32>(bindingPtr),
                                value);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_setBindingDouble(
    JNIEnv *, jclass, jlong bindingPtr, jdouble value) {
  g_sym.waterui_set_binding_f64(jlong_to_ptr<WuiBinding_f64>(bindingPtr),
                                value);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropBindingBool(
    JNIEnv *, jclass, jlong bindingPtr) {
  g_sym.waterui_drop_binding_bool(jlong_to_ptr<WuiBinding_bool>(bindingPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropBindingInt(
    JNIEnv *, jclass, jlong bindingPtr) {
  g_sym.waterui_drop_binding_i32(jlong_to_ptr<WuiBinding_i32>(bindingPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropBindingDouble(JNIEnv *, jclass,
                                                          jlong bindingPtr) {
  g_sym.waterui_drop_binding_f64(jlong_to_ptr<WuiBinding_f64>(bindingPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropBindingStr(
    JNIEnv *, jclass, jlong bindingPtr) {
  g_sym.waterui_drop_binding_str(jlong_to_ptr<WuiBinding_Str>(bindingPtr));
}

// ========== Computed Read/Drop ==========

JNIEXPORT jdouble JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedF64(JNIEnv *, jclass,
                                                        jlong computedPtr) {
  return g_sym.waterui_read_computed_f64(
      jlong_to_ptr<WuiComputed_f64>(computedPtr));
}

JNIEXPORT jint JNICALL Java_dev_waterui_android_ffi_WatcherJni_readComputedI32(
    JNIEnv *, jclass, jlong computedPtr) {
  return g_sym.waterui_read_computed_i32(
      jlong_to_ptr<WuiComputed_i32>(computedPtr));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedResolvedColor(
    JNIEnv *env, jclass, jlong computedPtr) {
  WuiResolvedColor color = g_sym.waterui_read_computed_resolved_color(
      jlong_to_ptr<WuiComputed_ResolvedColor>(computedPtr));
  return new_resolved_color(env, color);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedResolvedFont(
    JNIEnv *env, jclass, jlong computedPtr) {
  WuiResolvedFont font = g_sym.waterui_read_computed_resolved_font(
      jlong_to_ptr<WuiComputed_ResolvedFont>(computedPtr));
  return new_resolved_font(env, font);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropComputedF64(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_f64(jlong_to_ptr<WuiComputed_f64>(computedPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropComputedI32(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_i32(jlong_to_ptr<WuiComputed_i32>(computedPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropComputedResolvedColor(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_resolved_color(
      jlong_to_ptr<WuiComputed_ResolvedColor>(computedPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropComputedResolvedFont(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_resolved_font(
      jlong_to_ptr<WuiComputed_ResolvedFont>(computedPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropComputedStyledStr(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_styled_str(
      jlong_to_ptr<WuiComputed_StyledStr>(computedPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropComputedPickerItems(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_picker_items(
      jlong_to_ptr<WuiComputed_Vec_PickerItem_Id>(computedPtr));
}

// ========== Drop/Resolve Functions ==========

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropLayout(
    JNIEnv *, jclass, jlong layoutPtr) {
  g_sym.waterui_drop_layout(jlong_to_ptr<WuiLayout>(layoutPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropAction(
    JNIEnv *, jclass, jlong actionPtr) {
  g_sym.waterui_drop_action(jlong_to_ptr<WuiAction>(actionPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_callAction(
    JNIEnv *, jclass, jlong actionPtr, jlong envPtr) {
  g_sym.waterui_call_action(jlong_to_ptr<WuiAction>(actionPtr),
                            jlong_to_ptr<WuiEnv>(envPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropDynamic(
    JNIEnv *, jclass, jlong dynamicPtr) {
  g_sym.waterui_drop_dynamic(jlong_to_ptr<WuiDynamic>(dynamicPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropColor(
    JNIEnv *, jclass, jlong colorPtr) {
  g_sym.waterui_drop_color(jlong_to_ptr<WuiColor>(colorPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropFont(
    JNIEnv *, jclass, jlong fontPtr) {
  g_sym.waterui_drop_font(jlong_to_ptr<WuiFont>(fontPtr));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_resolveColor(
    JNIEnv *, jclass, jlong colorPtr, jlong envPtr) {
  return ptr_to_jlong(g_sym.waterui_resolve_color(
      jlong_to_ptr<WuiColor>(colorPtr), jlong_to_ptr<WuiEnv>(envPtr)));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_resolveFont(
    JNIEnv *, jclass, jlong fontPtr, jlong envPtr) {
  return ptr_to_jlong(g_sym.waterui_resolve_font(jlong_to_ptr<WuiFont>(fontPtr),
                                                 jlong_to_ptr<WuiEnv>(envPtr)));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropWatcherGuard(
    JNIEnv *, jclass, jlong guardPtr) {
  g_sym.waterui_drop_box_watcher_guard(jlong_to_ptr<WuiWatcherGuard>(guardPtr));
}

JNIEXPORT jint JNICALL Java_dev_waterui_android_ffi_WatcherJni_getAnimation(
    JNIEnv *, jclass, jlong metadataPtr) {
  return static_cast<jint>(g_sym.waterui_get_animation(
      jlong_to_ptr<WuiWatcherMetadata>(metadataPtr)));
}

// ========== Theme Functions ==========

#define DEFINE_THEME_COLOR_FN(javaName, cName)                                 \
  JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_##javaName(  \
      JNIEnv *, jclass, jlong envPtr) {                                        \
    return ptr_to_jlong(g_sym.cName(jlong_to_ptr<WuiEnv>(envPtr)));            \
  }

DEFINE_THEME_COLOR_FN(themeColorBackground, waterui_theme_color_background)
DEFINE_THEME_COLOR_FN(themeColorSurface, waterui_theme_color_surface)
DEFINE_THEME_COLOR_FN(themeColorSurfaceVariant,
                      waterui_theme_color_surface_variant)
DEFINE_THEME_COLOR_FN(themeColorBorder, waterui_theme_color_border)
DEFINE_THEME_COLOR_FN(themeColorForeground, waterui_theme_color_foreground)
DEFINE_THEME_COLOR_FN(themeColorMutedForeground,
                      waterui_theme_color_muted_foreground)
DEFINE_THEME_COLOR_FN(themeColorAccent, waterui_theme_color_accent)
DEFINE_THEME_COLOR_FN(themeColorAccentForeground,
                      waterui_theme_color_accent_foreground)
DEFINE_THEME_COLOR_FN(themeFontBody, waterui_theme_font_body)
DEFINE_THEME_COLOR_FN(themeFontTitle, waterui_theme_font_title)
DEFINE_THEME_COLOR_FN(themeFontHeadline, waterui_theme_font_headline)
DEFINE_THEME_COLOR_FN(themeFontSubheadline, waterui_theme_font_subheadline)
DEFINE_THEME_COLOR_FN(themeFontCaption, waterui_theme_font_caption)
DEFINE_THEME_COLOR_FN(themeFontFootnote, waterui_theme_font_footnote)

#undef DEFINE_THEME_COLOR_FN

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_themeInstallColor(JNIEnv *, jclass,
                                                          jlong envPtr,
                                                          jint slot,
                                                          jlong signalPtr) {
  g_sym.waterui_theme_install_color(
      jlong_to_ptr<WuiEnv>(envPtr), static_cast<WuiColorSlot>(slot),
      jlong_to_ptr<WuiComputed_ResolvedColor>(signalPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_themeInstallFont(
    JNIEnv *, jclass, jlong envPtr, jint slot, jlong signalPtr) {
  g_sym.waterui_theme_install_font(
      jlong_to_ptr<WuiEnv>(envPtr), static_cast<WuiFontSlot>(slot),
      jlong_to_ptr<WuiComputed_ResolvedFont>(signalPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_themeInstallColorScheme(
    JNIEnv *, jclass, jlong envPtr, jlong signalPtr) {
  g_sym.waterui_theme_install_color_scheme(
      jlong_to_ptr<WuiEnv>(envPtr),
      jlong_to_ptr<WuiComputed_ColorScheme>(signalPtr));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_themeColor(
    JNIEnv *, jclass, jlong envPtr, jint slot) {
  return ptr_to_jlong(g_sym.waterui_theme_color(
      jlong_to_ptr<WuiEnv>(envPtr), static_cast<WuiColorSlot>(slot)));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_themeFont(
    JNIEnv *, jclass, jlong envPtr, jint slot) {
  return ptr_to_jlong(g_sym.waterui_theme_font(jlong_to_ptr<WuiEnv>(envPtr),
                                               static_cast<WuiFontSlot>(slot)));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_themeColorScheme(JNIEnv *, jclass,
                                                         jlong envPtr) {
  return ptr_to_jlong(
      g_sym.waterui_theme_color_scheme(jlong_to_ptr<WuiEnv>(envPtr)));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_computedColorSchemeConstant(
    JNIEnv *, jclass, jint scheme) {
  return ptr_to_jlong(g_sym.waterui_computed_color_scheme_constant(
      static_cast<WuiColorScheme>(scheme)));
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedColorScheme(
    JNIEnv *, jclass, jlong computedPtr) {
  return static_cast<jint>(g_sym.waterui_read_computed_color_scheme(
      jlong_to_ptr<WuiComputed_ColorScheme>(computedPtr)));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropComputedColorScheme(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_color_scheme(
      jlong_to_ptr<WuiComputed_ColorScheme>(computedPtr));
}

// ========== Renderer View ==========

JNIEXPORT jfloat JNICALL
Java_dev_waterui_android_ffi_WatcherJni_rendererViewWidth(JNIEnv *, jclass,
                                                          jlong handle) {
  return g_sym.waterui_renderer_view_width(
      jlong_to_ptr<WuiRendererView>(handle));
}

JNIEXPORT jfloat JNICALL
Java_dev_waterui_android_ffi_WatcherJni_rendererViewHeight(JNIEnv *, jclass,
                                                           jlong handle) {
  return g_sym.waterui_renderer_view_height(
      jlong_to_ptr<WuiRendererView>(handle));
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_ffi_WatcherJni_rendererViewPreferredFormat(
    JNIEnv *, jclass, jlong handle) {
  return static_cast<jint>(g_sym.waterui_renderer_view_preferred_format(
      jlong_to_ptr<WuiRendererView>(handle)));
}

JNIEXPORT jboolean JNICALL
Java_dev_waterui_android_ffi_WatcherJni_rendererViewRenderCpu(
    JNIEnv *env, jclass, jlong handle, jbyteArray pixels, jint width,
    jint height, jint stride, jint format) {
  jbyte *data = env->GetByteArrayElements(pixels, nullptr);
  bool result = g_sym.waterui_renderer_view_render_cpu(
      jlong_to_ptr<WuiRendererView>(handle), reinterpret_cast<uint8_t *>(data),
      static_cast<uint32_t>(width), static_cast<uint32_t>(height),
      static_cast<uintptr_t>(stride),
      static_cast<WuiRendererBufferFormat>(format));
  env->ReleaseByteArrayElements(pixels, data, 0);
  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropRendererView(
    JNIEnv *, jclass, jlong handle) {
  g_sym.waterui_drop_renderer_view(jlong_to_ptr<WuiRendererView>(handle));
}

} // extern "C"
