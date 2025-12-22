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
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <cmath>
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
  X(waterui_call_watcher_color_scheme)                                         \
  X(waterui_drop_watcher_color_scheme)                                         \
  X(waterui_dynamic_connect)                                                   \
  X(waterui_read_computed_styled_str)                                          \
  X(waterui_read_computed_picker_items)                                        \
  X(waterui_read_binding_str)                                                  \
  X(waterui_set_binding_str)                                                   \
  X(waterui_set_binding_secure)                                                \
  X(waterui_secure_field_id)                                                   \
  X(waterui_force_as_secure_field)                                             \
  X(waterui_call_watcher_resolved_color)                                       \
  X(waterui_call_watcher_resolved_font)                                        \
  X(waterui_drop_watcher_resolved_color)                                       \
  X(waterui_drop_watcher_resolved_font)                                        \
  X(waterui_new_computed_resolved_color)                                       \
  X(waterui_new_computed_resolved_font)                                        \
  X(waterui_new_computed_color_scheme)                                         \
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
  X(waterui_date_picker_id)                                                    \
  X(waterui_color_picker_id)                                                   \
  X(waterui_progress_id)                                                       \
  X(waterui_dynamic_id)                                                        \
  X(waterui_scroll_view_id)                                                    \
  X(waterui_spacer_id)                                                         \
  X(waterui_toggle_id)                                                         \
  X(waterui_slider_id)                                                         \
  X(waterui_fixed_container_id)                                                \
  X(waterui_picker_id)                                                         \
  X(waterui_layout_container_id)                                               \
  X(waterui_init)                                                              \
  X(waterui_app)                                                               \
  X(waterui_view_body)                                                         \
  X(waterui_clone_env)                                                         \
  X(waterui_drop_env)                                                          \
  X(waterui_drop_anyview)                                                      \
  X(waterui_force_as_button)                                                   \
  X(waterui_force_as_text)                                                     \
  X(waterui_force_as_color)                                                    \
  X(waterui_force_as_text_field)                                               \
  X(waterui_force_as_toggle)                                                   \
  X(waterui_force_as_slider)                                                   \
  X(waterui_force_as_stepper)                                                  \
  X(waterui_force_as_date_picker)                                              \
  X(waterui_force_as_color_picker)                                             \
  X(waterui_force_as_progress)                                                 \
  X(waterui_force_as_scroll_view)                                              \
  X(waterui_force_as_picker)                                                   \
  X(waterui_force_as_layout_container)                                         \
  X(waterui_force_as_fixed_container)                                          \
  X(waterui_force_as_dynamic)                                                  \
  X(waterui_drop_layout)                                                       \
  X(waterui_drop_action)                                                       \
  X(waterui_call_action)                                                       \
  X(waterui_drop_index_action)                                                 \
  X(waterui_call_index_action)                                                 \
  X(waterui_drop_move_action)                                                  \
  X(waterui_call_move_action)                                                  \
  X(waterui_drop_dynamic)                                                      \
  X(waterui_drop_color)                                                        \
  X(waterui_color_from_srgba)                                                  \
  X(waterui_color_from_linear_rgba_headroom)                                   \
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
  X(waterui_read_binding_color)                                                \
  X(waterui_read_binding_f64)                                                  \
  X(waterui_read_binding_i32)                                                  \
  X(waterui_set_binding_bool)                                                  \
  X(waterui_set_binding_color)                                                 \
  X(waterui_set_binding_f64)                                                   \
  X(waterui_set_binding_i32)                                                   \
  X(waterui_drop_binding_bool)                                                 \
  X(waterui_drop_binding_color)                                                \
  X(waterui_drop_binding_f64)                                                  \
  X(waterui_drop_binding_i32)                                                  \
  X(waterui_drop_binding_str)                                                  \
  X(waterui_read_binding_date)                                                 \
  X(waterui_set_binding_date)                                                  \
  X(waterui_drop_binding_date)                                                 \
  X(waterui_watch_binding_date)                                                \
  X(waterui_new_watcher_date)                                                  \
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
  X(waterui_metadata_env_id)                                                   \
  X(waterui_force_as_metadata_env)                                             \
  X(waterui_metadata_secure_id)                                                \
  X(waterui_force_as_metadata_secure)                                          \
  X(waterui_metadata_standard_dynamic_range_id)                                \
  X(waterui_force_as_metadata_standard_dynamic_range)                          \
  X(waterui_metadata_high_dynamic_range_id)                                    \
  X(waterui_force_as_metadata_high_dynamic_range)                              \
  X(waterui_metadata_gesture_id)                                               \
  X(waterui_force_as_metadata_gesture)                                         \
  X(waterui_metadata_lifecycle_hook_id)                                        \
  X(waterui_force_as_metadata_lifecycle_hook)                                  \
  X(waterui_metadata_on_event_id)                                              \
  X(waterui_force_as_metadata_on_event)                                        \
  X(waterui_metadata_cursor_id)                                                \
  X(waterui_force_as_metadata_cursor)                                          \
  X(waterui_metadata_background_id)                                            \
  X(waterui_force_as_metadata_background)                                      \
  X(waterui_metadata_foreground_id)                                            \
  X(waterui_force_as_metadata_foreground)                                      \
  X(waterui_metadata_shadow_id)                                                \
  X(waterui_force_as_metadata_shadow)                                          \
  X(waterui_metadata_focused_id)                                               \
  X(waterui_force_as_metadata_focused)                                         \
  X(waterui_metadata_ignore_safe_area_id)                                      \
  X(waterui_force_as_metadata_ignore_safe_area)                                \
  X(waterui_metadata_retain_id)                                                \
  X(waterui_force_as_metadata_retain)                                          \
  X(waterui_drop_retain)                                                       \
  X(waterui_metadata_scale_id)                                                 \
  X(waterui_force_as_metadata_scale)                                           \
  X(waterui_metadata_rotation_id)                                              \
  X(waterui_force_as_metadata_rotation)                                        \
  X(waterui_metadata_offset_id)                                                \
  X(waterui_force_as_metadata_offset)                                          \
  X(waterui_metadata_blur_id)                                                  \
  X(waterui_force_as_metadata_blur)                                            \
  X(waterui_metadata_brightness_id)                                            \
  X(waterui_force_as_metadata_brightness)                                      \
  X(waterui_metadata_saturation_id)                                            \
  X(waterui_force_as_metadata_saturation)                                      \
  X(waterui_metadata_contrast_id)                                              \
  X(waterui_force_as_metadata_contrast)                                        \
  X(waterui_metadata_hue_rotation_id)                                          \
  X(waterui_force_as_metadata_hue_rotation)                                    \
  X(waterui_metadata_grayscale_id)                                             \
  X(waterui_force_as_metadata_grayscale)                                       \
  X(waterui_metadata_opacity_id)                                               \
  X(waterui_force_as_metadata_opacity)                                         \
  X(waterui_call_lifecycle_hook)                                               \
  X(waterui_drop_lifecycle_hook)                                               \
  X(waterui_call_on_event)                                                     \
  X(waterui_drop_on_event)                                                     \
  X(waterui_read_computed_cursor_style)                                        \
  X(waterui_watch_computed_cursor_style)                                       \
  X(waterui_drop_computed_cursor_style)                                        \
  X(waterui_new_watcher_cursor_style)                                          \
  X(waterui_read_computed_color)                                               \
  X(waterui_photo_id)                                                          \
  X(waterui_force_as_photo)                                                    \
  X(waterui_video_id)                                                          \
  X(waterui_force_as_video)                                                    \
  X(waterui_video_player_id)                                                   \
  X(waterui_force_as_video_player)                                             \
  X(waterui_webview_id)                                                        \
  X(waterui_force_as_webview)                                                  \
  X(waterui_webview_native_handle)                                             \
  X(waterui_drop_web_view)                                                     \
  X(waterui_read_binding_f32)                                                  \
  X(waterui_set_binding_f32)                                                   \
  X(waterui_drop_binding_f32)                                                  \
  X(waterui_new_watcher_f32)                                                   \
  X(waterui_watch_binding_f32)                                                 \
  X(waterui_read_computed_f32)                                                 \
  X(waterui_watch_computed_f32)                                                \
  X(waterui_drop_computed_f32)                                                 \
  X(waterui_read_computed_str)                                                 \
  X(waterui_watch_computed_str)                                                \
  X(waterui_drop_computed_str)                                                 \
  X(waterui_read_computed_video)                                               \
  X(waterui_watch_computed_video)                                              \
  X(waterui_drop_computed_video)                                               \
  X(waterui_new_watcher_video)                                                 \
  X(waterui_navigation_stack_id)                                               \
  X(waterui_navigation_view_id)                                                \
  X(waterui_tabs_id)                                                           \
  X(waterui_force_as_navigation_stack)                                         \
  X(waterui_force_as_navigation_view)                                          \
  X(waterui_force_as_tabs)                                                     \
  X(waterui_tab_content)                                                       \
  X(waterui_navigation_controller_new)                                         \
  X(waterui_env_install_navigation_controller)                                 \
  X(waterui_drop_navigation_controller)                                        \
  X(waterui_env_install_webview_controller)                                    \
  X(waterui_gpu_surface_id)                                                    \
  X(waterui_force_as_gpu_surface)                                              \
  X(waterui_gpu_surface_init)                                                  \
  X(waterui_gpu_surface_render)                                                \
  X(waterui_gpu_surface_drop)                                                  \
  X(waterui_list_id)                                                           \
  X(waterui_list_item_id)                                                      \
  X(waterui_force_as_list)                                                     \
  X(waterui_force_as_list_item)                                                \
  X(waterui_env_install_media_picker_manager)                                  \
  X(waterui_metadata_clip_shape_id)                                            \
  X(waterui_force_as_metadata_clip_shape)                                      \
  X(waterui_metadata_context_menu_id)                                          \
  X(waterui_force_as_metadata_context_menu)                                    \
  X(waterui_read_computed_menu_items)                                          \
  X(waterui_drop_computed_menu_items)                                          \
  X(waterui_call_shared_action)                                                \
  X(waterui_drop_shared_action)                                                \
  X(waterui_menu_id)                                                           \
  X(waterui_force_as_menu)                                                     \
  X(waterui_filled_shape_id)                                                   \
  X(waterui_force_as_filled_shape)                                             \
  X(waterui_metadata_draggable_id)                                             \
  X(waterui_force_as_metadata_draggable)                                       \
  X(waterui_metadata_drop_destination_id)                                      \
  X(waterui_force_as_metadata_drop_destination)                                \
  X(waterui_draggable_get_data)                                                \
  X(waterui_drop_draggable)                                                    \
  X(waterui_drop_drop_destination)                                             \
  X(waterui_call_drop_handler)                                                 \
  X(waterui_call_drop_enter_handler)                                           \
  X(waterui_call_drop_exit_handler)

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
static jclass gFloatClass = nullptr;
static jmethodID gFloatValueOf = nullptr;
static jclass gLongClass = nullptr;
static jmethodID gLongValueOf = nullptr;
static jclass gMetadataClass = nullptr;
static jmethodID gMetadataCtor = nullptr;
static jclass gWatcherStructClass = nullptr;
static jmethodID gWatcherStructCtor = nullptr;
static jclass gTypeIdStructClass = nullptr;
static jmethodID gTypeIdStructCtor = nullptr;
static jclass gWebViewManagerClass = nullptr;
static jmethodID gWebViewManagerCreate = nullptr;
static jclass gWebViewWrapperClass = nullptr;
static jmethodID gWebViewWrapperGetView = nullptr;
static jmethodID gWebViewWrapperGoBack = nullptr;
static jmethodID gWebViewWrapperGoForward = nullptr;
static jmethodID gWebViewWrapperGoTo = nullptr;
static jmethodID gWebViewWrapperStop = nullptr;
static jmethodID gWebViewWrapperRefresh = nullptr;
static jmethodID gWebViewWrapperCanGoBack = nullptr;
static jmethodID gWebViewWrapperCanGoForward = nullptr;
static jmethodID gWebViewWrapperSetUserAgent = nullptr;
static jmethodID gWebViewWrapperSetRedirectsEnabled = nullptr;
static jmethodID gWebViewWrapperInjectScript = nullptr;
static jmethodID gWebViewWrapperSetEventCallback = nullptr;
static jmethodID gWebViewWrapperRunJavaScript = nullptr;
static jmethodID gWebViewWrapperRelease = nullptr;
static jclass gNativeWebViewEventCallbackClass = nullptr;
static jmethodID gNativeWebViewEventCallbackCtor = nullptr;
static jobject gAppClassLoader = nullptr;
static jmethodID gClassLoaderLoadClass = nullptr;
static jmethodID gClassGetClassLoader = nullptr;

void throw_unsatisfied(JNIEnv *env, const std::string &message) {
  jclass errorClass = env->FindClass("java/lang/UnsatisfiedLinkError");
  if (errorClass == nullptr) {
    env->FatalError(message.c_str());
    return;
  }
  env->ThrowNew(errorClass, message.c_str());
}

void clear_jni_exception(JNIEnv *env, const char *context) {
  if (!env->ExceptionCheck()) {
    return;
  }
  env->ExceptionClear();
  __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                      "Cleared JNI exception while %s", context);
}

