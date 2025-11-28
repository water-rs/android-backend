#include "waterui.h"
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr char LOG_TAG[] = "WaterUI.JNI";

#define WATERUI_SYMBOL_LIST(X)                                                 \
  X(waterui_anyviews_get_id)                                                   \
  X(waterui_anyviews_get_view)                                                 \
  X(waterui_anyviews_len)                                                      \
  X(waterui_button_id)                                                         \
  X(waterui_call_action)                                                       \
  X(waterui_clone_env)                                                         \
  X(waterui_color_id)                                                          \
  X(waterui_drop_action)                                                       \
  X(waterui_drop_anyview)                                                      \
  X(waterui_drop_anyviews)                                                     \
  X(waterui_drop_binding_bool)                                                 \
  X(waterui_drop_binding_f64)                                                  \
  X(waterui_drop_binding_i32)                                                  \
  X(waterui_drop_binding_str)                                                  \
  X(waterui_drop_box_watcher_guard)                                            \
  X(waterui_drop_color)                                                        \
  X(waterui_drop_computed_f64)                                                 \
  X(waterui_drop_computed_i32)                                                 \
  X(waterui_drop_computed_resolved_font)                                       \
  X(waterui_drop_computed_resolved_color)                                      \
  X(waterui_drop_computed_styled_str)                                          \
  X(waterui_drop_computed_picker_items)                                        \
  X(waterui_drop_dynamic)                                                      \
  X(waterui_drop_env)                                                          \
  X(waterui_drop_font)                                                         \
  X(waterui_drop_layout)                                                       \
  X(waterui_drop_renderer_view)                                                \
  X(waterui_drop_watcher_metadata)                                             \
  X(waterui_dynamic_connect)                                                   \
  X(waterui_dynamic_id)                                                        \
  X(waterui_empty_id)                                                          \
  X(waterui_fixed_container_id)                                                \
  X(waterui_force_as_button)                                                   \
  X(waterui_force_as_color)                                                    \
  X(waterui_force_as_fixed_container)                                          \
  X(waterui_force_as_dynamic)                                                  \
  X(waterui_force_as_layout_container)                                         \
  X(waterui_force_as_plain)                                                    \
  X(waterui_force_as_progress)                                                 \
  X(waterui_force_as_renderer_view)                                            \
  X(waterui_force_as_scroll_view)                                              \
  X(waterui_force_as_slider)                                                   \
  X(waterui_force_as_picker)                                                   \
  X(waterui_force_as_stepper)                                                  \
  X(waterui_force_as_text)                                                     \
  X(waterui_force_as_text_field)                                               \
  X(waterui_force_as_toggle)                                                   \
  X(waterui_get_animation)                                                     \
  X(waterui_init)                                                              \
  X(waterui_layout_container_id)                                               \
  X(waterui_layout_place)                                                      \
  X(waterui_layout_propose)                                                    \
  X(waterui_layout_size)                                                       \
  X(waterui_main)                                                              \
  X(waterui_plain_id)                                                          \
  X(waterui_progress_id)                                                       \
  X(waterui_read_binding_bool)                                                 \
  X(waterui_read_binding_f64)                                                  \
  X(waterui_read_binding_i32)                                                  \
  X(waterui_read_binding_str)                                                  \
  X(waterui_read_computed_f64)                                                 \
  X(waterui_read_computed_i32)                                                 \
  X(waterui_read_computed_resolved_font)                                       \
  X(waterui_read_computed_resolved_color)                                      \
  X(waterui_read_computed_styled_str)                                          \
  X(waterui_read_computed_picker_items)                                        \
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
  X(waterui_renderer_view_height)                                              \
  X(waterui_renderer_view_id)                                                  \
  X(waterui_renderer_view_preferred_format)                                    \
  X(waterui_renderer_view_render_cpu)                                          \
  X(waterui_renderer_view_width)                                               \
  X(waterui_resolve_color)                                                     \
  X(waterui_resolve_font)                                                      \
  X(waterui_env_install_theme)                                                 \
  X(waterui_theme_install_color)                                               \
  X(waterui_theme_install_font)                                                \
  X(waterui_theme_install_color_scheme)                                        \
  X(waterui_theme_color)                                                       \
  X(waterui_theme_font)                                                        \
  X(waterui_theme_color_scheme)                                                \
  X(waterui_scroll_view_id)                                                    \
  X(waterui_set_binding_bool)                                                  \
  X(waterui_set_binding_f64)                                                   \
  X(waterui_set_binding_i32)                                                   \
  X(waterui_set_binding_str)                                                   \
  X(waterui_slider_id)                                                         \
  X(waterui_picker_id)                                                         \
  X(waterui_spacer_id)                                                         \
  X(waterui_stepper_id)                                                        \
  X(waterui_text_field_id)                                                     \
  X(waterui_text_id)                                                           \
  X(waterui_toggle_id)                                                         \
  X(waterui_configure_hot_reload_directory)                                    \
  X(waterui_configure_hot_reload_endpoint)                                     \
  X(waterui_view_body)                                                         \
  X(waterui_view_id)                                                           \
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
  X(waterui_new_computed_resolved_color)                                       \
  X(waterui_new_computed_resolved_font)                                        \
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
  X(waterui_call_watcher_resolved_color)                                       \
  X(waterui_call_watcher_resolved_font)                                        \
  X(waterui_call_watcher_color_scheme)                                         \
  X(waterui_drop_watcher_resolved_color)                                       \
  X(waterui_drop_watcher_resolved_font)                                        \
  X(waterui_drop_watcher_color_scheme)

// --- Macros for JNI boilerplate reduction ---