bool init_app_class_loader(JNIEnv *env, jclass clazz) {
  if (gAppClassLoader != nullptr && gClassLoaderLoadClass != nullptr) {
    return true;
  }
  if (clazz == nullptr) {
    return false;
  }

  jclass classClass = env->FindClass("java/lang/Class");
  if (classClass == nullptr) {
    clear_jni_exception(env, "finding java/lang/Class");
    return false;
  }

  if (gClassGetClassLoader == nullptr) {
    gClassGetClassLoader = env->GetMethodID(classClass, "getClassLoader",
                                            "()Ljava/lang/ClassLoader;");
    if (gClassGetClassLoader == nullptr) {
      clear_jni_exception(env, "resolving Class.getClassLoader");
      return false;
    }
  }

  jobject loader = env->CallObjectMethod(clazz, gClassGetClassLoader);
  if (env->ExceptionCheck()) {
    clear_jni_exception(env, "getting app ClassLoader");
    return false;
  }
  if (loader == nullptr) {
    return false;
  }

  jclass classLoaderClass = env->FindClass("java/lang/ClassLoader");
  if (classLoaderClass == nullptr) {
    clear_jni_exception(env, "finding java/lang/ClassLoader");
    env->DeleteLocalRef(loader);
    return false;
  }

  if (gClassLoaderLoadClass == nullptr) {
    gClassLoaderLoadClass = env->GetMethodID(
        classLoaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    if (gClassLoaderLoadClass == nullptr) {
      clear_jni_exception(env, "resolving ClassLoader.loadClass");
      env->DeleteLocalRef(loader);
      return false;
    }
  }

  gAppClassLoader = env->NewGlobalRef(loader);
  env->DeleteLocalRef(loader);
  return gAppClassLoader != nullptr;
}

jclass find_app_class(JNIEnv *env, const char *name) {
  if (gAppClassLoader != nullptr && gClassLoaderLoadClass != nullptr) {
    std::string dotted(name);
    for (auto &ch : dotted) {
      if (ch == '/') {
        ch = '.';
      }
    }
    jstring jname = env->NewStringUTF(dotted.c_str());
    if (jname == nullptr) {
      return nullptr;
    }
    auto *cls_obj =
        env->CallObjectMethod(gAppClassLoader, gClassLoaderLoadClass, jname);
    env->DeleteLocalRef(jname);
    if (env->ExceptionCheck()) {
      clear_jni_exception(env, "loading class via ClassLoader");
      return nullptr;
    }
    return static_cast<jclass>(cls_obj);
  }

  jclass cls = env->FindClass(name);
  if (cls == nullptr) {
    clear_jni_exception(env, "finding class");
  }
  return cls;
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

WuiStr str_from_jstring(JNIEnv *env, jstring str) {
  auto *holder =
      static_cast<ByteArrayHolder *>(std::malloc(sizeof(ByteArrayHolder)));
  holder->len = 0;
  holder->data = nullptr;

  if (str != nullptr) {
    const char *chars = env->GetStringUTFChars(str, nullptr);
    jsize len = env->GetStringUTFLength(str);
    holder->len = static_cast<size_t>(len);
    if (holder->len > 0) {
      holder->data = static_cast<uint8_t *>(std::malloc(holder->len));
      std::memcpy(holder->data, chars, holder->len);
    }
    env->ReleaseStringUTFChars(str, chars);
  }

  WuiArray_u8 ffiArray{};
  ffiArray.data = holder;
  ffiArray.vtable.slice = byte_slice;
  ffiArray.vtable.drop = byte_drop;

  WuiStr result{};
  result._0 = ffiArray;
  return result;
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

jobject new_type_id_struct(JNIEnv *env, WuiTypeId typeId) {
  return env->NewObject(gTypeIdStructClass, gTypeIdStructCtor,
                        static_cast<jlong>(typeId.low),
                        static_cast<jlong>(typeId.high));
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

jobject box_float(JNIEnv *env, jfloat value) {
  return env->CallStaticObjectMethod(gFloatClass, gFloatValueOf, value);
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

void watcher_cursor_style_call(const void *data, WuiCursorStyle value,
                               WuiWatcherMetadata *metadata) {
  watcher_int_call(data, static_cast<int32_t>(value), metadata);
}

void watcher_cursor_style_drop(void *data) { watcher_int_drop(data); }

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

void watcher_float_call(const void *data, float value,
                        WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jobject boxed = box_float(scoped.env, value);
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed,
                 metadata);
  scoped.env->DeleteLocalRef(boxed);
}

void watcher_float_drop(void *data) {
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
      find_app_class(env, "dev/waterui/android/runtime/ResolvedColorStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FFFFF)V");
  jobject obj = env->NewObject(cls, ctor, color.red, color.green, color.blue,
                               color.opacity, color.headroom);
  env->DeleteLocalRef(cls);
  return obj;
}

jobject new_resolved_font(JNIEnv *env, const WuiResolvedFont &font) {
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/ResolvedFontStruct");
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
      find_app_class(env, "dev/waterui/android/runtime/TextStyleStruct");
  jmethodID styleCtor = env->GetMethodID(styleCls, "<init>", "(JZZZJJ)V");
  jclass chunkCls =
      find_app_class(env, "dev/waterui/android/runtime/StyledChunkStruct");
  jmethodID chunkCtor = env->GetMethodID(
      chunkCls, "<init>",
      "(Ljava/lang/String;Ldev/waterui/android/runtime/TextStyleStruct;)V");
  jclass strCls =
      find_app_class(env, "dev/waterui/android/runtime/StyledStrStruct");
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
      find_app_class(env, "dev/waterui/android/runtime/PickerItemStruct");
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
  int ref_count = 1;

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

  void clear_watchers() {
    for (auto &entry : watchers) {
      entry.active = false;
      if (entry.watcher != nullptr) {
        g_sym.waterui_drop_watcher_resolved_color(entry.watcher);
        entry.watcher = nullptr;
      }
    }
    watchers.clear();
  }

  void retain() { ++ref_count; }

  void release() {
    if (--ref_count == 0) {
      clear_watchers();
      delete this;
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
    guard_state->color_state->release();
  }
  delete guard_state;
}

WuiWatcherGuard *reactive_color_watch(const void *data,
                                      WuiWatcher_ResolvedColor *watcher) {
  auto *state = const_cast<ReactiveColorState *>(
      static_cast<const ReactiveColorState *>(data));
  size_t index = state->add_watcher(watcher);
  state->retain();
  auto *guard_state = new ReactiveGuardState{state, index};
  return g_sym.waterui_new_watcher_guard(guard_state, reactive_guard_drop);
}

void reactive_color_drop(void *data) {
  auto *state = static_cast<ReactiveColorState *>(data);
  if (state != nullptr) {
    state->release();
  }
}

// Reactive Font State
struct WatcherEntryFont {
  WuiWatcher_ResolvedFont *watcher;
  bool active;
};

struct ReactiveFontState {
  WuiResolvedFont font;
  std::vector<WatcherEntryFont> watchers;
  int ref_count = 1;

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

  void clear_watchers() {
    for (auto &entry : watchers) {
      entry.active = false;
      if (entry.watcher != nullptr) {
        g_sym.waterui_drop_watcher_resolved_font(entry.watcher);
        entry.watcher = nullptr;
      }
    }
    watchers.clear();
  }

  void retain() { ++ref_count; }

  void release() {
    if (--ref_count == 0) {
      clear_watchers();
      delete this;
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
    guard_state->font_state->release();
  }
  delete guard_state;
}

WuiWatcherGuard *reactive_font_watch(const void *data,
                                     WuiWatcher_ResolvedFont *watcher) {
  auto *state = const_cast<ReactiveFontState *>(
      static_cast<const ReactiveFontState *>(data));
  size_t index = state->add_watcher(watcher);
  state->retain();
  auto *guard_state = new ReactiveGuardStateFont{state, index};
  return g_sym.waterui_new_watcher_guard(guard_state, reactive_font_guard_drop);
}

void reactive_font_drop(void *data) {
  auto *state = static_cast<ReactiveFontState *>(data);
  if (state != nullptr) {
    state->release();
  }
}

// Reactive Color Scheme State
struct WatcherEntryScheme {
  WuiWatcher_ColorScheme *watcher;
  bool active;
};

struct ReactiveColorSchemeState {
  WuiColorScheme scheme;
  std::vector<WatcherEntryScheme> watchers;
  int ref_count = 1;

  void set_scheme(WuiColorScheme new_scheme) {
    scheme = new_scheme;
    for (auto &entry : watchers) {
      if (entry.active && entry.watcher != nullptr) {
        g_sym.waterui_call_watcher_color_scheme(entry.watcher, scheme);
      }
    }
  }

  size_t add_watcher(WuiWatcher_ColorScheme *watcher) {
    size_t index = watchers.size();
    watchers.push_back({watcher, true});
    return index;
  }

  void remove_watcher(size_t index) {
    if (index < watchers.size()) {
      watchers[index].active = false;
      if (watchers[index].watcher != nullptr) {
        g_sym.waterui_drop_watcher_color_scheme(watchers[index].watcher);
      }
      watchers[index].watcher = nullptr;
    }
  }

  void clear_watchers() {
    for (auto &entry : watchers) {
      entry.active = false;
      if (entry.watcher != nullptr) {
        g_sym.waterui_drop_watcher_color_scheme(entry.watcher);
        entry.watcher = nullptr;
      }
    }
    watchers.clear();
  }

  void retain() { ++ref_count; }

  void release() {
    if (--ref_count == 0) {
      clear_watchers();
      delete this;
    }
  }
};

struct ReactiveGuardStateScheme {
  ReactiveColorSchemeState *scheme_state;
  size_t watcher_index;
};

WuiColorScheme reactive_color_scheme_get(const void *data) {
  auto *state = static_cast<const ReactiveColorSchemeState *>(data);
  return state->scheme;
}

void reactive_color_scheme_guard_drop(void *data) {
  auto *guard_state = static_cast<ReactiveGuardStateScheme *>(data);
  if (guard_state->scheme_state) {
    guard_state->scheme_state->remove_watcher(guard_state->watcher_index);
    guard_state->scheme_state->release();
  }
  delete guard_state;
}

WuiWatcherGuard *reactive_color_scheme_watch(const void *data,
                                             WuiWatcher_ColorScheme *watcher) {
  auto *state = const_cast<ReactiveColorSchemeState *>(
      static_cast<const ReactiveColorSchemeState *>(data));
  size_t index = state->add_watcher(watcher);
  state->retain();
  auto *guard_state = new ReactiveGuardStateScheme{state, index};
  return g_sym.waterui_new_watcher_guard(guard_state,
                                         reactive_color_scheme_guard_drop);
}

void reactive_color_scheme_drop(void *data) {
  auto *state = static_cast<ReactiveColorSchemeState *>(data);
  if (state != nullptr) {
    state->release();
  }
}

WuiResolvedColor argb_to_resolved_color(jint color) {
  const uint32_t argb = static_cast<uint32_t>(color);
  const float a = ((argb >> 24) & 0xFFu) / 255.0f;
  const float r = ((argb >> 16) & 0xFFu) / 255.0f;
  const float g = ((argb >> 8) & 0xFFu) / 255.0f;
  const float b = (argb & 0xFFu) / 255.0f;
  const auto srgb_to_linear = [](float c) {
    if (c <= 0.04045f) {
      return c / 12.92f;
    }
    return std::pow((c + 0.055f) / 1.055f, 2.4f);
  };
  WuiResolvedColor resolved{};
  resolved.red = srgb_to_linear(r);
  resolved.green = srgb_to_linear(g);
  resolved.blue = srgb_to_linear(b);
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

struct WebViewHandleContext {
  jobject wrapper;
  WuiFn_WuiWebViewEvent watcher;
  bool has_watcher;
};

static bool init_webview_manager_jni(JNIEnv *env) {
  if (gWebViewManagerClass != nullptr && gWebViewManagerCreate != nullptr) {
    return true;
  }

  jclass cls =
      find_app_class(env, "dev/waterui/android/components/WebViewManager");
  if (cls == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to find WebViewManager class");
    return false;
  }

  gWebViewManagerClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
  env->DeleteLocalRef(cls);

  gWebViewManagerCreate = env->GetStaticMethodID(
      gWebViewManagerClass, "create",
      "()Ldev/waterui/android/components/WebViewWrapper;");
  if (gWebViewManagerCreate == nullptr) {
    clear_jni_exception(env, "resolving WebViewManager.create");
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to find WebViewManager.create method");
    return false;
  }

  return true;
}

static bool init_webview_wrapper_jni(JNIEnv *env) {
  if (gWebViewWrapperClass != nullptr && gWebViewWrapperGoBack != nullptr) {
    return true;
  }

  jclass cls =
      find_app_class(env, "dev/waterui/android/components/WebViewWrapper");
  if (cls == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to find WebViewWrapper class");
    return false;
  }

  gWebViewWrapperClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
  env->DeleteLocalRef(cls);

  gWebViewWrapperGetView = env->GetMethodID(gWebViewWrapperClass, "getWebView",
                                            "()Landroid/webkit/WebView;");
  gWebViewWrapperGoBack =
      env->GetMethodID(gWebViewWrapperClass, "goBack", "()V");
  gWebViewWrapperGoForward =
      env->GetMethodID(gWebViewWrapperClass, "goForward", "()V");
  gWebViewWrapperGoTo =
      env->GetMethodID(gWebViewWrapperClass, "goTo", "(Ljava/lang/String;)V");
  gWebViewWrapperStop = env->GetMethodID(gWebViewWrapperClass, "stop", "()V");
  gWebViewWrapperRefresh =
      env->GetMethodID(gWebViewWrapperClass, "refresh", "()V");
  gWebViewWrapperCanGoBack =
      env->GetMethodID(gWebViewWrapperClass, "canGoBack", "()Z");
  gWebViewWrapperCanGoForward =
      env->GetMethodID(gWebViewWrapperClass, "canGoForward", "()Z");
  gWebViewWrapperSetUserAgent = env->GetMethodID(
      gWebViewWrapperClass, "setUserAgent", "(Ljava/lang/String;)V");
  gWebViewWrapperSetRedirectsEnabled =
      env->GetMethodID(gWebViewWrapperClass, "setRedirectsEnabled", "(Z)V");
  gWebViewWrapperInjectScript = env->GetMethodID(
      gWebViewWrapperClass, "injectScript", "(Ljava/lang/String;I)V");
  gWebViewWrapperSetEventCallback = env->GetMethodID(
      gWebViewWrapperClass, "setEventCallback",
      "(Ldev/waterui/android/components/WebViewEventCallback;)V");
  gWebViewWrapperRunJavaScript = env->GetMethodID(
      gWebViewWrapperClass, "runJavaScript", "(Ljava/lang/String;JJ)V");
  gWebViewWrapperRelease =
      env->GetMethodID(gWebViewWrapperClass, "release", "()V");

  if (!gWebViewWrapperGetView || !gWebViewWrapperGoBack ||
      !gWebViewWrapperGoForward || !gWebViewWrapperGoTo ||
      !gWebViewWrapperStop || !gWebViewWrapperRefresh ||
      !gWebViewWrapperCanGoBack || !gWebViewWrapperCanGoForward ||
      !gWebViewWrapperSetUserAgent || !gWebViewWrapperSetRedirectsEnabled ||
      !gWebViewWrapperInjectScript || !gWebViewWrapperSetEventCallback ||
      !gWebViewWrapperRunJavaScript || !gWebViewWrapperRelease) {
    clear_jni_exception(env, "resolving WebViewWrapper methods");
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to resolve WebViewWrapper methods");
    return false;
  }

  return true;
}

static bool init_webview_callback_jni(JNIEnv *env) {
  if (gNativeWebViewEventCallbackClass != nullptr &&
      gNativeWebViewEventCallbackCtor != nullptr) {
    return true;
  }

  jclass cls = find_app_class(
      env, "dev/waterui/android/components/NativeWebViewEventCallback");
  if (cls == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to find NativeWebViewEventCallback class");
    return false;
  }

  gNativeWebViewEventCallbackClass =
      reinterpret_cast<jclass>(env->NewGlobalRef(cls));
  env->DeleteLocalRef(cls);

  gNativeWebViewEventCallbackCtor =
      env->GetMethodID(gNativeWebViewEventCallbackClass, "<init>", "(J)V");
  if (gNativeWebViewEventCallbackCtor == nullptr) {
    clear_jni_exception(env, "resolving NativeWebViewEventCallback ctor");
    __android_log_print(
        ANDROID_LOG_ERROR, LOG_TAG,
        "Failed to find NativeWebViewEventCallback constructor");
    return false;
  }

  return true;
}

static jobject create_webview_wrapper(JNIEnv *env) {
  if (!init_webview_manager_jni(env)) {
    return nullptr;
  }
  jobject wrapper =
      env->CallStaticObjectMethod(gWebViewManagerClass, gWebViewManagerCreate);
  if (env->ExceptionCheck()) {
    clear_jni_exception(env, "creating WebViewWrapper");
    return nullptr;
  }
  return wrapper;
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
  gFloatClass = init_class("java/lang/Float");
  gLongClass = init_class("java/lang/Long");
  gMetadataClass =
      init_class("dev/waterui/android/reactive/WuiWatcherMetadata");
  gWatcherStructClass = init_class("dev/waterui/android/runtime/WatcherStruct");
  gTypeIdStructClass = init_class("dev/waterui/android/runtime/TypeIdStruct");

  if (!gBooleanClass || !gIntegerClass || !gDoubleClass || !gFloatClass ||
      !gLongClass || !gMetadataClass || !gWatcherStructClass ||
      !gTypeIdStructClass) {
    return JNI_ERR;
  }

  gBooleanValueOf = env->GetStaticMethodID(gBooleanClass, "valueOf",
                                           "(Z)Ljava/lang/Boolean;");
  gIntegerValueOf = env->GetStaticMethodID(gIntegerClass, "valueOf",
                                           "(I)Ljava/lang/Integer;");
  gDoubleValueOf =
      env->GetStaticMethodID(gDoubleClass, "valueOf", "(D)Ljava/lang/Double;");
  gFloatValueOf =
      env->GetStaticMethodID(gFloatClass, "valueOf", "(F)Ljava/lang/Float;");
  gLongValueOf =
      env->GetStaticMethodID(gLongClass, "valueOf", "(J)Ljava/lang/Long;");
  gMetadataCtor = env->GetMethodID(gMetadataClass, "<init>", "(J)V");
  gWatcherStructCtor =
      env->GetMethodID(gWatcherStructClass, "<init>", "(JJJ)V");
  gTypeIdStructCtor = env->GetMethodID(gTypeIdStructClass, "<init>", "(JJ)V");

  if (!gBooleanValueOf || !gIntegerValueOf || !gDoubleValueOf ||
      !gFloatValueOf || !gLongValueOf || !gMetadataCtor ||
      !gWatcherStructCtor || !gTypeIdStructCtor) {
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
  auto release_obj = [&](jobject &obj) {
    if (obj != nullptr) {
      scoped.env->DeleteGlobalRef(obj);
      obj = nullptr;
    }
  };
  release(gBooleanClass);
  release(gIntegerClass);
  release(gDoubleClass);
  release(gFloatClass);
  release(gLongClass);
  release(gMetadataClass);
  release(gWatcherStructClass);
  release(gTypeIdStructClass);
  release(gWebViewManagerClass);
  release(gWebViewWrapperClass);
  release(gNativeWebViewEventCallbackClass);
  release_obj(gAppClassLoader);
  gBooleanValueOf = nullptr;
  gIntegerValueOf = nullptr;
  gDoubleValueOf = nullptr;
  gFloatValueOf = nullptr;
  gLongValueOf = nullptr;
  gMetadataCtor = nullptr;
  gWatcherStructCtor = nullptr;
  gTypeIdStructCtor = nullptr;
  gWebViewManagerCreate = nullptr;
  gWebViewWrapperGetView = nullptr;
  gWebViewWrapperGoBack = nullptr;
  gWebViewWrapperGoForward = nullptr;
  gWebViewWrapperGoTo = nullptr;
  gWebViewWrapperStop = nullptr;
  gWebViewWrapperRefresh = nullptr;
  gWebViewWrapperCanGoBack = nullptr;
  gWebViewWrapperCanGoForward = nullptr;
  gWebViewWrapperSetUserAgent = nullptr;
  gWebViewWrapperSetRedirectsEnabled = nullptr;
  gWebViewWrapperInjectScript = nullptr;
  gWebViewWrapperSetEventCallback = nullptr;
  gWebViewWrapperRunJavaScript = nullptr;
  gWebViewWrapperRelease = nullptr;
  gNativeWebViewEventCallbackCtor = nullptr;
  gClassLoaderLoadClass = nullptr;
  gClassGetClassLoader = nullptr;
  g_vm = nullptr;
}

// ============================================================================
// JNI Exports - WatcherJni class
// ============================================================================

extern "C" {

// Rust -> Android callbacks for MediaPickerManager
void waterui_present_media_picker(WuiMediaFilterType filter,
                                  MediaPickerPresentCallback callback);
void waterui_load_media(uint32_t id, MediaLoadCallback callback);
static WuiWebViewHandle create_webview_handle();

// Bootstrap - loads symbols from libwaterui_app.so
JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_nativeInit(JNIEnv *env, jclass clazz) {
  init_app_class_loader(env, clazz);
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
DEFINE_WATCHER_CREATOR(createFloatWatcher, WuiWatcher_f32, float)
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
Java_dev_waterui_android_ffi_WatcherJni_watchComputedF32(JNIEnv *env, jclass,
                                                         jlong computedPtr,
                                                         jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_f32>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_f32, float>(
      fields, g_sym.waterui_new_watcher_f32);
  return ptr_to_jlong(g_sym.waterui_watch_computed_f32(computed, w));
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
Java_dev_waterui_android_ffi_WatcherJni_createReactiveColorSchemeState(
    JNIEnv *, jclass, jint scheme) {
  if (!g_symbols_ready)
    return 0;
  auto *state = new ReactiveColorSchemeState{};
  state->scheme = static_cast<WuiColorScheme>(scheme);
  return ptr_to_jlong(state);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_reactiveColorSchemeStateToComputed(
    JNIEnv *, jclass, jlong statePtr) {
  if (!g_symbols_ready || statePtr == 0)
    return 0;
  auto *state = jlong_to_ptr<ReactiveColorSchemeState>(statePtr);
  return ptr_to_jlong(g_sym.waterui_new_computed_color_scheme(
      state, reactive_color_scheme_get, reactive_color_scheme_watch,
      reactive_color_scheme_drop));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_reactiveColorSchemeStateSet(
    JNIEnv *, jclass, jlong statePtr, jint scheme) {
  if (statePtr == 0)
    return;
  auto *state = jlong_to_ptr<ReactiveColorSchemeState>(statePtr);
  state->set_scheme(static_cast<WuiColorScheme>(scheme));
}

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

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_setBindingSecure(
    JNIEnv *env, jclass, jlong bindingPtr, jbyteArray bytes) {
  auto *binding = jlong_to_ptr<WuiBinding_Secure>(bindingPtr);
  WuiStr str = str_from_byte_array(env, bytes);
  g_sym.waterui_set_binding_secure(binding, str);
  str._0.vtable.drop(str._0.data);
}

// ========== String Conversion ==========

// NOTE: wuiStrToString was removed as it incorrectly used waterui_view_id.
// waterui_view_id now returns WuiTypeId, not WuiStr.

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_viewId(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto *view = jlong_to_ptr<WuiAnyView>(viewPtr);
  WuiTypeId typeId = g_sym.waterui_view_id(view);
  return new_type_id_struct(env, typeId);
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
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/PlainStruct");
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
  jint result =
      ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  if (result == JNI_EDETACHED) {
    ctx->jvm->AttachCurrentThread(&env, nullptr);
    wasAttached = true;
  }

  // Call the measureForLayout method on the SubViewStruct
  // measureForLayout(float proposalWidth, float proposalHeight) returns
  // SizeStruct
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
  jint result =
      ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
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
// SubViewStruct contains: view (View), stretchAxis (StretchAxis), priority
// (Int)
WuiSubView subview_from_java(JNIEnv *env, JavaVM *jvm, jobject subviewObj) {
  jclass cls = env->GetObjectClass(subviewObj);

  // Get stretchAxis field
  jfieldID stretchField = env->GetFieldID(
      cls, "stretchAxis", "Ldev/waterui/android/runtime/StretchAxis;");
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
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/ProposalStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FF)V");
  jobject obj = env->NewObject(cls, ctor, proposal.width, proposal.height);
  env->DeleteLocalRef(cls);
  return obj;
}

jobject size_to_java(JNIEnv *env, const WuiSize &size) {
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/SizeStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FF)V");
  jobject obj = env->NewObject(cls, ctor, size.width, size.height);
  env->DeleteLocalRef(cls);
  return obj;
}

jobject rect_to_java(JNIEnv *env, const WuiRect &rect) {
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/RectStruct");
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

  WuiArray_WuiRect result =
      g_sym.waterui_layout_place(layout, bounds, subviews);
  WuiArraySlice_WuiRect slice = result.vtable.slice(result.data);

  jclass cls = find_app_class(env, "dev/waterui/android/runtime/RectStruct");
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
  JNIEXPORT jobject JNICALL                                                    \
      Java_dev_waterui_android_ffi_WatcherJni_##javaName(JNIEnv *env,          \
                                                         jclass) {             \
    WuiTypeId typeId = g_sym.cName();                                          \
    return new_type_id_struct(env, typeId);                                    \
  }

DEFINE_TYPE_ID_FN(emptyId, waterui_empty_id)
DEFINE_TYPE_ID_FN(textId, waterui_text_id)
DEFINE_TYPE_ID_FN(plainId, waterui_plain_id)
DEFINE_TYPE_ID_FN(buttonId, waterui_button_id)
DEFINE_TYPE_ID_FN(colorId, waterui_color_id)
DEFINE_TYPE_ID_FN(textFieldId, waterui_text_field_id)
DEFINE_TYPE_ID_FN(stepperId, waterui_stepper_id)
DEFINE_TYPE_ID_FN(datePickerId, waterui_date_picker_id)
DEFINE_TYPE_ID_FN(colorPickerId, waterui_color_picker_id)
DEFINE_TYPE_ID_FN(progressId, waterui_progress_id)
DEFINE_TYPE_ID_FN(dynamicId, waterui_dynamic_id)
DEFINE_TYPE_ID_FN(scrollViewId, waterui_scroll_view_id)
DEFINE_TYPE_ID_FN(spacerId, waterui_spacer_id)
DEFINE_TYPE_ID_FN(toggleId, waterui_toggle_id)
DEFINE_TYPE_ID_FN(sliderId, waterui_slider_id)
DEFINE_TYPE_ID_FN(fixedContainerId, waterui_fixed_container_id)
DEFINE_TYPE_ID_FN(pickerId, waterui_picker_id)
DEFINE_TYPE_ID_FN(secureFieldId, waterui_secure_field_id)
DEFINE_TYPE_ID_FN(layoutContainerId, waterui_layout_container_id)
DEFINE_TYPE_ID_FN(metadataEnvId, waterui_metadata_env_id)
DEFINE_TYPE_ID_FN(metadataSecureId, waterui_metadata_secure_id)
DEFINE_TYPE_ID_FN(metadataStandardDynamicRangeId,
                  waterui_metadata_standard_dynamic_range_id)
DEFINE_TYPE_ID_FN(metadataHighDynamicRangeId,
                  waterui_metadata_high_dynamic_range_id)
DEFINE_TYPE_ID_FN(metadataGestureId, waterui_metadata_gesture_id)
DEFINE_TYPE_ID_FN(metadataLifeCycleHookId, waterui_metadata_lifecycle_hook_id)
DEFINE_TYPE_ID_FN(metadataOnEventId, waterui_metadata_on_event_id)
DEFINE_TYPE_ID_FN(metadataCursorId, waterui_metadata_cursor_id)
DEFINE_TYPE_ID_FN(metadataBackgroundId, waterui_metadata_background_id)
DEFINE_TYPE_ID_FN(metadataForegroundId, waterui_metadata_foreground_id)
DEFINE_TYPE_ID_FN(metadataShadowId, waterui_metadata_shadow_id)
DEFINE_TYPE_ID_FN(metadataFocusedId, waterui_metadata_focused_id)
DEFINE_TYPE_ID_FN(metadataIgnoreSafeAreaId,
                  waterui_metadata_ignore_safe_area_id)
DEFINE_TYPE_ID_FN(metadataRetainId, waterui_metadata_retain_id)
DEFINE_TYPE_ID_FN(metadataScaleId, waterui_metadata_scale_id)
DEFINE_TYPE_ID_FN(metadataRotationId, waterui_metadata_rotation_id)
DEFINE_TYPE_ID_FN(metadataOffsetId, waterui_metadata_offset_id)
DEFINE_TYPE_ID_FN(metadataBlurId, waterui_metadata_blur_id)
DEFINE_TYPE_ID_FN(metadataBrightnessId, waterui_metadata_brightness_id)
DEFINE_TYPE_ID_FN(metadataSaturationId, waterui_metadata_saturation_id)
DEFINE_TYPE_ID_FN(metadataContrastId, waterui_metadata_contrast_id)
DEFINE_TYPE_ID_FN(metadataHueRotationId, waterui_metadata_hue_rotation_id)
DEFINE_TYPE_ID_FN(metadataGrayscaleId, waterui_metadata_grayscale_id)
DEFINE_TYPE_ID_FN(metadataOpacityId, waterui_metadata_opacity_id)
DEFINE_TYPE_ID_FN(metadataClipShapeId, waterui_metadata_clip_shape_id)
DEFINE_TYPE_ID_FN(metadataContextMenuId, waterui_metadata_context_menu_id)
DEFINE_TYPE_ID_FN(menuId, waterui_menu_id)
DEFINE_TYPE_ID_FN(filledShapeId, waterui_filled_shape_id)

#undef DEFINE_TYPE_ID_FN

// ========== Core Functions ==========

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_init(JNIEnv *,
                                                                     jclass) {
  return ptr_to_jlong(g_sym.waterui_init());
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_app(JNIEnv *env, jclass, jlong envPtr) {
  // Call waterui_app(env) which returns WuiApp by value
  WuiApp wuiApp = g_sym.waterui_app(jlong_to_ptr<WuiEnv>(envPtr));

  // Get window array slice
  WuiArraySlice_WuiWindow slice =
      wuiApp.windows.vtable.slice(wuiApp.windows.data);

  // Create WindowStruct class and array
  jclass windowCls =
      find_app_class(env, "dev/waterui/android/runtime/WindowStruct");
  jmethodID windowCtor = env->GetMethodID(windowCls, "<init>", "(JZZJJJJI)V");
  jobjectArray windowArray =
      env->NewObjectArray(static_cast<jsize>(slice.len), windowCls, nullptr);

  for (size_t i = 0; i < slice.len; i++) {
    WuiWindow *window = slice.head + i;
    jobject windowObj = env->NewObject(
        windowCls, windowCtor, ptr_to_jlong(window->title),
        static_cast<jboolean>(window->closable),
        static_cast<jboolean>(window->resizable), ptr_to_jlong(window->frame),
        ptr_to_jlong(window->content), ptr_to_jlong(window->state),
        ptr_to_jlong(window->toolbar), static_cast<jint>(window->style));
    env->SetObjectArrayElement(windowArray, static_cast<jsize>(i), windowObj);
    env->DeleteLocalRef(windowObj);
  }
  env->DeleteLocalRef(windowCls);

  // Create AppStruct with env returned from the app
  jclass appCls = find_app_class(env, "dev/waterui/android/runtime/AppStruct");
  jmethodID appCtor = env->GetMethodID(
      appCls, "<init>", "([Ldev/waterui/android/runtime/WindowStruct;J)V");
  jobject appObj =
      env->NewObject(appCls, appCtor, windowArray, ptr_to_jlong(wuiApp.env));
  env->DeleteLocalRef(appCls);

  return appObj;
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_envInstallMediaPickerManager(
    JNIEnv *, jclass, jlong envPtr) {
  auto *env = jlong_to_ptr<WuiEnv>(envPtr);
  g_sym.waterui_env_install_media_picker_manager(
      env, waterui_present_media_picker, waterui_load_media);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_envInstallWebViewController(
    JNIEnv *env, jclass clazz, jlong envPtr) {
  init_app_class_loader(env, clazz);
  init_webview_manager_jni(env);
  init_webview_wrapper_jni(env);
  init_webview_callback_jni(env);
  auto *wui_env = jlong_to_ptr<WuiEnv>(envPtr);
  g_sym.waterui_env_install_webview_controller(wui_env, create_webview_handle);
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
  // TODO: Hot reload not yet implemented for Android FFI
  (void)env;
  (void)host;
  (void)port;
  __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                      "Hot reload endpoint configuration not yet implemented");
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_configureHotReloadDirectory(
    JNIEnv *env, jclass, jstring path) {
  // TODO: Hot reload not yet implemented for Android FFI
  (void)env;
  (void)path;
  __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                      "Hot reload directory configuration not yet implemented");
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
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/ButtonStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJI)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(button.label),
                               ptr_to_jlong(button.action),
                               static_cast<jint>(button.style));
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
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/TextFieldStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJI)V");
  jobject obj = env->NewObject(
      cls, ctor, ptr_to_jlong(field.label), ptr_to_jlong(field.value),
      ptr_to_jlong(field.prompt.content), static_cast<jint>(field.keyboard));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsSecureField(JNIEnv *env, jclass,
                                                           jlong viewPtr) {
  auto field =
      g_sym.waterui_force_as_secure_field(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/SecureFieldStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(field.label),
                               ptr_to_jlong(field.value));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsToggle(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto toggle =
      g_sym.waterui_force_as_toggle(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/ToggleStruct");
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
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/SliderStruct");
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
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/StepperStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJII)V");
  jobject obj = env->NewObject(
      cls, ctor, ptr_to_jlong(stepper.value), ptr_to_jlong(stepper.step),
      ptr_to_jlong(stepper.label), static_cast<jint>(stepper.range.start),
      static_cast<jint>(stepper.range.end));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsDatePicker(JNIEnv *env, jclass,
                                                          jlong viewPtr) {
  auto picker =
      g_sym.waterui_force_as_date_picker(jlong_to_ptr<WuiAnyView>(viewPtr));

  // Create DateStruct for start
  jclass dateStructCls =
      find_app_class(env, "dev/waterui/android/runtime/DateStruct");
  jmethodID dateStructCtor =
      env->GetMethodID(dateStructCls, "<init>", "(III)V");
  jobject startDate = env->NewObject(
      dateStructCls, dateStructCtor, static_cast<jint>(picker.range.start.year),
      static_cast<jint>(picker.range.start.month),
      static_cast<jint>(picker.range.start.day));
  jobject endDate = env->NewObject(dateStructCls, dateStructCtor,
                                   static_cast<jint>(picker.range.end.year),
                                   static_cast<jint>(picker.range.end.month),
                                   static_cast<jint>(picker.range.end.day));

  // Create DateRangeStruct
  jclass dateRangeStructCls =
      find_app_class(env, "dev/waterui/android/runtime/DateRangeStruct");
  jmethodID dateRangeStructCtor =
      env->GetMethodID(dateRangeStructCls, "<init>",
                       "(Ldev/waterui/android/runtime/DateStruct;Ldev/waterui/"
                       "android/runtime/DateStruct;)V");
  jobject range = env->NewObject(dateRangeStructCls, dateRangeStructCtor,
                                 startDate, endDate);

  // Create DatePickerStruct
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/DatePickerStruct");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(JJLdev/waterui/android/runtime/DateRangeStruct;I)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(picker.label),
                               ptr_to_jlong(picker.value), range,
                               static_cast<jint>(picker.ty));

  env->DeleteLocalRef(dateStructCls);
  env->DeleteLocalRef(startDate);
  env->DeleteLocalRef(endDate);
  env->DeleteLocalRef(dateRangeStructCls);
  env->DeleteLocalRef(range);
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsColorPicker(JNIEnv *env, jclass,
                                                           jlong viewPtr) {
  auto picker =
      g_sym.waterui_force_as_color_picker(jlong_to_ptr<WuiAnyView>(viewPtr));

  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/ColorPickerStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJZZ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(picker.label),
                               ptr_to_jlong(picker.value),
                               static_cast<jboolean>(picker.support_alpha),
                               static_cast<jboolean>(picker.support_hdr));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsProgress(JNIEnv *env, jclass,
                                                        jlong viewPtr) {
  auto progress =
      g_sym.waterui_force_as_progress(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/ProgressStruct");
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
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/ScrollStruct");
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
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/PickerStruct");
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
      find_app_class(env, "dev/waterui/android/runtime/LayoutContainerStruct");
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
      find_app_class(env, "dev/waterui/android/runtime/FixedContainerStruct");
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

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataEnv(JNIEnv *env, jclass,
                                                           jlong viewPtr) {
  auto metadata =
      g_sym.waterui_force_as_metadata_env(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataEnvStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataSecure(JNIEnv *env,
                                                              jclass,
                                                              jlong viewPtr) {
  auto metadata =
      g_sym.waterui_force_as_metadata_secure(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataSecureStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataStandardDynamicRange(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_standard_dynamic_range(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataStandardDynamicRangeStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataHighDynamicRange(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_high_dynamic_range(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataHighDynamicRangeStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataGesture(JNIEnv *env,
                                                               jclass,
                                                               jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_gesture(
      jlong_to_ptr<WuiAnyView>(viewPtr));

  // Create GestureDataStruct
  jclass gestureDataCls =
      find_app_class(env, "dev/waterui/android/runtime/GestureDataStruct");
  jmethodID gestureDataCtor =
      env->GetMethodID(gestureDataCls, "<init>", "(IIFFFJJ)V");

  // Extract gesture data based on tag
  int tapCount = 1;
  int longPressDuration = 500;
  float dragMinDistance = 10.0f;
  float magnificationInitialScale = 1.0f;
  float rotationInitialAngle = 0.0f;
  jlong thenFirstPtr = 0;
  jlong thenSecondPtr = 0;

  switch (metadata.value.gesture.tag) {
  case WuiGesture_Tap:
    tapCount = metadata.value.gesture.tap.count;
    break;
  case WuiGesture_LongPress:
    longPressDuration = metadata.value.gesture.long_press.duration;
    break;
  case WuiGesture_Drag:
    dragMinDistance = metadata.value.gesture.drag.min_distance;
    break;
  case WuiGesture_Magnification:
    magnificationInitialScale =
        metadata.value.gesture.magnification.initial_scale;
    break;
  case WuiGesture_Rotation:
    rotationInitialAngle = metadata.value.gesture.rotation.initial_angle;
    break;
  case WuiGesture_Then:
    thenFirstPtr = ptr_to_jlong(metadata.value.gesture.then.first);
    thenSecondPtr = ptr_to_jlong(metadata.value.gesture.then.then);
    break;
  }

  jobject gestureData = env->NewObject(
      gestureDataCls, gestureDataCtor, tapCount, longPressDuration,
      dragMinDistance, magnificationInitialScale, rotationInitialAngle,
      thenFirstPtr, thenSecondPtr);

  // Create MetadataGestureStruct
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataGestureStruct");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(JILdev/waterui/android/runtime/GestureDataStruct;J)V");
  jobject obj =
      env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                     static_cast<jint>(metadata.value.gesture.tag), gestureData,
                     ptr_to_jlong(metadata.value.action));
  env->DeleteLocalRef(gestureDataCls);
  env->DeleteLocalRef(gestureData);
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataLifeCycleHook(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_lifecycle_hook(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataLifeCycleHookStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JIJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               static_cast<jint>(metadata.value.lifecycle),
                               ptr_to_jlong(metadata.value.handler));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataOnEvent(JNIEnv *env,
                                                               jclass,
                                                               jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_on_event(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataOnEventStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JIJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               static_cast<jint>(metadata.value.event),
                               ptr_to_jlong(metadata.value.handler));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataCursor(JNIEnv *env,
                                                              jclass,
                                                              jlong viewPtr) {
  auto metadata =
      g_sym.waterui_force_as_metadata_cursor(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataCursorStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.style));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataBackground(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_background(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataBackgroundStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JIJJ)V");

  jlong colorPtr = 0;
  jlong imagePtr = 0;
  if (metadata.value.tag == WuiBackground_Color) {
    colorPtr = ptr_to_jlong(metadata.value.color.color);
  } else if (metadata.value.tag == WuiBackground_Image) {
    imagePtr = ptr_to_jlong(metadata.value.image.image);
  }

  jobject obj =
      env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                     static_cast<jint>(metadata.value.tag), colorPtr, imagePtr);
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataForeground(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_foreground(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataForegroundStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.color));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataShadow(JNIEnv *env,
                                                              jclass,
                                                              jlong viewPtr) {
  auto metadata =
      g_sym.waterui_force_as_metadata_shadow(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataShadowStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJFFF)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.color),
                               metadata.value.offset_x, metadata.value.offset_y,
                               metadata.value.radius);
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataFocused(JNIEnv *env,
                                                               jclass,
                                                               jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_focused(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataFocusedStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.binding));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataIgnoreSafeArea(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_ignore_safe_area(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataIgnoreSafeAreaStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JZZZZ)V");
  jobject obj =
      env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                     static_cast<jboolean>(metadata.value.edges.top),
                     static_cast<jboolean>(metadata.value.edges.bottom),
                     static_cast<jboolean>(metadata.value.edges.leading),
                     static_cast<jboolean>(metadata.value.edges.trailing));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataRetain(JNIEnv *env,
                                                              jclass,
                                                              jlong viewPtr) {
  auto metadata =
      g_sym.waterui_force_as_metadata_retain(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataRetainStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value._opaque));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataScale(JNIEnv *env,
                                                             jclass,
                                                             jlong viewPtr) {
  auto metadata =
      g_sym.waterui_force_as_metadata_scale(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataScaleStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJFF)V");
  jobject obj = env->NewObject(
      cls, ctor, ptr_to_jlong(metadata.content), ptr_to_jlong(metadata.value.x),
      ptr_to_jlong(metadata.value.y), metadata.value.anchor.x,
      metadata.value.anchor.y);
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataRotation(JNIEnv *env,
                                                                jclass,
                                                                jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_rotation(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataRotationStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJFF)V");
  jobject obj =
      env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                     ptr_to_jlong(metadata.value.angle),
                     metadata.value.anchor.x, metadata.value.anchor.y);
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataOffset(JNIEnv *env,
                                                              jclass,
                                                              jlong viewPtr) {
  auto metadata =
      g_sym.waterui_force_as_metadata_offset(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataOffsetStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.x),
                               ptr_to_jlong(metadata.value.y));
  env->DeleteLocalRef(cls);
  return obj;
}

// ========== Filter Metadata Force-As Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataBlur(JNIEnv *env, jclass,
                                                            jlong viewPtr) {
  auto metadata =
      g_sym.waterui_force_as_metadata_blur(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataBlurStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.radius));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataBrightness(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_brightness(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataBrightnessStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.amount));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataSaturation(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_saturation(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataSaturationStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.amount));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataContrast(JNIEnv *env,
                                                                jclass,
                                                                jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_contrast(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataContrastStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.amount));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataHueRotation(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_hue_rotation(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataHueRotationStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.angle));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataGrayscale(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_grayscale(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataGrayscaleStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.intensity));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataOpacity(JNIEnv *env,
                                                               jclass,
                                                               jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_opacity(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MetadataOpacityStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.value));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataClipShape(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_clip_shape(
      jlong_to_ptr<WuiAnyView>(viewPtr));

  // Get path commands array
  WuiArraySlice_WuiPathCommand slice =
      metadata.value.commands.vtable.slice(metadata.value.commands.data);

  // Create PathCommandStruct class and array
  jclass cmdCls =
      find_app_class(env, "dev/waterui/android/runtime/PathCommandStruct");
  // Constructor: (tag, x, y, cx, cy, c1x, c1y, c2x, c2y, rx, ry, start, sweep)
  jmethodID cmdCtor = env->GetMethodID(cmdCls, "<init>", "(IFFFFFFFFFFFF)V");

  jobjectArray cmdArray =
      env->NewObjectArray(static_cast<jsize>(slice.len), cmdCls, nullptr);

  for (size_t i = 0; i < slice.len; i++) {
    WuiPathCommand cmd = slice.head[i];
    float x = 0, y = 0, cx = 0, cy = 0, c1x = 0, c1y = 0, c2x = 0, c2y = 0;
    float rx = 0, ry = 0, start = 0, sweep = 0;

    switch (cmd.tag) {
    case WuiPathCommand_MoveTo:
      x = cmd.move_to.x;
      y = cmd.move_to.y;
      break;
    case WuiPathCommand_LineTo:
      x = cmd.line_to.x;
      y = cmd.line_to.y;
      break;
    case WuiPathCommand_QuadTo:
      cx = cmd.quad_to.cx;
      cy = cmd.quad_to.cy;
      x = cmd.quad_to.x;
      y = cmd.quad_to.y;
      break;
    case WuiPathCommand_CubicTo:
      c1x = cmd.cubic_to.c1x;
      c1y = cmd.cubic_to.c1y;
      c2x = cmd.cubic_to.c2x;
      c2y = cmd.cubic_to.c2y;
      x = cmd.cubic_to.x;
      y = cmd.cubic_to.y;
      break;
    case WuiPathCommand_Arc:
      cx = cmd.arc.cx;
      cy = cmd.arc.cy;
      rx = cmd.arc.rx;
      ry = cmd.arc.ry;
      start = cmd.arc.start;
      sweep = cmd.arc.sweep;
      break;
    case WuiPathCommand_Close:
      // No additional data needed
      break;
    }

    jobject cmdObj =
        env->NewObject(cmdCls, cmdCtor, static_cast<jint>(cmd.tag), x, y, cx,
                       cy, c1x, c1y, c2x, c2y, rx, ry, start, sweep);
    env->SetObjectArrayElement(cmdArray, static_cast<jsize>(i), cmdObj);
    env->DeleteLocalRef(cmdObj);
  }

  // Create MetadataClipShapeStruct
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataClipShapeStruct");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(J[Ldev/waterui/android/runtime/PathCommandStruct;)V");
  jobject obj =
      env->NewObject(cls, ctor, ptr_to_jlong(metadata.content), cmdArray);

  env->DeleteLocalRef(cmdCls);
  env->DeleteLocalRef(cmdArray);
  env->DeleteLocalRef(cls);

  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataContextMenu(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_context_menu(
      jlong_to_ptr<WuiAnyView>(viewPtr));

  // Create MetadataContextMenuStruct
  jclass cls = find_app_class(
      env, "dev/waterui/android/runtime/MetadataContextMenuStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.items));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsMenu(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto menu = g_sym.waterui_force_as_menu(jlong_to_ptr<WuiAnyView>(viewPtr));

  // Create MenuStruct
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/MenuStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(menu.label),
                               ptr_to_jlong(menu.items));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobjectArray JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedMenuItems(
    JNIEnv *env, jclass, jlong computedPtr) {
  auto items = g_sym.waterui_read_computed_menu_items(
      jlong_to_ptr<Computed_MenuItems>(computedPtr));

  // Get the array slice
  auto slice = items.vtable.slice(items.data);

  // Create MenuItemStruct class and array
  jclass itemCls =
      find_app_class(env, "dev/waterui/android/runtime/MenuItemStruct");
  jmethodID itemCtor = env->GetMethodID(itemCls, "<init>", "(JJ)V");
  jobjectArray itemArray =
      env->NewObjectArray(static_cast<jsize>(slice.len), itemCls, nullptr);

  for (size_t i = 0; i < slice.len; i++) {
    WuiMenuItem item = slice.head[i];
    jobject itemObj =
        env->NewObject(itemCls, itemCtor, ptr_to_jlong(item.label.content),
                       ptr_to_jlong(item.action));
    env->SetObjectArrayElement(itemArray, static_cast<jsize>(i), itemObj);
    env->DeleteLocalRef(itemObj);
  }

  env->DeleteLocalRef(itemCls);
  return itemArray;
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropComputedMenuItems(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_menu_items(
      jlong_to_ptr<Computed_MenuItems>(computedPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_callSharedAction(
    JNIEnv *, jclass, jlong actionPtr, jlong envPtr) {
  g_sym.waterui_call_shared_action(jlong_to_ptr<WuiSharedAction>(actionPtr),
                                   jlong_to_ptr<WuiEnv>(envPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropSharedAction(
    JNIEnv *, jclass, jlong actionPtr) {
  g_sym.waterui_drop_shared_action(jlong_to_ptr<WuiSharedAction>(actionPtr));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsFilledShape(JNIEnv *env, jclass,
                                                           jlong viewPtr) {
  auto filled =
      g_sym.waterui_force_as_filled_shape(jlong_to_ptr<WuiAnyView>(viewPtr));

  // Get path commands array
  WuiArraySlice_WuiPathCommand slice =
      filled.commands.vtable.slice(filled.commands.data);

  // Create PathCommandStruct class and array
  jclass cmdCls =
      find_app_class(env, "dev/waterui/android/runtime/PathCommandStruct");
  jmethodID cmdCtor = env->GetMethodID(cmdCls, "<init>", "(IFFFFFFFFFFFF)V");

  jobjectArray cmdArray =
      env->NewObjectArray(static_cast<jsize>(slice.len), cmdCls, nullptr);

  for (size_t i = 0; i < slice.len; i++) {
    WuiPathCommand cmd = slice.head[i];
    float x = 0, y = 0, cx = 0, cy = 0, c1x = 0, c1y = 0, c2x = 0, c2y = 0;
    float rx = 0, ry = 0, start = 0, sweep = 0;

    switch (cmd.tag) {
    case WuiPathCommand_MoveTo:
      x = cmd.move_to.x;
      y = cmd.move_to.y;
      break;
    case WuiPathCommand_LineTo:
      x = cmd.line_to.x;
      y = cmd.line_to.y;
      break;
    case WuiPathCommand_QuadTo:
      cx = cmd.quad_to.cx;
      cy = cmd.quad_to.cy;
      x = cmd.quad_to.x;
      y = cmd.quad_to.y;
      break;
    case WuiPathCommand_CubicTo:
      c1x = cmd.cubic_to.c1x;
      c1y = cmd.cubic_to.c1y;
      c2x = cmd.cubic_to.c2x;
      c2y = cmd.cubic_to.c2y;
      x = cmd.cubic_to.x;
      y = cmd.cubic_to.y;
      break;
    case WuiPathCommand_Arc:
      cx = cmd.arc.cx;
      cy = cmd.arc.cy;
      rx = cmd.arc.rx;
      ry = cmd.arc.ry;
      start = cmd.arc.start;
      sweep = cmd.arc.sweep;
      break;
    case WuiPathCommand_Close:
      break;
    }

    jobject cmdObj =
        env->NewObject(cmdCls, cmdCtor, static_cast<jint>(cmd.tag), x, y, cx,
                       cy, c1x, c1y, c2x, c2y, rx, ry, start, sweep);
    env->SetObjectArrayElement(cmdArray, static_cast<jsize>(i), cmdObj);
    env->DeleteLocalRef(cmdObj);
  }

  // Create FilledShapeStruct
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/FilledShapeStruct");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "([Ldev/waterui/android/runtime/PathCommandStruct;J)V");
  jobject obj = env->NewObject(cls, ctor, cmdArray, ptr_to_jlong(filled.fill));

  env->DeleteLocalRef(cmdCls);
  env->DeleteLocalRef(cmdArray);
  env->DeleteLocalRef(cls);

  return obj;
}

// ========== OnEvent Handler Functions ==========

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_callLifeCycleHook(JNIEnv *, jclass,
                                                          jlong handlerPtr,
                                                          jlong envPtr) {
  g_sym.waterui_call_lifecycle_hook(
      jlong_to_ptr<WuiLifeCycleHookHandler>(handlerPtr),
      jlong_to_ptr<WuiEnv>(envPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropLifeCycleHook(JNIEnv *, jclass,
                                                          jlong handlerPtr) {
  g_sym.waterui_drop_lifecycle_hook(
      jlong_to_ptr<WuiLifeCycleHookHandler>(handlerPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_callOnEvent(
    JNIEnv *, jclass, jlong handlerPtr, jlong envPtr) {
  g_sym.waterui_call_on_event(jlong_to_ptr<WuiOnEventHandler>(handlerPtr),
                              jlong_to_ptr<WuiEnv>(envPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropOnEvent(
    JNIEnv *, jclass, jlong handlerPtr) {
  g_sym.waterui_drop_on_event(jlong_to_ptr<WuiOnEventHandler>(handlerPtr));
}

// ========== Cursor Style Computed Functions ==========

JNIEXPORT jint JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedCursorStyle(
    JNIEnv *, jclass, jlong computedPtr) {
  return static_cast<jint>(g_sym.waterui_read_computed_cursor_style(
      jlong_to_ptr<WuiComputed_CursorStyle>(computedPtr)));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedCursorStyle(
    JNIEnv *env, jclass, jlong computedPtr, jobject watcher) {
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_CursorStyle, WuiCursorStyle>(
      fields, g_sym.waterui_new_watcher_cursor_style);
  auto guard = g_sym.waterui_watch_computed_cursor_style(
      jlong_to_ptr<WuiComputed_CursorStyle>(computedPtr), w);
  return ptr_to_jlong(guard);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropComputedCursorStyle(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_cursor_style(
      jlong_to_ptr<WuiComputed_CursorStyle>(computedPtr));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_createCursorStyleWatcher(
    JNIEnv *env, jclass, jobject callback) {
  auto *state = create_watcher_state(env, callback);
  return new_watcher_struct(
      env, ptr_to_jlong(state),
      ptr_to_jlong(reinterpret_cast<void *>(watcher_cursor_style_call)),
      ptr_to_jlong(reinterpret_cast<void *>(watcher_cursor_style_drop)));
}

// ========== Drop Functions ==========

// ========== Retain Functions ==========

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropRetain(
    JNIEnv *, jclass, jlong retainPtr) {
  // The retainPtr is the _opaque pointer from WuiRetain struct
  WuiRetain retain;
  retain._opaque = jlong_to_ptr<void>(retainPtr);
  g_sym.waterui_drop_retain(retain);
}

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

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readBindingColor(JNIEnv *, jclass,
                                                         jlong bindingPtr) {
  return ptr_to_jlong(g_sym.waterui_read_binding_color(
      jlong_to_ptr<WuiBinding_Color>(bindingPtr)));
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

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_setBindingColor(
    JNIEnv *, jclass, jlong bindingPtr, jlong colorPtr) {
  g_sym.waterui_set_binding_color(jlong_to_ptr<WuiBinding_Color>(bindingPtr),
                                  jlong_to_ptr<WuiColor>(colorPtr));
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

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropBindingColor(
    JNIEnv *, jclass, jlong bindingPtr) {
  g_sym.waterui_drop_binding_color(jlong_to_ptr<WuiBinding_Color>(bindingPtr));
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

JNIEXPORT jfloat JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedF32(JNIEnv *, jclass,
                                                        jlong computedPtr) {
  return g_sym.waterui_read_computed_f32(
      jlong_to_ptr<WuiComputed_f32>(computedPtr));
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

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropComputedF32(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_f32(jlong_to_ptr<WuiComputed_f32>(computedPtr));
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

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropIndexAction(
    JNIEnv *, jclass, jlong actionPtr) {
  g_sym.waterui_drop_index_action(jlong_to_ptr<WuiIndexAction>(actionPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_callIndexAction(
    JNIEnv *, jclass, jlong actionPtr, jlong envPtr, jlong index) {
  g_sym.waterui_call_index_action(jlong_to_ptr<WuiIndexAction>(actionPtr),
                                  jlong_to_ptr<WuiEnv>(envPtr),
                                  static_cast<uintptr_t>(index));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropMoveAction(
    JNIEnv *, jclass, jlong actionPtr) {
  g_sym.waterui_drop_move_action(jlong_to_ptr<WuiMoveAction>(actionPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_callMoveAction(
    JNIEnv *, jclass, jlong actionPtr, jlong envPtr, jlong fromIndex,
    jlong toIndex) {
  g_sym.waterui_call_move_action(
      jlong_to_ptr<WuiMoveAction>(actionPtr), jlong_to_ptr<WuiEnv>(envPtr),
      static_cast<uintptr_t>(fromIndex), static_cast<uintptr_t>(toIndex));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropDynamic(
    JNIEnv *, jclass, jlong dynamicPtr) {
  g_sym.waterui_drop_dynamic(jlong_to_ptr<WuiDynamic>(dynamicPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropColor(
    JNIEnv *, jclass, jlong colorPtr) {
  g_sym.waterui_drop_color(jlong_to_ptr<WuiColor>(colorPtr));
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_colorFromSrgba(
    JNIEnv *, jclass, jfloat red, jfloat green, jfloat blue, jfloat alpha) {
  return ptr_to_jlong(g_sym.waterui_color_from_srgba(red, green, blue, alpha));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_colorFromLinearRgbaHeadroom(
    JNIEnv *, jclass, jfloat red, jfloat green, jfloat blue, jfloat alpha,
    jfloat headroom) {
  return ptr_to_jlong(g_sym.waterui_color_from_linear_rgba_headroom(
      red, green, blue, alpha, headroom));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedColor(JNIEnv *, jclass,
                                                          jlong computedPtr) {
  return ptr_to_jlong(g_sym.waterui_read_computed_color(
      jlong_to_ptr<WuiComputed_Color>(computedPtr)));
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

// Legacy getAnimation - returns tag as int (deprecated)
JNIEXPORT jint JNICALL Java_dev_waterui_android_ffi_WatcherJni_getAnimation(
    JNIEnv *, jclass, jlong metadataPtr) {
  WuiAnimation anim = g_sym.waterui_get_animation(
      jlong_to_ptr<WuiWatcherMetadata>(metadataPtr));
  return static_cast<jint>(anim.tag);
}

// Get animation tag from metadata
JNIEXPORT jint JNICALL Java_dev_waterui_android_ffi_WatcherJni_getAnimationTag(
    JNIEnv *, jclass, jlong metadataPtr) {
  WuiAnimation anim = g_sym.waterui_get_animation(
      jlong_to_ptr<WuiWatcherMetadata>(metadataPtr));
  return static_cast<jint>(anim.tag);
}

// Get animation duration in milliseconds (for timed animations)
JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_getAnimationDurationMs(
    JNIEnv *, jclass, jlong metadataPtr) {
  WuiAnimation anim = g_sym.waterui_get_animation(
      jlong_to_ptr<WuiWatcherMetadata>(metadataPtr));
  switch (anim.tag) {
  case WuiAnimation_Linear:
    return static_cast<jlong>(anim.linear.duration_ms);
  case WuiAnimation_EaseIn:
    return static_cast<jlong>(anim.ease_in.duration_ms);
  case WuiAnimation_EaseOut:
    return static_cast<jlong>(anim.ease_out.duration_ms);
  case WuiAnimation_EaseInOut:
    return static_cast<jlong>(anim.ease_in_out.duration_ms);
  default:
    return 0;
  }
}

// Get spring stiffness (for spring animations)
JNIEXPORT jfloat JNICALL
Java_dev_waterui_android_ffi_WatcherJni_getAnimationStiffness(
    JNIEnv *, jclass, jlong metadataPtr) {
  WuiAnimation anim = g_sym.waterui_get_animation(
      jlong_to_ptr<WuiWatcherMetadata>(metadataPtr));
  if (anim.tag == WuiAnimation_Spring) {
    return anim.spring.stiffness;
  }
  return 0.0f;
}

// Get spring damping (for spring animations)
JNIEXPORT jfloat JNICALL
Java_dev_waterui_android_ffi_WatcherJni_getAnimationDamping(JNIEnv *, jclass,
                                                            jlong metadataPtr) {
  WuiAnimation anim = g_sym.waterui_get_animation(
      jlong_to_ptr<WuiWatcherMetadata>(metadataPtr));
  if (anim.tag == WuiAnimation_Spring) {
    return anim.spring.damping;
  }
  return 0.0f;
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

// ========== Photo Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_photoId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_photo_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsPhoto(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto photo = g_sym.waterui_force_as_photo(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/PhotoStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
  jstring sourceStr = wui_str_to_jstring(env, photo.source);
  jobject obj = env->NewObject(cls, ctor, sourceStr);
  env->DeleteLocalRef(cls);
  env->DeleteLocalRef(sourceStr);
  return obj;
}

// ========== Video (Raw) Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_videoId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_video_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsVideo(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto video = g_sym.waterui_force_as_video(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/VideoStruct2");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJIZZ)V");
  jobject obj = env->NewObject(
      cls, ctor, ptr_to_jlong(video.source), ptr_to_jlong(video.volume),
      static_cast<jint>(video.aspect_ratio), static_cast<jboolean>(video.loops),
      static_cast<jboolean>(false)); // show_controls = false for raw video
  env->DeleteLocalRef(cls);
  return obj;
}

// ========== VideoPlayer Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_videoPlayerId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_video_player_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsVideoPlayer(JNIEnv *env, jclass,
                                                           jlong viewPtr) {
  auto vp =
      g_sym.waterui_force_as_video_player(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/VideoPlayerStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJIZ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(vp.source),
                               ptr_to_jlong(vp.volume),
                               static_cast<jint>(vp.aspect_ratio),
                               static_cast<jboolean>(vp.show_controls));
  env->DeleteLocalRef(cls);
  return obj;
}

// ========== WebView Functions ==========

static void drop_wui_str(WuiStr value) { value._0.vtable.drop(value._0.data); }

static void webview_go_back(void *data) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    return;
  }
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperGoBack);
}

static void webview_go_forward(void *data) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    return;
  }
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperGoForward);
}

static void webview_go_to(void *data, WuiStr url) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    drop_wui_str(url);
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    drop_wui_str(url);
    return;
  }
  std::string url_str = wui_str_to_std_string(url);
  jstring jurl = scoped.env->NewStringUTF(url_str.c_str());
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperGoTo, jurl);
  scoped.env->DeleteLocalRef(jurl);
}

static void webview_stop(void *data) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    return;
  }
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperStop);
}

static void webview_refresh(void *data) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    return;
  }
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperRefresh);
}

static bool webview_can_go_back(const void *data) {
  auto *ctx = static_cast<const WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    return false;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    return false;
  }
  return scoped.env->CallBooleanMethod(ctx->wrapper,
                                       gWebViewWrapperCanGoBack) == JNI_TRUE;
}

static bool webview_can_go_forward(const void *data) {
  auto *ctx = static_cast<const WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    return false;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    return false;
  }
  return scoped.env->CallBooleanMethod(ctx->wrapper,
                                       gWebViewWrapperCanGoForward) == JNI_TRUE;
}

static void webview_set_user_agent(void *data, WuiStr user_agent) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    drop_wui_str(user_agent);
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    drop_wui_str(user_agent);
    return;
  }
  std::string ua = wui_str_to_std_string(user_agent);
  jstring jua = scoped.env->NewStringUTF(ua.c_str());
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperSetUserAgent, jua);
  scoped.env->DeleteLocalRef(jua);
}

static void webview_set_redirects_enabled(void *data, bool enabled) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    return;
  }
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperSetRedirectsEnabled,
                             static_cast<jboolean>(enabled));
}

static void webview_inject_script(void *data, WuiStr script,
                                  WuiScriptInjectionTime time) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    drop_wui_str(script);
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    drop_wui_str(script);
    return;
  }
  std::string script_str = wui_str_to_std_string(script);
  jstring jscript = scoped.env->NewStringUTF(script_str.c_str());
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperInjectScript, jscript,
                             static_cast<jint>(time));
  scoped.env->DeleteLocalRef(jscript);
}

static void webview_watch(void *data, WuiFn_WuiWebViewEvent callback) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    callback.drop(callback.data);
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env) ||
      !init_webview_callback_jni(scoped.env)) {
    callback.drop(callback.data);
    return;
  }

  if (ctx->has_watcher) {
    ctx->watcher.drop(ctx->watcher.data);
    scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperSetEventCallback,
                               nullptr);
  }

  ctx->watcher = callback;
  ctx->has_watcher = true;

  jobject cb_obj = scoped.env->NewObject(gNativeWebViewEventCallbackClass,
                                         gNativeWebViewEventCallbackCtor,
                                         reinterpret_cast<jlong>(ctx));
  if (cb_obj == nullptr) {
    ctx->watcher.drop(ctx->watcher.data);
    ctx->has_watcher = false;
    return;
  }
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperSetEventCallback,
                             cb_obj);
  scoped.env->DeleteLocalRef(cb_obj);
}

static void webview_run_javascript(void *data, WuiStr script,
                                   WuiJsCallback callback) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    drop_wui_str(script);
    return;
  }
  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    drop_wui_str(script);
    return;
  }
  std::string script_str = wui_str_to_std_string(script);
  jstring jscript = scoped.env->NewStringUTF(script_str.c_str());
  scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperRunJavaScript,
                             jscript, reinterpret_cast<jlong>(callback.data),
                             reinterpret_cast<jlong>(callback.call));
  scoped.env->DeleteLocalRef(jscript);
}

static void webview_drop(void *data) {
  auto *ctx = static_cast<WebViewHandleContext *>(data);
  if (ctx == nullptr) {
    return;
  }

  ScopedEnv scoped;
  if (scoped.env != nullptr && init_webview_wrapper_jni(scoped.env)) {
    if (ctx->wrapper != nullptr) {
      scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperSetEventCallback,
                                 nullptr);
      scoped.env->CallVoidMethod(ctx->wrapper, gWebViewWrapperRelease);
      scoped.env->DeleteGlobalRef(ctx->wrapper);
    }
  }

  if (ctx->has_watcher) {
    ctx->watcher.drop(ctx->watcher.data);
  }

  delete ctx;
}

static WuiWebViewHandle create_webview_handle() {
  WuiWebViewHandle handle{};
  handle.data = nullptr;
  handle.go_back = webview_go_back;
  handle.go_forward = webview_go_forward;
  handle.go_to = webview_go_to;
  handle.stop = webview_stop;
  handle.refresh = webview_refresh;
  handle.can_go_back = webview_can_go_back;
  handle.can_go_forward = webview_can_go_forward;
  handle.set_user_agent = webview_set_user_agent;
  handle.set_redirects_enabled = webview_set_redirects_enabled;
  handle.inject_script = webview_inject_script;
  handle.watch = webview_watch;
  handle.run_javascript = webview_run_javascript;
  handle.drop = webview_drop;

  ScopedEnv scoped;
  if (scoped.env == nullptr || !init_webview_wrapper_jni(scoped.env)) {
    return handle;
  }

  jobject wrapper = create_webview_wrapper(scoped.env);
  if (wrapper == nullptr) {
    return handle;
  }

  auto *ctx =
      new WebViewHandleContext{scoped.env->NewGlobalRef(wrapper), {}, false};
  scoped.env->DeleteLocalRef(wrapper);
  handle.data = ctx;
  return handle;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_webviewId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_webview_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsWebView(
    JNIEnv *, jclass, jlong viewPtr) {
  auto webview =
      g_sym.waterui_force_as_webview(jlong_to_ptr<WuiAnyView>(viewPtr));
  return ptr_to_jlong(webview);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_webviewNativeHandle(JNIEnv *, jclass,
                                                            jlong webviewPtr) {
  return ptr_to_jlong(g_sym.waterui_webview_native_handle(
      jlong_to_ptr<WuiWebView>(webviewPtr)));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_webviewNativeView(JNIEnv *env, jclass,
                                                          jlong handlePtr) {
  auto *ctx = jlong_to_ptr<WebViewHandleContext>(handlePtr);
  if (ctx == nullptr || ctx->wrapper == nullptr) {
    return nullptr;
  }
  if (!init_webview_wrapper_jni(env)) {
    return nullptr;
  }
  return env->CallObjectMethod(ctx->wrapper, gWebViewWrapperGetView);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropWebView(
    JNIEnv *, jclass, jlong webviewPtr) {
  g_sym.waterui_drop_web_view(jlong_to_ptr<WuiWebView>(webviewPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_components_NativeWebViewEventCallback_nativeOnEvent(
    JNIEnv *env, jobject, jlong nativePtr, jint eventType, jstring url,
    jstring url2, jstring message, jfloat progress, jboolean canGoBack,
    jboolean canGoForward) {
  auto *ctx = jlong_to_ptr<WebViewHandleContext>(nativePtr);
  if (ctx == nullptr || !ctx->has_watcher) {
    return;
  }

  WuiWebViewEvent event{};
  event.event_type = static_cast<WuiWebViewEventType>(eventType);
  event.url = str_from_jstring(env, url);
  event.url2 = str_from_jstring(env, url2);
  event.message = str_from_jstring(env, message);
  event.progress = progress;
  event.can_go_back = (canGoBack == JNI_TRUE);
  event.can_go_forward = (canGoForward == JNI_TRUE);

  ctx->watcher.call(ctx->watcher.data, event);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_components_WebViewWrapper_nativeCompleteJsResult(
    JNIEnv *env, jobject, jlong callbackData, jlong callbackFn,
    jboolean success, jstring result) {
  auto call_fn = reinterpret_cast<void (*)(void *, bool, WuiStr)>(callbackFn);
  if (call_fn == nullptr) {
    return;
  }
  WuiStr result_str = str_from_jstring(env, result);
  call_fn(reinterpret_cast<void *>(callbackData), success == JNI_TRUE,
          result_str);
}

// ========== MediaPicker Functions ==========

// MediaPicker removed - now uses Button wrapper in Rust (no longer a native
// component)

// ========== Float Binding Functions ==========

JNIEXPORT jfloat JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readBindingFloat(JNIEnv *, jclass,
                                                         jlong bindingPtr) {
  return g_sym.waterui_read_binding_f32(
      jlong_to_ptr<WuiBinding_f32>(bindingPtr));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_setBindingFloat(
    JNIEnv *, jclass, jlong bindingPtr, jfloat value) {
  g_sym.waterui_set_binding_f32(jlong_to_ptr<WuiBinding_f32>(bindingPtr),
                                value);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropBindingFloat(
    JNIEnv *, jclass, jlong bindingPtr) {
  g_sym.waterui_drop_binding_f32(jlong_to_ptr<WuiBinding_f32>(bindingPtr));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchBindingFloat(JNIEnv *env, jclass,
                                                          jlong bindingPtr,
                                                          jobject watcher) {
  auto *binding = jlong_to_ptr<WuiBinding_f32>(bindingPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_f32, float>(
      fields, g_sym.waterui_new_watcher_f32);
  return ptr_to_jlong(g_sym.waterui_watch_binding_f32(binding, w));
}

// ========== Date Binding Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readBindingDate(JNIEnv *env, jclass,
                                                        jlong bindingPtr) {
  auto date = g_sym.waterui_read_binding_date(
      jlong_to_ptr<WuiBinding_Date>(bindingPtr));
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/DateStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(III)V");
  jobject obj = env->NewObject(cls, ctor, static_cast<jint>(date.year),
                               static_cast<jint>(date.month),
                               static_cast<jint>(date.day));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_setBindingDate(
    JNIEnv *, jclass, jlong bindingPtr, jint year, jint month, jint day) {
  WuiDate date;
  date.year = year;
  date.month = static_cast<uint8_t>(month);
  date.day = static_cast<uint8_t>(day);
  g_sym.waterui_set_binding_date(jlong_to_ptr<WuiBinding_Date>(bindingPtr),
                                 date);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropBindingDate(
    JNIEnv *, jclass, jlong bindingPtr) {
  g_sym.waterui_drop_binding_date(jlong_to_ptr<WuiBinding_Date>(bindingPtr));
}

static void date_watcher_call(const void *data, WuiDate value,
                              WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_sym.waterui_drop_watcher_metadata(metadata);
    return;
  }
  auto *state = static_cast<WatcherCallbackState const *>(data);
  jclass dateStructCls =
      find_app_class(scoped.env, "dev/waterui/android/runtime/DateStruct");
  jmethodID dateStructCtor =
      scoped.env->GetMethodID(dateStructCls, "<init>", "(III)V");
  jobject dateObj = scoped.env->NewObject(
      dateStructCls, dateStructCtor, static_cast<jint>(value.year),
      static_cast<jint>(value.month), static_cast<jint>(value.day));
  invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), dateObj,
                 metadata);
  scoped.env->DeleteLocalRef(dateStructCls);
  scoped.env->DeleteLocalRef(dateObj);
}

static void date_watcher_drop(void *data) {
  ScopedEnv scoped;
  drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchBindingDate(JNIEnv *env, jclass,
                                                         jlong bindingPtr,
                                                         jobject watcher) {
  auto *binding = jlong_to_ptr<WuiBinding_Date>(bindingPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_Date, WuiDate>(
      fields, g_sym.waterui_new_watcher_date);
  return ptr_to_jlong(g_sym.waterui_watch_binding_date(binding, w));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_createDateWatcher(JNIEnv *env, jclass,
                                                          jobject callback) {
  auto *state = create_watcher_state(env, callback);
  return new_watcher_struct(
      env, ptr_to_jlong(state),
      ptr_to_jlong(reinterpret_cast<void *>(date_watcher_call)),
      ptr_to_jlong(reinterpret_cast<void *>(date_watcher_drop)));
}

// ========== String Computed Functions ==========

JNIEXPORT jstring JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedStr(JNIEnv *env, jclass,
                                                        jlong computedPtr) {
  auto str = g_sym.waterui_read_computed_str(
      jlong_to_ptr<WuiComputed_Str>(computedPtr));
  return wui_str_to_jstring(env, str);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedStr(JNIEnv *env, jclass,
                                                         jlong computedPtr,
                                                         jobject watcher) {
  auto *computed = jlong_to_ptr<WuiComputed_Str>(computedPtr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher);
  auto *w = create_watcher<WuiWatcher_Str, WuiStr>(
      fields, g_sym.waterui_new_watcher_str);
  return ptr_to_jlong(g_sym.waterui_watch_computed_str(computed, w));
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropComputedStr(
    JNIEnv *, jclass, jlong computedPtr) {
  g_sym.waterui_drop_computed_str(jlong_to_ptr<WuiComputed_Str>(computedPtr));
}

// ========== Video Computed Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_readComputedVideo(JNIEnv *env, jclass,
                                                          jlong computedPtr) {
  auto video = g_sym.waterui_read_computed_video(
      jlong_to_ptr<WuiComputed_Video>(computedPtr));
  // Convert WuiVideo to VideoStruct
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/VideoStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
  // Convert WuiStr (url) to Java String using proper helper
  jstring urlStr = wui_str_to_jstring(env, video.url);
  jobject obj = env->NewObject(cls, ctor, urlStr);
  env->DeleteLocalRef(cls);
  env->DeleteLocalRef(urlStr);
  return obj;
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_watchComputedVideo(JNIEnv *, jclass,
                                                           jlong computedPtr,
                                                           jobject watcher) {
  // Not implemented yet - would require Video watcher callback infrastructure
  return 0;
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropComputedVideo(JNIEnv *, jclass,
                                                          jlong computedPtr) {
  g_sym.waterui_drop_computed_video(
      jlong_to_ptr<WuiComputed_Video>(computedPtr));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_createVideoWatcher(JNIEnv *env, jclass,
                                                           jobject callback) {
  // Not implemented yet - would require Video watcher callback infrastructure
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/WatcherStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJ)V");
  jobject obj = env->NewObject(cls, ctor, 0L, 0L, 0L);
  env->DeleteLocalRef(cls);
  return obj;
}

// ========== Navigation Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_navigationStackId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_navigation_stack_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_navigationViewId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_navigation_view_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_tabsId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_tabs_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsNavigationStack(JNIEnv *env,
                                                               jclass,
                                                               jlong viewPtr) {
  WuiNavigationStack navStack = g_sym.waterui_force_as_navigation_stack(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/NavigationStackStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(navStack.root));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsNavigationView(JNIEnv *env,
                                                              jclass,
                                                              jlong viewPtr) {
  WuiNavigationView navView =
      g_sym.waterui_force_as_navigation_view(jlong_to_ptr<WuiAnyView>(viewPtr));

  // Create BarStruct
  jclass barCls = find_app_class(env, "dev/waterui/android/runtime/BarStruct");
  jmethodID barCtor = env->GetMethodID(barCls, "<init>", "(JJJ)V");
  jobject barObj = env->NewObject(
      barCls, barCtor, ptr_to_jlong(navView.bar.title.content),
      ptr_to_jlong(navView.bar.color), ptr_to_jlong(navView.bar.hidden));
  env->DeleteLocalRef(barCls);

  // Create NavigationViewStruct
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/NavigationViewStruct");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(Ldev/waterui/android/runtime/BarStruct;J)V");
  jobject obj =
      env->NewObject(cls, ctor, barObj, ptr_to_jlong(navView.content));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsTabs(
    JNIEnv *env, jclass, jlong viewPtr) {
  WuiTabs tabsData =
      g_sym.waterui_force_as_tabs(jlong_to_ptr<WuiAnyView>(viewPtr));

  // Get tab array slice
  WuiArraySlice_WuiTab slice = tabsData.tabs.vtable.slice(tabsData.tabs.data);

  // Create TabStruct array
  jclass tabCls = find_app_class(env, "dev/waterui/android/runtime/TabStruct");
  jmethodID tabCtor = env->GetMethodID(tabCls, "<init>", "(JJJ)V");
  jobjectArray tabArray =
      env->NewObjectArray(static_cast<jsize>(slice.len), tabCls, nullptr);

  for (size_t i = 0; i < slice.len; i++) {
    WuiTab *tab = slice.head + i;
    jobject tabObj =
        env->NewObject(tabCls, tabCtor, static_cast<jlong>(tab->id),
                       ptr_to_jlong(tab->label), ptr_to_jlong(tab->content));
    env->SetObjectArrayElement(tabArray, static_cast<jsize>(i), tabObj);
    env->DeleteLocalRef(tabObj);
  }
  env->DeleteLocalRef(tabCls);

  // Create TabsStruct
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/TabsStruct");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(J[Ldev/waterui/android/runtime/TabStruct;I)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(tabsData.selection),
                               tabArray, static_cast<jint>(tabsData.position));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_tabContent(
    JNIEnv *env, jclass, jlong contentPtr) {
  WuiNavigationView navView =
      g_sym.waterui_tab_content(jlong_to_ptr<WuiTabContent>(contentPtr));

  // Create BarStruct
  jclass barCls = find_app_class(env, "dev/waterui/android/runtime/BarStruct");
  jmethodID barCtor = env->GetMethodID(barCls, "<init>", "(JJJ)V");
  jobject barObj = env->NewObject(
      barCls, barCtor, ptr_to_jlong(navView.bar.title.content),
      ptr_to_jlong(navView.bar.color), ptr_to_jlong(navView.bar.hidden));
  env->DeleteLocalRef(barCls);

  // Create NavigationViewStruct
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/NavigationViewStruct");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>", "(Ldev/waterui/android/runtime/BarStruct;J)V");
  jobject obj =
      env->NewObject(cls, ctor, barObj, ptr_to_jlong(navView.content));
  env->DeleteLocalRef(cls);
  return obj;
}

// ========== Navigation Controller Functions ==========

// Callback context for navigation controller
struct NavigationControllerContext {
  JavaVM *jvm;
  jobject callback; // Global reference to Kotlin callback object
};

// C callback that forwards to Kotlin
static void navigation_push_callback(void *data, WuiNavigationView navView) {
  auto *ctx = static_cast<NavigationControllerContext *>(data);
  JNIEnv *env = nullptr;
  bool attached = false;

  jint result =
      ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  if (result == JNI_EDETACHED) {
    ctx->jvm->AttachCurrentThread(&env, nullptr);
    attached = true;
  }

  if (env != nullptr) {
    // Create BarStruct
    jclass barCls =
        find_app_class(env, "dev/waterui/android/runtime/BarStruct");
    jmethodID barCtor = env->GetMethodID(barCls, "<init>", "(JJJ)V");
    jobject barObj = env->NewObject(
        barCls, barCtor, ptr_to_jlong(navView.bar.title.content),
        ptr_to_jlong(navView.bar.color), ptr_to_jlong(navView.bar.hidden));
    env->DeleteLocalRef(barCls);

    // Create NavigationViewStruct
    jclass navViewCls =
        find_app_class(env, "dev/waterui/android/runtime/NavigationViewStruct");
    jmethodID navViewCtor = env->GetMethodID(
        navViewCls, "<init>", "(Ldev/waterui/android/runtime/BarStruct;J)V");
    jobject navViewObj = env->NewObject(navViewCls, navViewCtor, barObj,
                                        ptr_to_jlong(navView.content));
    env->DeleteLocalRef(navViewCls);

    // Call Kotlin callback
    jclass callbackCls = env->GetObjectClass(ctx->callback);
    jmethodID pushMethod = env->GetMethodID(
        callbackCls, "onPush",
        "(Ldev/waterui/android/runtime/NavigationViewStruct;)V");
    env->CallVoidMethod(ctx->callback, pushMethod, navViewObj);
    env->DeleteLocalRef(callbackCls);
    env->DeleteLocalRef(navViewObj);
    env->DeleteLocalRef(barObj);
  }

  if (attached) {
    ctx->jvm->DetachCurrentThread();
  }
}

static void navigation_pop_callback(void *data) {
  auto *ctx = static_cast<NavigationControllerContext *>(data);
  JNIEnv *env = nullptr;
  bool attached = false;

  jint result =
      ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  if (result == JNI_EDETACHED) {
    ctx->jvm->AttachCurrentThread(&env, nullptr);
    attached = true;
  }

  if (env != nullptr) {
    jclass callbackCls = env->GetObjectClass(ctx->callback);
    jmethodID popMethod = env->GetMethodID(callbackCls, "onPop", "()V");
    env->CallVoidMethod(ctx->callback, popMethod);
    env->DeleteLocalRef(callbackCls);
  }

  if (attached) {
    ctx->jvm->DetachCurrentThread();
  }
}

static void navigation_drop_callback(void *data) {
  auto *ctx = static_cast<NavigationControllerContext *>(data);
  JNIEnv *env = nullptr;
  bool attached = false;

  jint result =
      ctx->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  if (result == JNI_EDETACHED) {
    ctx->jvm->AttachCurrentThread(&env, nullptr);
    attached = true;
  }

  if (env != nullptr) {
    env->DeleteGlobalRef(ctx->callback);
  }

  if (attached) {
    ctx->jvm->DetachCurrentThread();
  }

  delete ctx;
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_ffi_WatcherJni_navigationControllerNew(
    JNIEnv *env, jclass, jobject callback) {
  JavaVM *jvm;
  env->GetJavaVM(&jvm);

  auto *ctx = new NavigationControllerContext{jvm, env->NewGlobalRef(callback)};

  return ptr_to_jlong(g_sym.waterui_navigation_controller_new(
      ctx, navigation_push_callback, navigation_pop_callback,
      navigation_drop_callback));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_envInstallNavigationController(
    JNIEnv *, jclass, jlong envPtr, jlong controllerPtr) {
  g_sym.waterui_env_install_navigation_controller(
      jlong_to_ptr<WuiEnv>(envPtr),
      jlong_to_ptr<WuiNavigationController>(controllerPtr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropNavigationController(JNIEnv *,
                                                                 jclass,
                                                                 jlong ptr) {
  g_sym.waterui_drop_navigation_controller(
      jlong_to_ptr<WuiNavigationController>(ptr));
}

// ========== GpuSurface Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_gpuSurfaceId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_gpu_surface_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsGpuSurface(JNIEnv *env, jclass,
                                                          jlong viewPtr) {
  WuiGpuSurface gpuSurface =
      g_sym.waterui_force_as_gpu_surface(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/GpuSurfaceStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(gpuSurface.renderer));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jlong JNICALL Java_dev_waterui_android_ffi_WatcherJni_gpuSurfaceInit(
    JNIEnv *env, jclass, jlong rendererPtr, jobject javaSurface, jint width,
    jint height) {
  if (javaSurface == nullptr || rendererPtr == 0) {
    return 0;
  }

  // Extract ANativeWindow from Java Surface
  ANativeWindow *nativeWindow = ANativeWindow_fromSurface(env, javaSurface);
  if (nativeWindow == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to get ANativeWindow from Surface");
    return 0;
  }

  // Create a temporary WuiGpuSurface struct to pass to init
  WuiGpuSurface surface{};
  surface.renderer = jlong_to_ptr<void>(rendererPtr);
  WuiGpuSurfaceState *state = g_sym.waterui_gpu_surface_init(
      &surface, nativeWindow, static_cast<uint32_t>(width),
      static_cast<uint32_t>(height));

  // Note: We don't release the ANativeWindow here because wgpu needs it
  // for the lifetime of the surface. It will be released when the surface
  // is destroyed.

  return ptr_to_jlong(state);
}

JNIEXPORT jboolean JNICALL
Java_dev_waterui_android_ffi_WatcherJni_gpuSurfaceRender(JNIEnv *, jclass,
                                                         jlong statePtr,
                                                         jint width,
                                                         jint height) {
  bool result = g_sym.waterui_gpu_surface_render(
      jlong_to_ptr<WuiGpuSurfaceState>(statePtr), static_cast<uint32_t>(width),
      static_cast<uint32_t>(height));
  return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_gpuSurfaceDrop(
    JNIEnv *, jclass, jlong statePtr) {
  g_sym.waterui_gpu_surface_drop(jlong_to_ptr<WuiGpuSurfaceState>(statePtr));
}

// ========== List Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_listId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_list_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_listItemId(JNIEnv *env, jclass) {
  auto id = g_sym.waterui_list_item_id();
  return new_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL Java_dev_waterui_android_ffi_WatcherJni_forceAsList(
    JNIEnv *env, jclass, jlong viewPtr) {
  WuiList list = g_sym.waterui_force_as_list(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(env, "dev/waterui/android/runtime/ListStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJJ)V");
  jobject obj = env->NewObject(
      cls, ctor, ptr_to_jlong(list.contents), ptr_to_jlong(list.editing),
      ptr_to_jlong(list.on_delete), ptr_to_jlong(list.on_move));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsListItem(JNIEnv *env, jclass,
                                                        jlong viewPtr) {
  WuiListItem item =
      g_sym.waterui_force_as_list_item(jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/ListItemStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(item.content),
                               ptr_to_jlong(item.deletable));
  env->DeleteLocalRef(cls);
  return obj;
}

// ============================================================================
// Media Loading
// ============================================================================

// ============================================================================
// Media Loading Types
// ============================================================================
// These types must match the Rust definitions in waterui_media::media_picker

// MediaLoadResult and MediaLoadCallback are defined in waterui.h

// Cache for MediaLoader class and method
static jclass gMediaLoaderClass = nullptr;
static jmethodID gMediaLoaderLoadMethod = nullptr;

/**
 * Initialize MediaLoader JNI references.
 * Called lazily on first use.
 */
static bool initMediaLoaderJni(JNIEnv *env) {
  if (gMediaLoaderClass != nullptr && gMediaLoaderLoadMethod != nullptr) {
    return true;
  }

  jclass localClass =
      find_app_class(env, "dev/waterui/android/runtime/MediaLoader");
  if (localClass == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to find MediaLoader class");
    return false;
  }

  gMediaLoaderClass = (jclass)env->NewGlobalRef(localClass);
  env->DeleteLocalRef(localClass);

  gMediaLoaderLoadMethod =
      env->GetStaticMethodID(gMediaLoaderClass, "loadMedia", "(IJJ)V");
  if (gMediaLoaderLoadMethod == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to find MediaLoader.loadMedia method");
    return false;
  }

  return true;
}

/**
 * C function called by Rust when Selected::load() is invoked.
 * This is the implementation of the extern "C" fn waterui_load_media.
 */
// MediaPickerManager JNI globals
static jclass gMediaPickerManagerClass = nullptr;
static jmethodID gMediaPickerPresentMethod = nullptr;

static bool initMediaPickerManagerJni(JNIEnv *env) {
  if (gMediaPickerManagerClass != nullptr) {
    return true; // Already initialized
  }

  jclass cls =
      find_app_class(env, "dev/waterui/android/runtime/MediaPickerManager");
  if (cls == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                        "Failed to find MediaPickerManager class");
    return false;
  }
  gMediaPickerManagerClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
  env->DeleteLocalRef(cls);

  gMediaPickerPresentMethod = env->GetStaticMethodID(gMediaPickerManagerClass,
                                                     "presentPicker", "(IJJ)V");
  if (gMediaPickerPresentMethod == nullptr) {
    __android_log_print(
        ANDROID_LOG_ERROR, LOG_TAG,
        "Failed to find MediaPickerManager.presentPicker method");
    return false;
  }

  return true;
}

// Present media picker - calls into Kotlin MediaPickerManager
void waterui_present_media_picker(WuiMediaFilterType filter,
                                  MediaPickerPresentCallback callback) {
  ScopedEnv scoped;
  JNIEnv *env = scoped.env;
  if (env == nullptr) {
    __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                        "waterui_present_media_picker: failed to get JNIEnv");
    std::abort();
  }

  if (!initMediaPickerManagerJni(env)) {
    __android_log_print(
        ANDROID_LOG_FATAL, LOG_TAG,
        "waterui_present_media_picker: failed to init MediaPickerManager JNI");
    std::abort();
  }

  // Call MediaPickerManager.presentPicker(filter, callbackData, callFnPtr)
  env->CallStaticVoidMethod(gMediaPickerManagerClass, gMediaPickerPresentMethod,
                            static_cast<jint>(filter),
                            reinterpret_cast<jlong>(callback.data),
                            reinterpret_cast<jlong>(callback.call));
}

/**
 * JNI function called by MediaPickerManager.kt when user selects media.
 * Invokes the Rust callback with the selected media ID.
 */
JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_MediaPickerManager_nativeCompletePresentCallback(
    JNIEnv *, jclass, jlong callbackData, jlong callbackFn, jint selectedId) {
  // Get the callback function pointer - SelectedId is uint32_t
  auto callFn = reinterpret_cast<void (*)(void *, SelectedId)>(callbackFn);
  void *data = reinterpret_cast<void *>(callbackData);

  // Call the Rust callback with the selected ID
  SelectedId selected = static_cast<SelectedId>(selectedId);
  callFn(data, selected);
}

void waterui_load_media(uint32_t id, MediaLoadCallback callback) {
  ScopedEnv scoped;
  JNIEnv *env = scoped.env;
  if (env == nullptr) {
    __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                        "waterui_load_media: failed to get JNIEnv");
    std::abort();
  }

  if (!initMediaLoaderJni(env)) {
    __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                        "waterui_load_media: failed to init MediaLoader JNI");
    std::abort();
  }

  // Call MediaLoader.loadMedia(id, callbackData, callFnPtr)
  // We pass the callback data and function pointer separately
  env->CallStaticVoidMethod(gMediaLoaderClass, gMediaLoaderLoadMethod,
                            static_cast<jint>(id),
                            reinterpret_cast<jlong>(callback.data),
                            reinterpret_cast<jlong>(callback.call));
}

/**
 * JNI function called by MediaLoader.kt when media loading completes.
 * Invokes the Rust callback with the file URL(s).
 *
 * For Motion Photos / Live Photos, both imageUrl and videoUrl are provided.
 * For regular images/videos, videoUrl is null.
 */
JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_MediaLoader_nativeCompleteMediaLoad(
    JNIEnv *env, jclass, jlong callbackData, jlong callFnPtr, jstring imageUrl,
    jstring videoUrl, jbyte mediaType) {
  // Get the callback function pointer
  auto callFn = reinterpret_cast<void (*)(void *, MediaLoadResult)>(callFnPtr);
  void *data = reinterpret_cast<void *>(callbackData);

  // Convert Java strings to C strings
  const char *imageCStr = env->GetStringUTFChars(imageUrl, nullptr);
  if (imageCStr == nullptr) {
    __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                        "nativeCompleteMediaLoad: imageUrl is null");
    std::abort();
  }
  size_t imageLen = strlen(imageCStr);
  if (imageLen == 0) {
    __android_log_print(ANDROID_LOG_FATAL, LOG_TAG,
                        "nativeCompleteMediaLoad: imageUrl is empty");
    env->ReleaseStringUTFChars(imageUrl, imageCStr);
    std::abort();
  }

  const char *videoCStr = nullptr;
  size_t videoLen = 0;
  if (videoUrl != nullptr) {
    videoCStr = env->GetStringUTFChars(videoUrl, nullptr);
    if (videoCStr != nullptr) {
      videoLen = strlen(videoCStr);
    }
  }

  // For motion photos, video URL is required
  if (mediaType == 2 && (videoCStr == nullptr || videoLen == 0)) {
    __android_log_print(
        ANDROID_LOG_FATAL, LOG_TAG,
        "nativeCompleteMediaLoad: videoUrl is null/empty for Motion Photo");
    env->ReleaseStringUTFChars(imageUrl, imageCStr);
    if (videoCStr != nullptr) {
      env->ReleaseStringUTFChars(videoUrl, videoCStr);
    }
    std::abort();
  }

  // Create result
  MediaLoadResult result{};
  result.url_ptr = reinterpret_cast<const uint8_t *>(imageCStr);
  result.url_len = imageLen;
  result.video_url_ptr =
      videoCStr ? reinterpret_cast<const uint8_t *>(videoCStr) : nullptr;
  result.video_url_len = videoLen;
  result.media_type = static_cast<uint8_t>(mediaType);

  // Call the Rust callback
  callFn(data, result);

  // Release the strings
  env->ReleaseStringUTFChars(imageUrl, imageCStr);
  if (videoCStr != nullptr) {
    env->ReleaseStringUTFChars(videoUrl, videoCStr);
  }
}

/**
 * JNI function to call the onSelection callback when media is selected.
 */
JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_callOnSelection(
    JNIEnv *, jclass, jlong dataPtr, jlong callPtr, jint selectionId) {
  // SelectedId is uint32_t
  auto callFn = reinterpret_cast<void (*)(void *, SelectedId)>(callPtr);
  void *data = reinterpret_cast<void *>(dataPtr);

  SelectedId selected = static_cast<SelectedId>(selectionId);
  callFn(data, selected);
}

// ========== Drag and Drop JNI Functions ==========

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_metadataDraggableId(JNIEnv *env,
                                                            jclass) {
  auto id = g_sym.waterui_metadata_draggable_id();
  return create_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_metadataDropDestinationId(JNIEnv *env,
                                                                  jclass) {
  auto id = g_sym.waterui_metadata_drop_destination_id();
  return create_type_id_struct(env, id);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataDraggable(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_draggable(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/components/MetadataDraggableStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               ptr_to_jlong(metadata.value.inner));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_forceAsMetadataDropDestination(
    JNIEnv *env, jclass, jlong viewPtr) {
  auto metadata = g_sym.waterui_force_as_metadata_drop_destination(
      jlong_to_ptr<WuiAnyView>(viewPtr));
  jclass cls = find_app_class(
      env, "dev/waterui/android/components/MetadataDropDestinationStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
  // The drop destination has on_drop, on_enter, on_exit handlers - we pass the
  // whole struct as opaque pointer
  jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(metadata.content),
                               reinterpret_cast<jlong>(&metadata.value));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_ffi_WatcherJni_draggableGetData(JNIEnv *env, jclass,
                                                         jlong draggablePtr) {
  auto *draggable = jlong_to_ptr<WuiDraggable>(draggablePtr);
  WuiDragData data = g_sym.waterui_draggable_get_data(draggable);

  // Convert to DragDataStruct
  jclass tagCls =
      find_app_class(env, "dev/waterui/android/components/DragDataTag");
  jmethodID valueOfMethod = env->GetStaticMethodID(
      tagCls, "values", "()[Ldev/waterui/android/components/DragDataTag;");
  jobjectArray tags = static_cast<jobjectArray>(
      env->CallStaticObjectMethod(tagCls, valueOfMethod));
  jobject tagObj =
      env->GetObjectArrayElement(tags, static_cast<jint>(data.tag));

  // Convert value string
  jstring value = env->NewStringUTF(data.value);

  jclass cls =
      find_app_class(env, "dev/waterui/android/components/DragDataStruct");
  jmethodID ctor = env->GetMethodID(
      cls, "<init>",
      "(Ldev/waterui/android/components/DragDataTag;Ljava/lang/String;)V");
  jobject obj = env->NewObject(cls, ctor, tagObj, value);

  env->DeleteLocalRef(tagCls);
  env->DeleteLocalRef(tags);
  env->DeleteLocalRef(tagObj);
  env->DeleteLocalRef(value);
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_dropDraggable(
    JNIEnv *, jclass, jlong draggablePtr) {
  auto *draggable = jlong_to_ptr<WuiDraggable>(draggablePtr);
  g_sym.waterui_drop_draggable(draggable);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_dropDropDestination(JNIEnv *, jclass,
                                                            jlong dropDestPtr) {
  auto *dropDest = jlong_to_ptr<WuiDropDestination>(dropDestPtr);
  g_sym.waterui_drop_drop_destination(dropDest);
}

JNIEXPORT void JNICALL Java_dev_waterui_android_ffi_WatcherJni_callDropHandler(
    JNIEnv *env, jclass, jlong dropDestPtr, jlong envPtr, jint dataTag,
    jstring dataValue) {
  auto *dropDest = jlong_to_ptr<WuiDropDestination>(dropDestPtr);
  auto *wuiEnv = jlong_to_ptr<WuiEnv>(envPtr);

  const char *valueCStr = env->GetStringUTFChars(dataValue, nullptr);
  g_sym.waterui_call_drop_handler(
      dropDest, wuiEnv, static_cast<WuiDragDataTag>(dataTag), valueCStr);
  env->ReleaseStringUTFChars(dataValue, valueCStr);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_callDropEnterHandler(JNIEnv *, jclass,
                                                             jlong dropDestPtr,
                                                             jlong envPtr) {
  auto *dropDest = jlong_to_ptr<WuiDropDestination>(dropDestPtr);
  auto *wuiEnv = jlong_to_ptr<WuiEnv>(envPtr);
  g_sym.waterui_call_drop_enter_handler(dropDest, wuiEnv);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_ffi_WatcherJni_callDropExitHandler(JNIEnv *, jclass,
                                                            jlong dropDestPtr,
                                                            jlong envPtr) {
  auto *dropDest = jlong_to_ptr<WuiDropDestination>(dropDestPtr);
  auto *wuiEnv = jlong_to_ptr<WuiEnv>(envPtr);
  g_sym.waterui_call_drop_exit_handler(dropDest, wuiEnv);
}

} // extern "C"