#define DEFINE_WATCHER_CREATOR(JavaName, WatcherType, ValueType, CreatorFunc)  \
  JNIEXPORT jobject JNICALL                                                    \
      Java_dev_waterui_android_runtime_NativeBindings_##JavaName(              \
          JNIEnv *env, jclass, jobject callback) {                             \
    auto *state = create_watcher_state(env, callback);                         \
    return new_watcher_struct(                                                 \
        env, ptr_to_jlong(state),                                              \
        ptr_to_jlong(reinterpret_cast<void *>(watcher_##ValueType##_call)),    \
        ptr_to_jlong(reinterpret_cast<void *>(watcher_##ValueType##_drop)));   \
  }

#define DEFINE_BINDING_ACCESSORS(Type, NativeType, CppType, JniType, SetFunc,          \
                                 ReadFunc, DropFunc, WatchFunc, WatcherType,           \
                                 NewWatcherFunc)                                       \
  JNIEXPORT void JNICALL                                                               \
      Java_dev_waterui_android_runtime_NativeBindings_waterui_1set_1binding_1##Type(   \
          JNIEnv *, jclass, jlong binding_ptr, JniType value) {                        \
    auto *binding = jlong_to_ptr<WuiBinding_##NativeType>(binding_ptr);                \
    g_wui.SetFunc(binding, static_cast<CppType>(value));                               \
  }                                                                                    \
  JNIEXPORT JniType JNICALL                                                            \
      Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1binding_1##Type(  \
          JNIEnv *, jclass, jlong binding_ptr) {                                       \
    auto *binding = jlong_to_ptr<WuiBinding_##NativeType>(binding_ptr);                \
    return static_cast<JniType>(g_wui.ReadFunc(binding));                              \
  }                                                                                    \
  JNIEXPORT void JNICALL                                                               \
      Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1binding_1##Type(  \
          JNIEnv *, jclass, jlong binding_ptr) {                                       \
    auto *binding = jlong_to_ptr<WuiBinding_##NativeType>(binding_ptr);                \
    g_wui.DropFunc(binding);                                                           \
  }                                                                                    \
  JNIEXPORT jlong JNICALL                                                              \
      Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1binding_1##Type( \
          JNIEnv *env, jclass, jlong binding_ptr, jobject watcher_obj) {               \
    auto *binding = jlong_to_ptr<WuiBinding_##NativeType>(binding_ptr);                \
    WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);           \
    auto *watcher =                                                                    \
        create_watcher<WatcherType, CppType>(fields, g_wui.NewWatcherFunc);            \
    return ptr_to_jlong(g_wui.WatchFunc(                                               \
        reinterpret_cast<const Binding_##NativeType *>(binding), watcher));            \
  }

#define DEFINE_COMPUTED_ACCESSORS(Type, NativeType, CppType, JniType,                   \
                                  ReadFunc, WatchFunc, DropFunc, WatcherType,           \
                                  NewWatcherFunc)                                       \
  JNIEXPORT JniType JNICALL                                                             \
      Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1computed_1##Type(  \
          JNIEnv *, jclass, jlong computed_ptr) {                                       \
    auto *computed = jlong_to_ptr<WuiComputed_##NativeType>(computed_ptr);              \
    return static_cast<JniType>(g_wui.ReadFunc(computed));                              \
  }                                                                                     \
  JNIEXPORT jlong JNICALL                                                               \
      Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1computed_1##Type( \
          JNIEnv *env, jclass, jlong computed_ptr, jobject watcher_obj) {               \
    auto *computed = jlong_to_ptr<WuiComputed_##NativeType>(computed_ptr);              \
    WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);            \
    auto *watcher =                                                                     \
        create_watcher<WatcherType, CppType>(fields, g_wui.NewWatcherFunc);             \
    return ptr_to_jlong(g_wui.WatchFunc(computed, watcher));                            \
  }                                                                                     \
  JNIEXPORT void JNICALL                                                                \
      Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1computed_1##Type(  \
          JNIEnv *, jclass, jlong computed_ptr) {                                       \
    auto *computed = jlong_to_ptr<WuiComputed_##NativeType>(computed_ptr);              \
    g_wui.DropFunc(computed);                                                           \
  }

#define DEFINE_FORCE_AS(JavaName, NativeType, FuncName, JavaClass, CtorSig,    \
                        ...)                                                   \
  JNIEXPORT jobject JNICALL                                                    \
      Java_dev_waterui_android_runtime_NativeBindings_##JavaName(              \
          JNIEnv *env, jclass, jlong any_view_ptr) {                           \
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);                       \
    NativeType native = g_wui.FuncName(view);                                  \
    jclass cls = env->FindClass(JavaClass);                                    \
    jmethodID ctor = env->GetMethodID(cls, "<init>", CtorSig);                 \
    jobject obj = env->NewObject(cls, ctor, ##__VA_ARGS__);                    \
    env->DeleteLocalRef(cls);                                                  \
    return obj;                                                                \
  }

struct WaterUiSymbols {
#define DECLARE_WATERUI_SYMBOL(name) decltype(&::name) name = nullptr;
  WATERUI_SYMBOL_LIST(DECLARE_WATERUI_SYMBOL)
#undef DECLARE_WATERUI_SYMBOL
};

WaterUiSymbols g_wui{};
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

extern "C" JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_bootstrapNativeBindings(
    JNIEnv *env, jclass, jstring library_name) {
  if (library_name == nullptr) {
    throw_unsatisfied(env, "Native library name must not be null");
    return;
  }

  const char *raw_name = env->GetStringUTFChars(library_name, nullptr);
  if (raw_name == nullptr) {
    return;
  }
  std::string so_name = "lib";
  so_name += raw_name;
  so_name += ".so";
  env->ReleaseStringUTFChars(library_name, raw_name);

  void *handle = dlopen(so_name.c_str(), RTLD_NOW | RTLD_GLOBAL);
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
  g_wui.name = reinterpret_cast<decltype(&::name)>(dlsym(handle, #name));      \
  if (g_wui.name == nullptr) {                                                 \
    std::string error = "Unable to resolve symbol ";                           \
    error += #name;                                                            \
    error += ": ";                                                             \
    error += dlerror();                                                        \
    throw_unsatisfied(env, error);                                             \
    return;                                                                    \
  }
  WATERUI_SYMBOL_LIST(LOAD_SYMBOL)
#undef LOAD_SYMBOL
  g_symbols_ready = true;
  __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                      "Bound WaterUI symbols from %s", so_name.c_str());
}

class ScopedEnv {
public:
  JNIEnv *env = nullptr;
  bool attached = false;

  ScopedEnv() {
    if (g_vm == nullptr) {
      return;
    }
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

std::string wui_styled_str_to_string(WuiStyledStr styled) {
  std::string result;
  WuiArray_WuiStyledChunk chunks = styled.chunks;
  WuiArraySlice_WuiStyledChunk slice = chunks.vtable.slice(chunks.data);
  result.reserve(static_cast<size_t>(slice.len) * 8);
  for (uintptr_t i = 0; i < slice.len; ++i) {
    result += wui_str_to_std_string(slice.head[i].text);
  }
  chunks.vtable.drop(chunks.data);
  return result;
}

WuiWatcherGuard *make_noop_guard();

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

struct StaticColorState {
  WuiResolvedColor color;
};

WuiResolvedColor static_color_get(const void *data) {
  auto *state = static_cast<const StaticColorState *>(data);
  return state->color;
}

WuiWatcherGuard *static_color_watch(const void *, WuiWatcher_ResolvedColor *) {
  return make_noop_guard();
}

void static_color_drop(void *data) {
  delete static_cast<StaticColorState *>(data);
}

void static_guard_drop(void *) {
  // No-op guard drop for static colors.
}

WuiWatcherGuard *make_noop_guard() {
  return g_wui.waterui_new_watcher_guard(nullptr, static_guard_drop);
}

WuiComputed_ResolvedColor *make_static_color_computed(jint color) {
  auto *state = new StaticColorState{argb_to_resolved_color(color)};
  return g_wui.waterui_new_computed_resolved_color(
      state, static_color_get, static_color_watch, static_color_drop);
}

// ============================================================================
// Reactive Color State - allows updating from Kotlin and notifies watchers
// ============================================================================

struct WatcherEntry {
  WuiWatcher_ResolvedColor *watcher;
  bool active;
};

struct ReactiveColorState {
  WuiResolvedColor color;
  std::vector<WatcherEntry> watchers;
  std::mutex mutex;

  void set_color(const WuiResolvedColor &new_color) {
    std::lock_guard<std::mutex> lock(mutex);
    color = new_color;
    // Notify all active watchers using the FFI function
    for (auto &entry : watchers) {
      if (entry.active && entry.watcher != nullptr) {
        g_wui.waterui_call_watcher_resolved_color(entry.watcher, color);
      }
    }
  }

  size_t add_watcher(WuiWatcher_ResolvedColor *watcher) {
    __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "add_watcher: trying lock (this=%p)", this);
    if (mutex.try_lock()) {
      __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "add_watcher: try_lock succeeded");
      size_t index = watchers.size();
      __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "add_watcher: size=%zu, pushing", index);
      watchers.push_back({watcher, true});
      mutex.unlock();
      __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "add_watcher: returning index %zu", index);
      return index;
    } else {
      __android_log_print(ANDROID_LOG_ERROR, "WaterUI.JNI", "add_watcher: MUTEX ALREADY LOCKED! Deadlock detected.");
      // Return anyway to avoid infinite hang - this is a bug
      std::lock_guard<std::mutex> lock(mutex);
      size_t index = watchers.size();
      watchers.push_back({watcher, true});
      return index;
    }
  }

  void remove_watcher(size_t index) {
    std::lock_guard<std::mutex> lock(mutex);
    if (index < watchers.size()) {
      watchers[index].active = false;
      // Drop the watcher using FFI function
      if (watchers[index].watcher != nullptr) {
        g_wui.waterui_drop_watcher_resolved_color(watchers[index].watcher);
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
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "reactive_color_watch: entering");
  auto *state = const_cast<ReactiveColorState *>(
      static_cast<const ReactiveColorState *>(data));
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "reactive_color_watch: adding watcher");
  size_t index = state->add_watcher(watcher);
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "reactive_color_watch: watcher added at index %zu", index);

  auto *guard_state = new ReactiveGuardState{state, index};
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "reactive_color_watch: calling waterui_new_watcher_guard");
  auto *result = g_wui.waterui_new_watcher_guard(guard_state, reactive_guard_drop);
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "reactive_color_watch: returning guard %p", result);
  return result;
}

void reactive_color_drop(void *data) {
  delete static_cast<ReactiveColorState *>(data);
}

WuiComputed_ResolvedColor *
make_reactive_color_computed(const WuiResolvedColor &color) {
  auto *state = new ReactiveColorState{};
  state->color = color;
  return g_wui.waterui_new_computed_resolved_color(
      state, reactive_color_get, reactive_color_watch, reactive_color_drop);
}

// ============================================================================
// Reactive Font State
// ============================================================================

struct WatcherEntryFont {
  WuiWatcher_ResolvedFont *watcher;
  bool active;
};

struct ReactiveFontState {
  WuiResolvedFont font;
  std::vector<WatcherEntryFont> watchers;
  std::mutex mutex;

  void set_font(const WuiResolvedFont &new_font) {
    std::lock_guard<std::mutex> lock(mutex);
    font = new_font;
    // Notify all active watchers using the FFI function
    for (auto &entry : watchers) {
      if (entry.active && entry.watcher != nullptr) {
        g_wui.waterui_call_watcher_resolved_font(entry.watcher, font);
      }
    }
  }

  size_t add_watcher(WuiWatcher_ResolvedFont *watcher) {
    std::lock_guard<std::mutex> lock(mutex);
    size_t index = watchers.size();
    watchers.push_back({watcher, true});
    return index;
  }

  void remove_watcher(size_t index) {
    std::lock_guard<std::mutex> lock(mutex);
    if (index < watchers.size()) {
      watchers[index].active = false;
      // Drop the watcher using FFI function
      if (watchers[index].watcher != nullptr) {
        g_wui.waterui_drop_watcher_resolved_font(watchers[index].watcher);
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
  return g_wui.waterui_new_watcher_guard(guard_state, reactive_font_guard_drop);
}

void reactive_font_drop(void *data) {
  delete static_cast<ReactiveFontState *>(data);
}

WuiComputed_ResolvedFont *
make_reactive_font_computed(const WuiResolvedFont &font) {
  auto *state = new ReactiveFontState{};
  state->font = font;
  return g_wui.waterui_new_computed_resolved_font(
      state, reactive_font_get, reactive_font_watch, reactive_font_drop);
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

jobject rect_to_java(JNIEnv *env, const WuiRect &rect) {
  jclass cls = env->FindClass("dev/waterui/android/runtime/RectStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FFFF)V");
  jobject obj = env->NewObject(cls, ctor, rect.origin.x, rect.origin.y,
                               rect.size.width, rect.size.height);
  env->DeleteLocalRef(cls);
  return obj;
}

struct ChildArrayHolder {
  WuiChildMetadata *data;
  size_t len;
};

WuiArraySlice_WuiChildMetadata child_slice(const void *opaque) {
  const auto *holder = static_cast<const ChildArrayHolder *>(opaque);
  WuiArraySlice_WuiChildMetadata slice{};
  slice.head = holder->data;
  slice.len = holder->len;
  return slice;
}

void child_drop(void *opaque) {
  auto *holder = static_cast<ChildArrayHolder *>(opaque);
  if (holder == nullptr) {
    return;
  }
  std::free(holder->data);
  std::free(holder);
}

WuiChildMetadata child_from_java(JNIEnv *env, jobject child_obj) {
  jclass cls = env->GetObjectClass(child_obj);

  jmethodID getProposal = env->GetMethodID(
      cls, "getProposal", "()Ldev/waterui/android/runtime/ProposalStruct;");
  jmethodID getPriority = env->GetMethodID(cls, "getPriority", "()I");
  jmethodID getStretch = env->GetMethodID(cls, "isStretch", "()Z");

  jobject proposal_obj = env->CallObjectMethod(child_obj, getProposal);
  WuiProposalSize proposal = proposal_from_java(env, proposal_obj);
  env->DeleteLocalRef(proposal_obj);

  jint priority = env->CallIntMethod(child_obj, getPriority);
  jboolean stretch = env->CallBooleanMethod(child_obj, getStretch);
  env->DeleteLocalRef(cls);

  WuiChildMetadata metadata{};
  metadata.proposal = proposal;
  metadata.priority = static_cast<uint8_t>(priority);
  metadata.stretch = stretch == JNI_TRUE;
  return metadata;
}

WuiArray_WuiChildMetadata children_from_java(JNIEnv *env,
                                             jobjectArray children) {
  jsize len = env->GetArrayLength(children);
  auto *holder =
      static_cast<ChildArrayHolder *>(std::malloc(sizeof(ChildArrayHolder)));
  holder->len = static_cast<size_t>(len);
  holder->data = static_cast<WuiChildMetadata *>(
      std::calloc(holder->len, sizeof(WuiChildMetadata)));

  for (jsize i = 0; i < len; ++i) {
    jobject child_obj = env->GetObjectArrayElement(children, i);
    holder->data[i] = child_from_java(env, child_obj);
    env->DeleteLocalRef(child_obj);
  }

  WuiArray_WuiChildMetadata array{};
  array.data = holder;
  array.vtable.drop = child_drop;
  array.vtable.slice = child_slice;

  return array;
}

jobjectArray proposal_array_to_java(JNIEnv *env,
                                    const WuiArray_WuiProposalSize &array) {
  WuiArraySlice_WuiProposalSize slice = array.vtable.slice(array.data);
  jclass cls = env->FindClass("dev/waterui/android/runtime/ProposalStruct");
  jobjectArray result = env->NewObjectArray(slice.len, cls, nullptr);

  for (uintptr_t i = 0; i < slice.len; ++i) {
    jobject proposal_obj = proposal_to_java(env, slice.head[i]);
    env->SetObjectArrayElement(result, static_cast<jsize>(i), proposal_obj);
    env->DeleteLocalRef(proposal_obj);
  }

  env->DeleteLocalRef(cls);
  array.vtable.drop(array.data);
  return result;
}

jobjectArray rect_array_to_java(JNIEnv *env, const WuiArray_WuiRect &array) {
  WuiArraySlice_WuiRect slice = array.vtable.slice(array.data);
  jclass cls = env->FindClass("dev/waterui/android/runtime/RectStruct");
  jobjectArray result = env->NewObjectArray(slice.len, cls, nullptr);

  for (uintptr_t i = 0; i < slice.len; ++i) {
    jobject rect_obj = rect_to_java(env, slice.head[i]);
    env->SetObjectArrayElement(result, static_cast<jsize>(i), rect_obj);
    env->DeleteLocalRef(rect_obj);
  }

  env->DeleteLocalRef(cls);
  array.vtable.drop(array.data);
  return result;
}

jlongArray any_view_array_to_java(JNIEnv *env, WuiArray_____WuiAnyView array) {
  WuiArraySlice_____WuiAnyView slice = array.vtable.slice(array.data);
  jsize len = static_cast<jsize>(slice.len);
  jlongArray result = env->NewLongArray(len);
  if (result == nullptr) {
    if (array.vtable.drop != nullptr) {
      array.vtable.drop(array.data);
    }
    return nullptr;
  }

  if (len > 0 && slice.head != nullptr) {
    std::vector<jlong> values(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
      values[i] = ptr_to_jlong(slice.head[i]);
    }
    env->SetLongArrayRegion(result, 0, len, values.data());
  }

  if (array.vtable.drop != nullptr) {
    array.vtable.drop(array.data);
  }

  return result;
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
  if (holder == nullptr) {
    return;
  }
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
  if (state == nullptr) {
    return;
  }
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
        g_wui.waterui_read_computed_styled_str(item.content.content);
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

struct WatcherStructFields {
  jlong data;
  jlong call;
  jlong drop;
};

WatcherStructFields watcher_struct_from_java(JNIEnv *env, jobject watcher_obj) {
  WatcherStructFields fields{0, 0, 0};
  if (watcher_obj == nullptr) {
    return fields;
  }
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

void invoke_watcher(JNIEnv *env, WatcherCallbackState *state, jobject value_obj,
                    WuiWatcherMetadata *metadata) {
  if (env == nullptr || state == nullptr) {
    g_wui.waterui_drop_watcher_metadata(metadata);
    return;
  }
  jobject metadata_obj = new_metadata(env, metadata);
  env->CallVoidMethod(state->callback, state->method, value_obj, metadata_obj);
  env->DeleteLocalRef(metadata_obj);
  g_wui.waterui_drop_watcher_metadata(metadata);
}

void watcher_bool_call(const void *data, bool value,
                       WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_wui.waterui_drop_watcher_metadata(metadata);
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
    g_wui.waterui_drop_watcher_metadata(metadata);
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
    g_wui.waterui_drop_watcher_metadata(metadata);
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
    g_wui.waterui_drop_watcher_metadata(metadata);
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

void watcher_styled_str_call(const void *data, WuiStyledStr value,
                             WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_wui.waterui_drop_watcher_metadata(metadata);
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
    g_wui.waterui_drop_watcher_metadata(metadata);
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
    g_wui.waterui_drop_watcher_metadata(metadata);
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

void watcher_picker_items_call(const void *data, WuiArray_WuiPickerItem value,
                               WuiWatcherMetadata *metadata) {
  ScopedEnv scoped;
  if (scoped.env == nullptr) {
    g_wui.waterui_drop_watcher_metadata(metadata);
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
    g_wui.waterui_drop_watcher_metadata(metadata);
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

} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  g_vm = vm;
  JNIEnv *env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
    return JNI_ERR;
  }

  auto init_class = [&](const char *name) -> jclass {
    jclass local = env->FindClass(name);
    if (local == nullptr) {
      return nullptr;
    }
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
  if (scoped.env == nullptr) {
    return;
  }
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

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1init(JNIEnv *,
                                                              jclass) {
  return ptr_to_jlong(g_wui.waterui_init());
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1main(JNIEnv *,
                                                              jclass) {
  return ptr_to_jlong(g_wui.waterui_main());
}

JNIEXPORT jstring JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1view_1id(
    JNIEnv *env, jclass, jlong any_view_ptr) {
  WuiAnyView *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
  WuiStr id = g_wui.waterui_view_id(view);
  return wui_str_to_jstring(env, id);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1view_1body(
    JNIEnv *, jclass, jlong any_view_ptr, jlong env_ptr) {
  WuiAnyView *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
  WuiEnv *env = jlong_to_ptr<WuiEnv>(env_ptr);
  return ptr_to_jlong(g_wui.waterui_view_body(view, env));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1configure_1hot_1reload_1endpoint(
    JNIEnv *env, jclass, jstring host, jint port) {
  if (!g_symbols_ready || host == nullptr) {
    return;
  }

  const char *raw_host = env->GetStringUTFChars(host, nullptr);
  if (raw_host == nullptr) {
    return;
  }
  g_wui.waterui_configure_hot_reload_endpoint(raw_host,
                                              static_cast<uint16_t>(port));
  env->ReleaseStringUTFChars(host, raw_host);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1configure_1hot_1reload_1directory(
    JNIEnv *env, jclass, jstring directory) {
  if (!g_symbols_ready || directory == nullptr) {
    return;
  }
  const char *raw_dir = env->GetStringUTFChars(directory, nullptr);
  if (raw_dir == nullptr) {
    return;
  }
  g_wui.waterui_configure_hot_reload_directory(raw_dir);
  env->ReleaseStringUTFChars(directory, raw_dir);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1clone_1env(
    JNIEnv *, jclass, jlong env_ptr) {
  WuiEnv *env = jlong_to_ptr<WuiEnv>(env_ptr);
  return ptr_to_jlong(g_wui.waterui_clone_env(env));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1env(
    JNIEnv *, jclass, jlong env_ptr) {
  WuiEnv *env = jlong_to_ptr<WuiEnv>(env_ptr);
  g_wui.waterui_drop_env(env);
}

#define DEFINE_THEME_COLOR_FN(javaName, symbol)                                \
  JNIEXPORT jlong JNICALL                                                      \
      Java_dev_waterui_android_runtime_NativeBindings_##javaName(              \
          JNIEnv *, jclass, jlong env_ptr) {                                   \
    WuiEnv *env = jlong_to_ptr<WuiEnv>(env_ptr);                               \
    return ptr_to_jlong(g_wui.symbol(env));                                    \
  }

DEFINE_THEME_COLOR_FN(waterui_1theme_1color_1background,
                      waterui_theme_color_background)
DEFINE_THEME_COLOR_FN(waterui_1theme_1color_1surface,
                      waterui_theme_color_surface)
DEFINE_THEME_COLOR_FN(waterui_1theme_1color_1surface_1variant,
                      waterui_theme_color_surface_variant)
DEFINE_THEME_COLOR_FN(waterui_1theme_1color_1border, waterui_theme_color_border)
DEFINE_THEME_COLOR_FN(waterui_1theme_1color_1foreground,
                      waterui_theme_color_foreground)
DEFINE_THEME_COLOR_FN(waterui_1theme_1color_1muted_1foreground,
                      waterui_theme_color_muted_foreground)
DEFINE_THEME_COLOR_FN(waterui_1theme_1color_1accent, waterui_theme_color_accent)
DEFINE_THEME_COLOR_FN(waterui_1theme_1color_1accent_1foreground,
                      waterui_theme_color_accent_foreground)

#define DEFINE_THEME_FONT_FN(javaName, symbol)                                 \
  JNIEXPORT jlong JNICALL                                                      \
      Java_dev_waterui_android_runtime_NativeBindings_##javaName(              \
          JNIEnv *, jclass, jlong env_ptr) {                                   \
    WuiEnv *env = jlong_to_ptr<WuiEnv>(env_ptr);                               \
    return ptr_to_jlong(g_wui.symbol(env));                                    \
  }

DEFINE_THEME_FONT_FN(waterui_1theme_1font_1body, waterui_theme_font_body)
DEFINE_THEME_FONT_FN(waterui_1theme_1font_1title, waterui_theme_font_title)
DEFINE_THEME_FONT_FN(waterui_1theme_1font_1headline,
                     waterui_theme_font_headline)
DEFINE_THEME_FONT_FN(waterui_1theme_1font_1subheadline,
                     waterui_theme_font_subheadline)
DEFINE_THEME_FONT_FN(waterui_1theme_1font_1caption, waterui_theme_font_caption)
DEFINE_THEME_FONT_FN(waterui_1theme_1font_1footnote,
                     waterui_theme_font_footnote)

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1anyview(
    JNIEnv *, jclass, jlong any_view_ptr) {
  WuiAnyView *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
  g_wui.waterui_drop_anyview(view);
}

#define WATERUI_ID_EXPORT(javaName, ffiFunc)                                   \
  JNIEXPORT jstring JNICALL                                                    \
      Java_dev_waterui_android_runtime_NativeBindings_##javaName(JNIEnv *env,  \
                                                                 jclass) {     \
    WuiStr id = g_wui.ffiFunc();                                               \
    return wui_str_to_jstring(env, id);                                        \
  }

WATERUI_ID_EXPORT(waterui_1empty_1id, waterui_empty_id)
WATERUI_ID_EXPORT(waterui_1text_1id, waterui_text_id)
WATERUI_ID_EXPORT(waterui_1plain_1id, waterui_plain_id)
WATERUI_ID_EXPORT(waterui_1button_1id, waterui_button_id)
WATERUI_ID_EXPORT(waterui_1color_1id, waterui_color_id)
WATERUI_ID_EXPORT(waterui_1text_1field_1id, waterui_text_field_id)
WATERUI_ID_EXPORT(waterui_1stepper_1id, waterui_stepper_id)
WATERUI_ID_EXPORT(waterui_1progress_1id, waterui_progress_id)
WATERUI_ID_EXPORT(waterui_1dynamic_1id, waterui_dynamic_id)
WATERUI_ID_EXPORT(waterui_1scroll_1view_1id, waterui_scroll_view_id)
WATERUI_ID_EXPORT(waterui_1spacer_1id, waterui_spacer_id)
WATERUI_ID_EXPORT(waterui_1toggle_1id, waterui_toggle_id)
WATERUI_ID_EXPORT(waterui_1slider_1id, waterui_slider_id)
WATERUI_ID_EXPORT(waterui_1renderer_1view_1id, waterui_renderer_view_id)
WATERUI_ID_EXPORT(waterui_1layout_1container_1id, waterui_layout_container_id)
WATERUI_ID_EXPORT(waterui_1fixed_1container_1id, waterui_fixed_container_id)
WATERUI_ID_EXPORT(waterui_1picker_1id, waterui_picker_id)

JNIEXPORT jobjectArray JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1layout_1propose(
    JNIEnv *env, jclass, jlong layout_ptr, jobject parent_obj,
    jobjectArray children_array) {
  WuiLayout *layout = jlong_to_ptr<WuiLayout>(layout_ptr);
  WuiProposalSize parent = proposal_from_java(env, parent_obj);
  WuiArray_WuiChildMetadata children = children_from_java(env, children_array);

  WuiArray_WuiProposalSize result =
      g_wui.waterui_layout_propose(layout, parent, children);
  return proposal_array_to_java(env, result);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1layout_1size(
    JNIEnv *env, jclass, jlong layout_ptr, jobject parent_obj,
    jobjectArray children_array) {
  WuiLayout *layout = jlong_to_ptr<WuiLayout>(layout_ptr);
  WuiProposalSize parent = proposal_from_java(env, parent_obj);
  WuiArray_WuiChildMetadata children = children_from_java(env, children_array);

  WuiSize size = g_wui.waterui_layout_size(layout, parent, children);
  return size_to_java(env, size);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1layout_1place(
    JNIEnv *env, jclass, jlong layout_ptr, jobject bounds_obj,
    jobject proposal_obj, jobjectArray children_array) {
  WuiLayout *layout = jlong_to_ptr<WuiLayout>(layout_ptr);
  WuiRect bounds = rect_from_java(env, bounds_obj);
  WuiProposalSize proposal = proposal_from_java(env, proposal_obj);
  WuiArray_WuiChildMetadata children = children_from_java(env, children_array);

  WuiArray_WuiRect placed =
      g_wui.waterui_layout_place(layout, bounds, proposal, children);
  return rect_array_to_java(env, placed);
}

DEFINE_WATCHER_CREATOR(waterui_1create_1bool_1watcher, WuiWatcher_bool, bool,
                       waterui_new_watcher_bool)

DEFINE_WATCHER_CREATOR(waterui_1create_1int_1watcher, WuiWatcher_i32, int,
                       waterui_new_watcher_i32)

DEFINE_WATCHER_CREATOR(waterui_1create_1double_1watcher, WuiWatcher_f64, double,
                       waterui_new_watcher_f64)

DEFINE_WATCHER_CREATOR(waterui_1create_1string_1watcher, WuiWatcher_Str, str,
                       waterui_new_watcher_str)

DEFINE_WATCHER_CREATOR(waterui_1create_1any_1view_1watcher, WuiWatcher_AnyView,
                       anyview, waterui_new_watcher_any_view)

DEFINE_WATCHER_CREATOR(waterui_1create_1styled_1str_1watcher,
                       WuiWatcher_StyledStr, styled_str,
                       waterui_new_watcher_styled_str)

DEFINE_WATCHER_CREATOR(waterui_1create_1resolved_1color_1watcher,
                       WuiWatcher_ResolvedColor, resolved_color,
                       waterui_new_watcher_resolved_color)

DEFINE_WATCHER_CREATOR(waterui_1create_1resolved_1font_1watcher,
                       WuiWatcher_ResolvedFont, resolved_font,
                       waterui_new_watcher_resolved_font)

DEFINE_WATCHER_CREATOR(waterui_1create_1picker_1items_1watcher,
                       WuiWatcher_Vec_PickerItem_Id, picker_items,
                       waterui_new_watcher_picker_items)

DEFINE_BINDING_ACCESSORS(bool, bool, bool, jboolean, waterui_set_binding_bool,
                         waterui_read_binding_bool, waterui_drop_binding_bool,
                         waterui_watch_binding_bool, WuiWatcher_bool,
                         waterui_new_watcher_bool)

DEFINE_BINDING_ACCESSORS(int, i32, int32_t, jint, waterui_set_binding_i32,
                         waterui_read_binding_i32, waterui_drop_binding_i32,
                         waterui_watch_binding_i32, WuiWatcher_i32,
                         waterui_new_watcher_i32)

DEFINE_BINDING_ACCESSORS(double, f64, double, jdouble, waterui_set_binding_f64,
                         waterui_read_binding_f64, waterui_drop_binding_f64,
                         waterui_watch_binding_f64, WuiWatcher_f64,
                         waterui_new_watcher_f64)

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1set_1binding_1str(
    JNIEnv *env, jclass, jlong binding_ptr, jbyteArray value) {
  auto *binding = jlong_to_ptr<WuiBinding_Str>(binding_ptr);
  WuiStr str = str_from_byte_array(env, value);
  g_wui.waterui_set_binding_str(binding, str);
  str._0.vtable.drop(str._0.data);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1binding_1str(
    JNIEnv *env, jclass, jlong binding_ptr) {
  auto *binding = jlong_to_ptr<WuiBinding_Str>(binding_ptr);
  WuiStr value = g_wui.waterui_read_binding_str(binding);
  return wui_str_to_byte_array(env, value);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1binding_1str(
    JNIEnv *, jclass, jlong binding_ptr) {
  auto *binding = jlong_to_ptr<WuiBinding_Str>(binding_ptr);
  g_wui.waterui_drop_binding_str(binding);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1binding_1str(
    JNIEnv *env, jclass, jlong binding_ptr, jobject watcher_obj) {
  auto *binding = jlong_to_ptr<WuiBinding_Str>(binding_ptr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
  auto *watcher = create_watcher<WuiWatcher_Str, WuiStr>(
      fields, g_wui.waterui_new_watcher_str);
  return ptr_to_jlong(g_wui.waterui_watch_binding_str(
      reinterpret_cast<const Binding_Str *>(binding), watcher));
}

DEFINE_COMPUTED_ACCESSORS(f64, f64, double, jdouble, waterui_read_computed_f64,
                          waterui_watch_computed_f64, waterui_drop_computed_f64,
                          WuiWatcher_f64, waterui_new_watcher_f64)

DEFINE_COMPUTED_ACCESSORS(i32, i32, int32_t, jint, waterui_read_computed_i32,
                          waterui_watch_computed_i32, waterui_drop_computed_i32,
                          WuiWatcher_i32, waterui_new_watcher_i32)

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1computed_1styled_1str(
    JNIEnv *env, jclass, jlong computed_ptr) {
  auto *computed = jlong_to_ptr<WuiComputed_StyledStr>(computed_ptr);
  WuiStyledStr styled = g_wui.waterui_read_computed_styled_str(computed);
  return new_styled_str(env, styled);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1computed_1styled_1str(
    JNIEnv *env, jclass, jlong computed_ptr, jobject watcher_obj) {
  auto *computed = jlong_to_ptr<WuiComputed_StyledStr>(computed_ptr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
  auto *watcher = create_watcher<WuiWatcher_StyledStr, WuiStyledStr>(
      fields, g_wui.waterui_new_watcher_styled_str);
  return ptr_to_jlong(
      g_wui.waterui_watch_computed_styled_str(computed, watcher));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1computed_1styled_1str(
    JNIEnv *, jclass, jlong computed_ptr) {
  auto *computed = jlong_to_ptr<WuiComputed_StyledStr>(computed_ptr);
  g_wui.waterui_drop_computed_styled_str(computed);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1computed_1picker_1items(
    JNIEnv *env, jclass, jlong computed_ptr) {
  auto *computed = jlong_to_ptr<WuiComputed_Vec_PickerItem_Id>(computed_ptr);
  WuiArray_WuiPickerItem items =
      g_wui.waterui_read_computed_picker_items(computed);
  return picker_items_to_java(env, items);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1computed_1picker_1items(
    JNIEnv *env, jclass, jlong computed_ptr, jobject watcher_obj) {
  auto *computed = jlong_to_ptr<WuiComputed_Vec_PickerItem_Id>(computed_ptr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
  auto *watcher =
      create_watcher<WuiWatcher_Vec_PickerItem_Id, WuiArray_WuiPickerItem>(
          fields, g_wui.waterui_new_watcher_picker_items);
  return ptr_to_jlong(
      g_wui.waterui_watch_computed_picker_items(computed, watcher));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1computed_1picker_1items(
    JNIEnv *, jclass, jlong computed_ptr) {
  auto *computed = jlong_to_ptr<WuiComputed_Vec_PickerItem_Id>(computed_ptr);
  g_wui.waterui_drop_computed_picker_items(computed);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1resolve_1font(
    JNIEnv *, jclass, jlong font_ptr, jlong env_ptr) {
  auto *font = jlong_to_ptr<WuiFont>(font_ptr);
  auto *env = jlong_to_ptr<WuiEnv>(env_ptr);
  return ptr_to_jlong(g_wui.waterui_resolve_font(font, env));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1font(
    JNIEnv *, jclass, jlong font_ptr) {
  auto *font = jlong_to_ptr<WuiFont>(font_ptr);
  g_wui.waterui_drop_font(font);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1computed_1resolved_1font(
    JNIEnv *env, jclass, jlong computed_ptr) {
  auto *computed = jlong_to_ptr<WuiComputed_ResolvedFont>(computed_ptr);
  WuiResolvedFont font = g_wui.waterui_read_computed_resolved_font(computed);
  jclass cls = env->FindClass("dev/waterui/android/runtime/ResolvedFontStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(FI)V");
  jobject obj =
      env->NewObject(cls, ctor, font.size, static_cast<jint>(font.weight));
  env->DeleteLocalRef(cls);
  return obj;
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1computed_1resolved_1font(
    JNIEnv *env, jclass, jlong computed_ptr, jobject watcher_obj) {
  auto *computed = jlong_to_ptr<WuiComputed_ResolvedFont>(computed_ptr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
  auto *watcher = create_watcher<WuiWatcher_ResolvedFont, WuiResolvedFont>(
      fields, g_wui.waterui_new_watcher_resolved_font);
  return ptr_to_jlong(
      g_wui.waterui_watch_computed_resolved_font(computed, watcher));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1computed_1resolved_1font(
    JNIEnv *, jclass, jlong computed_ptr) {
  auto *computed = jlong_to_ptr<WuiComputed_ResolvedFont>(computed_ptr);
  g_wui.waterui_drop_computed_resolved_font(computed);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1resolve_1color(
    JNIEnv *, jclass, jlong color_ptr, jlong env_ptr) {
  auto *color = jlong_to_ptr<WuiColor>(color_ptr);
  auto *env = jlong_to_ptr<WuiEnv>(env_ptr);
  return ptr_to_jlong(g_wui.waterui_resolve_color(color, env));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1color(
    JNIEnv *, jclass, jlong color_ptr) {
  auto *color = jlong_to_ptr<WuiColor>(color_ptr);
  g_wui.waterui_drop_color(color);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1computed_1resolved_1color(
    JNIEnv *env, jclass, jlong computed_ptr) {
  auto *computed = jlong_to_ptr<WuiComputed_ResolvedColor>(computed_ptr);
  WuiResolvedColor color = g_wui.waterui_read_computed_resolved_color(computed);
  return new_resolved_color(env, color);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1computed_1resolved_1color(
    JNIEnv *env, jclass, jlong computed_ptr, jobject watcher_obj) {
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "watch_computed_resolved_color: entering, computed_ptr=%ld", computed_ptr);
  auto *computed = jlong_to_ptr<WuiComputed_ResolvedColor>(computed_ptr);
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "watch_computed_resolved_color: getting watcher struct");
  WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "watch_computed_resolved_color: creating watcher");
  auto *watcher = create_watcher<WuiWatcher_ResolvedColor, WuiResolvedColor>(
      fields, g_wui.waterui_new_watcher_resolved_color);
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "watch_computed_resolved_color: calling Rust waterui_watch_computed_resolved_color");
  auto *result = g_wui.waterui_watch_computed_resolved_color(computed, watcher);
  __android_log_print(ANDROID_LOG_DEBUG, "WaterUI.JNI", "watch_computed_resolved_color: Rust returned %p", result);
  return ptr_to_jlong(result);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1computed_1resolved_1color(
    JNIEnv *, jclass, jlong computed_ptr) {
  auto *computed = jlong_to_ptr<WuiComputed_ResolvedColor>(computed_ptr);
  g_wui.waterui_drop_computed_resolved_color(computed);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1watcher_1guard(
    JNIEnv *, jclass, jlong guard_ptr) {
  auto *guard = jlong_to_ptr<WuiWatcherGuard>(guard_ptr);
  g_wui.waterui_drop_box_watcher_guard(guard);
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1get_1animation(
    JNIEnv *, jclass, jlong metadata_ptr) {
  auto *metadata = jlong_to_ptr<WuiWatcherMetadata>(metadata_ptr);
  return static_cast<jint>(g_wui.waterui_get_animation(metadata));
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1any_1views_1len(
    JNIEnv *, jclass, jlong handle) {
  auto *views = jlong_to_ptr<WuiAnyViews>(handle);
  return static_cast<jint>(g_wui.waterui_anyviews_len(views));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1any_1views_1get_1view(
    JNIEnv *, jclass, jlong handle, jint index) {
  auto *views = jlong_to_ptr<WuiAnyViews>(handle);
  return ptr_to_jlong(
      g_wui.waterui_anyviews_get_view(views, static_cast<uintptr_t>(index)));
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1any_1views_1get_1id(
    JNIEnv *, jclass, jlong handle, jint index) {
  auto *views = jlong_to_ptr<WuiAnyViews>(handle);
  WuiId id =
      g_wui.waterui_anyviews_get_id(views, static_cast<uintptr_t>(index));
  return static_cast<jint>(id.inner);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1any_1views(
    JNIEnv *, jclass, jlong handle) {
  auto *views = jlong_to_ptr<WuiAnyViews>(handle);
  g_wui.waterui_drop_anyviews(views);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1layout(
    JNIEnv *, jclass, jlong layout_ptr) {
  auto *layout = jlong_to_ptr<WuiLayout>(layout_ptr);
  g_wui.waterui_drop_layout(layout);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1action(
    JNIEnv *, jclass, jlong action_ptr) {
  auto *action = jlong_to_ptr<WuiAction>(action_ptr);
  g_wui.waterui_drop_action(action);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1call_1action(
    JNIEnv *, jclass, jlong action_ptr, jlong env_ptr) {
  auto *action = jlong_to_ptr<WuiAction>(action_ptr);
  auto *env = jlong_to_ptr<WuiEnv>(env_ptr);
  g_wui.waterui_call_action(action, env);
}

DEFINE_FORCE_AS(waterui_1force_1as_1plain, WuiStr, waterui_force_as_plain,
                "dev/waterui/android/runtime/PlainStruct", "([B)V",
                wui_str_to_byte_array(env, native))

DEFINE_FORCE_AS(waterui_1force_1as_1text, WuiText, waterui_force_as_text,
                "dev/waterui/android/runtime/TextStruct", "(J)V",
                ptr_to_jlong(native.content))

DEFINE_FORCE_AS(waterui_1force_1as_1button, WuiButton, waterui_force_as_button,
                "dev/waterui/android/runtime/ButtonStruct", "(JJ)V",
                ptr_to_jlong(native.label), ptr_to_jlong(native.action))

DEFINE_FORCE_AS(waterui_1force_1as_1color, WuiColor *, waterui_force_as_color,
                "dev/waterui/android/runtime/ColorStruct", "(J)V",
                ptr_to_jlong(native))

DEFINE_FORCE_AS(waterui_1force_1as_1text_1field, WuiTextField,
                waterui_force_as_text_field,
                "dev/waterui/android/runtime/TextFieldStruct", "(JJJI)V",
                ptr_to_jlong(native.label), ptr_to_jlong(native.value),
                ptr_to_jlong(native.prompt.content),
                static_cast<jint>(native.keyboard))

DEFINE_FORCE_AS(waterui_1force_1as_1toggle, WuiToggle, waterui_force_as_toggle,
                "dev/waterui/android/runtime/ToggleStruct", "(JJ)V",
                ptr_to_jlong(native.label), ptr_to_jlong(native.toggle))

DEFINE_FORCE_AS(waterui_1force_1as_1slider, WuiSlider, waterui_force_as_slider,
                "dev/waterui/android/runtime/SliderStruct", "(JJJDDJ)V",
                ptr_to_jlong(native.label),
                ptr_to_jlong(native.min_value_label),
                ptr_to_jlong(native.max_value_label), native.range.start,
                native.range.end, ptr_to_jlong(native.value))

DEFINE_FORCE_AS(waterui_1force_1as_1stepper, WuiStepper,
                waterui_force_as_stepper,
                "dev/waterui/android/runtime/StepperStruct", "(JJJII)V",
                ptr_to_jlong(native.value), ptr_to_jlong(native.step),
                ptr_to_jlong(native.label),
                static_cast<jint>(native.range.start),
                static_cast<jint>(native.range.end))

DEFINE_FORCE_AS(waterui_1force_1as_1progress, WuiProgress,
                waterui_force_as_progress,
                "dev/waterui/android/runtime/ProgressStruct", "(JJJI)V",
                ptr_to_jlong(native.label), ptr_to_jlong(native.value_label),
                ptr_to_jlong(native.value), static_cast<jint>(native.style))

DEFINE_FORCE_AS(waterui_1force_1as_1scroll, WuiScrollView,
                waterui_force_as_scroll_view,
                "dev/waterui/android/runtime/ScrollStruct", "(IJ)V",
                static_cast<jint>(native.axis), ptr_to_jlong(native.content))

DEFINE_FORCE_AS(waterui_1force_1as_1picker, WuiPicker, waterui_force_as_picker,
                "dev/waterui/android/runtime/PickerStruct", "(JJ)V",
                ptr_to_jlong(native.items), ptr_to_jlong(native.selection))

DEFINE_FORCE_AS(waterui_1force_1as_1layout_1container, WuiContainer,
                waterui_force_as_layout_container,
                "dev/waterui/android/runtime/ContainerStruct", "(JJ)V",
                ptr_to_jlong(native.layout), ptr_to_jlong(native.contents))

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1fixed_1container(
    JNIEnv *env, jclass, jlong any_view_ptr) {
  auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
  WuiFixedContainer container = g_wui.waterui_force_as_fixed_container(view);
  jlongArray children = any_view_array_to_java(env, container.contents);
  if (children == nullptr) {
    return nullptr;
  }
  jclass cls =
      env->FindClass("dev/waterui/android/runtime/FixedContainerStruct");
  jmethodID ctor = env->GetMethodID(cls, "<init>", "(J[J)V");
  jobject obj =
      env->NewObject(cls, ctor, ptr_to_jlong(container.layout), children);
  env->DeleteLocalRef(children);
  env->DeleteLocalRef(cls);
  return obj;
}

DEFINE_FORCE_AS(waterui_1force_1as_1dynamic, WuiDynamic *,
                waterui_force_as_dynamic,
                "dev/waterui/android/runtime/DynamicStruct", "(J)V",
                ptr_to_jlong(native))

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1dynamic(
    JNIEnv *, jclass, jlong dynamic_ptr) {
  auto *dynamic = jlong_to_ptr<WuiDynamic>(dynamic_ptr);
  g_wui.waterui_drop_dynamic(dynamic);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1dynamic_1connect(
    JNIEnv *env, jclass, jlong dynamic_ptr, jobject watcher_obj) {
  auto *dynamic = jlong_to_ptr<WuiDynamic>(dynamic_ptr);
  WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
  auto *watcher = create_watcher<WuiWatcher_AnyView, WuiAnyView *>(
      fields, g_wui.waterui_new_watcher_any_view);
  g_wui.waterui_dynamic_connect(dynamic, watcher);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1renderer_1view(
    JNIEnv *, jclass, jlong any_view_ptr) {
  auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
  return ptr_to_jlong(g_wui.waterui_force_as_renderer_view(view));
}

JNIEXPORT jfloat JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1renderer_1view_1width(
    JNIEnv *, jclass, jlong handle) {
  auto *renderer = jlong_to_ptr<WuiRendererView>(handle);
  return g_wui.waterui_renderer_view_width(renderer);
}

JNIEXPORT jfloat JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1renderer_1view_1height(
    JNIEnv *, jclass, jlong handle) {
  auto *renderer = jlong_to_ptr<WuiRendererView>(handle);
  return g_wui.waterui_renderer_view_height(renderer);
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1renderer_1view_1preferred_1format(
    JNIEnv *, jclass, jlong handle) {
  auto *renderer = jlong_to_ptr<WuiRendererView>(handle);
  return static_cast<jint>(
      g_wui.waterui_renderer_view_preferred_format(renderer));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1install_1static_1theme(
    JNIEnv *, jclass, jlong env_ptr, jint background, jint surface,
    jint surface_variant, jint border, jint foreground, jint muted_foreground,
    jint accent, jint accent_foreground) {
  if (!g_symbols_ready) {
    return;
  }

  WuiComputed_ResolvedColor *background_ptr =
      make_static_color_computed(background);
  WuiComputed_ResolvedColor *surface_ptr = make_static_color_computed(surface);
  WuiComputed_ResolvedColor *surface_variant_ptr =
      make_static_color_computed(surface_variant);
  WuiComputed_ResolvedColor *border_ptr = make_static_color_computed(border);
  WuiComputed_ResolvedColor *foreground_ptr =
      make_static_color_computed(foreground);
  WuiComputed_ResolvedColor *muted_ptr =
      make_static_color_computed(muted_foreground);
  WuiComputed_ResolvedColor *accent_ptr = make_static_color_computed(accent);
  WuiComputed_ResolvedColor *accent_foreground_ptr =
      make_static_color_computed(accent_foreground);

  g_wui.waterui_env_install_theme(
      jlong_to_ptr<WuiEnv>(env_ptr), background_ptr, surface_ptr,
      surface_variant_ptr, border_ptr, foreground_ptr, muted_ptr, accent_ptr,
      accent_foreground_ptr, nullptr, nullptr, nullptr, nullptr, nullptr);
}

// ============================================================================
// Slot-based Theme Install JNI Methods
// ============================================================================

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1theme_1install_1color_1scheme(
    JNIEnv *, jclass, jlong env_ptr, jlong signal_ptr) {
  if (!g_symbols_ready || env_ptr == 0 || signal_ptr == 0)
    return;
  g_wui.waterui_theme_install_color_scheme(
      jlong_to_ptr<WuiEnv>(env_ptr),
      jlong_to_ptr<WuiComputed_ColorScheme>(signal_ptr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1theme_1install_1color(
    JNIEnv *, jclass, jlong env_ptr, jint slot, jlong signal_ptr) {
  if (!g_symbols_ready || env_ptr == 0 || signal_ptr == 0)
    return;
  g_wui.waterui_theme_install_color(
      jlong_to_ptr<WuiEnv>(env_ptr), static_cast<WuiColorSlot>(slot),
      jlong_to_ptr<WuiComputed_ResolvedColor>(signal_ptr));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1theme_1install_1font(
    JNIEnv *, jclass, jlong env_ptr, jint slot, jlong signal_ptr) {
  if (!g_symbols_ready || env_ptr == 0 || signal_ptr == 0)
    return;
  g_wui.waterui_theme_install_font(
      jlong_to_ptr<WuiEnv>(env_ptr), static_cast<WuiFontSlot>(slot),
      jlong_to_ptr<WuiComputed_ResolvedFont>(signal_ptr));
}

// Slot-based theme query functions
JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1theme_1color_1scheme(
    JNIEnv *, jclass, jlong env_ptr) {
  if (!g_symbols_ready || env_ptr == 0)
    return 0;
  return ptr_to_jlong(
      g_wui.waterui_theme_color_scheme(jlong_to_ptr<WuiEnv>(env_ptr)));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1theme_1color(
    JNIEnv *, jclass, jlong env_ptr, jint slot) {
  if (!g_symbols_ready || env_ptr == 0)
    return 0;
  return ptr_to_jlong(g_wui.waterui_theme_color(
      jlong_to_ptr<WuiEnv>(env_ptr), static_cast<WuiColorSlot>(slot)));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1theme_1font(
    JNIEnv *, jclass, jlong env_ptr, jint slot) {
  if (!g_symbols_ready || env_ptr == 0)
    return 0;
  return ptr_to_jlong(g_wui.waterui_theme_font(jlong_to_ptr<WuiEnv>(env_ptr),
                                               static_cast<WuiFontSlot>(slot)));
}

// ============================================================================
// Reactive Theme Signal JNI Methods
// ============================================================================

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1reactive_1color_1state(
    JNIEnv *, jclass, jint argb) {
  if (!g_symbols_ready)
    return 0;
  WuiResolvedColor color = argb_to_resolved_color(argb);
  auto *state = new ReactiveColorState{};
  state->color = color;
  return ptr_to_jlong(state);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1reactive_1color_1state_1to_1computed(
    JNIEnv *, jclass, jlong state_ptr) {
  if (!g_symbols_ready || state_ptr == 0)
    return 0;
  auto *state = jlong_to_ptr<ReactiveColorState>(state_ptr);
  return ptr_to_jlong(g_wui.waterui_new_computed_resolved_color(
      state, reactive_color_get, reactive_color_watch, reactive_color_drop));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1reactive_1color_1state_1set(
    JNIEnv *, jclass, jlong state_ptr, jint argb) {
  if (state_ptr == 0)
    return;
  auto *state = jlong_to_ptr<ReactiveColorState>(state_ptr);
  state->set_color(argb_to_resolved_color(argb));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1reactive_1font_1state(
    JNIEnv *env, jclass, jfloat size, jint weight) {
  if (!g_symbols_ready)
    return 0;
  auto *state = new ReactiveFontState{};
  state->font.size = size;
  state->font.weight = static_cast<WuiFontWeight>(weight);
  return ptr_to_jlong(state);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1reactive_1font_1state_1to_1computed(
    JNIEnv *, jclass, jlong state_ptr) {
  if (!g_symbols_ready || state_ptr == 0)
    return 0;
  auto *state = jlong_to_ptr<ReactiveFontState>(state_ptr);
  return ptr_to_jlong(g_wui.waterui_new_computed_resolved_font(
      state, reactive_font_get, reactive_font_watch, reactive_font_drop));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1reactive_1font_1state_1set(
    JNIEnv *env, jclass, jlong state_ptr, jfloat size, jint weight) {
  if (state_ptr == 0)
    return;
  auto *state = jlong_to_ptr<ReactiveFontState>(state_ptr);
  WuiResolvedFont new_font{};
  new_font.size = size;
  new_font.weight = static_cast<WuiFontWeight>(weight);
  state->set_font(new_font);
}

JNIEXPORT jboolean JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1renderer_1view_1render_1cpu(
    JNIEnv *env, jclass, jlong handle, jbyteArray pixel_array, jint width,
    jint height, jint stride, jint format) {
  auto *renderer = jlong_to_ptr<WuiRendererView>(handle);
  jboolean is_copy = JNI_FALSE;
  jbyte *pixels = env->GetByteArrayElements(pixel_array, &is_copy);
  bool ok = g_wui.waterui_renderer_view_render_cpu(
      renderer, reinterpret_cast<uint8_t *>(pixels),
      static_cast<uint32_t>(width), static_cast<uint32_t>(height),
      static_cast<uintptr_t>(stride),
      static_cast<WuiRendererBufferFormat>(format));
  env->ReleaseByteArrayElements(pixel_array, pixels, 0);
  return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1renderer_1view(
    JNIEnv *, jclass, jlong handle) {
  auto *renderer = jlong_to_ptr<WuiRendererView>(handle);
  g_wui.waterui_drop_renderer_view(renderer);
}

} // extern "C"
