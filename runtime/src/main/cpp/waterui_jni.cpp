#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "waterui.h"

namespace {

constexpr char LOG_TAG[] = "WaterUI.JNI";

#define WATERUI_SYMBOL_LIST(X)                                                                 \
    X(waterui_anyviews_get_id)                                                                 \
    X(waterui_anyviews_get_view)                                                               \
    X(waterui_anyviews_len)                                                                    \
    X(waterui_button_id)                                                                       \
    X(waterui_call_action)                                                                     \
    X(waterui_clone_env)                                                                       \
    X(waterui_color_id)                                                                        \
    X(waterui_drop_action)                                                                     \
    X(waterui_drop_anyview)                                                                    \
    X(waterui_drop_anyviews)                                                                   \
    X(waterui_drop_binding_bool)                                                               \
    X(waterui_drop_binding_f64)                                                                \
    X(waterui_drop_binding_i32)                                                                \
    X(waterui_drop_binding_str)                                                                \
    X(waterui_drop_box_watcher_guard)                                                          \
    X(waterui_drop_computed_f64)                                                               \
    X(waterui_drop_computed_i32)                                                               \
    X(waterui_drop_computed_resolved_color)                                                    \
    X(waterui_drop_computed_styled_str)                                                        \
    X(waterui_drop_dynamic)                                                                    \
    X(waterui_drop_env)                                                                        \
    X(waterui_drop_layout)                                                                     \
    X(waterui_drop_renderer_view)                                                              \
    X(waterui_drop_watcher_metadata)                                                           \
    X(waterui_dynamic_connect)                                                                 \
    X(waterui_dynamic_id)                                                                      \
    X(waterui_empty_id)                                                                        \
    X(waterui_fixed_container_id)                                                              \
    X(waterui_force_as_button)                                                                 \
    X(waterui_force_as_color)                                                                  \
    X(waterui_force_as_fixed_container)                                                        \
    X(waterui_force_as_dynamic)                                                                \
    X(waterui_force_as_layout_container)                                                       \
    X(waterui_force_as_plain)                                                                  \
    X(waterui_force_as_progress)                                                               \
    X(waterui_force_as_renderer_view)                                                          \
    X(waterui_force_as_scroll_view)                                                            \
    X(waterui_force_as_slider)                                                                 \
    X(waterui_force_as_stepper)                                                                \
    X(waterui_force_as_text)                                                                   \
    X(waterui_force_as_text_field)                                                             \
    X(waterui_force_as_toggle)                                                                 \
    X(waterui_get_animation)                                                                   \
    X(waterui_init)                                                                            \
    X(waterui_layout_container_id)                                                             \
    X(waterui_layout_place)                                                                    \
    X(waterui_layout_propose)                                                                  \
    X(waterui_layout_size)                                                                     \
    X(waterui_main)                                                                            \
    X(waterui_plain_id)                                                                        \
    X(waterui_progress_id)                                                                     \
    X(waterui_read_binding_bool)                                                               \
    X(waterui_read_binding_f64)                                                                \
    X(waterui_read_binding_i32)                                                                \
    X(waterui_read_binding_str)                                                                \
    X(waterui_read_computed_f64)                                                               \
    X(waterui_read_computed_i32)                                                               \
    X(waterui_read_computed_resolved_color)                                                    \
    X(waterui_read_computed_styled_str)                                                        \
    X(waterui_renderer_view_height)                                                            \
    X(waterui_renderer_view_id)                                                                \
    X(waterui_renderer_view_preferred_format)                                                  \
    X(waterui_renderer_view_render_cpu)                                                        \
    X(waterui_renderer_view_width)                                                             \
    X(waterui_resolve_color)                                                                   \
    X(waterui_scroll_view_id)                                                                  \
    X(waterui_set_binding_bool)                                                                \
    X(waterui_set_binding_f64)                                                                 \
    X(waterui_set_binding_i32)                                                                 \
    X(waterui_set_binding_str)                                                                 \
    X(waterui_slider_id)                                                                       \
    X(waterui_spacer_id)                                                                       \
    X(waterui_stepper_id)                                                                      \
    X(waterui_text_field_id)                                                                   \
    X(waterui_text_id)                                                                         \
    X(waterui_toggle_id)                                                                       \
    X(waterui_view_body)                                                                       \
    X(waterui_view_id)                                                                         \
    X(waterui_watch_binding_bool)                                                              \
    X(waterui_watch_binding_f64)                                                               \
    X(waterui_watch_binding_i32)                                                               \
    X(waterui_watch_binding_str)                                                               \
    X(waterui_watch_computed_f64)                                                              \
    X(waterui_watch_computed_i32)                                                              \
    X(waterui_watch_computed_resolved_color)                                                   \
    X(waterui_watch_computed_styled_str)

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
    JNIEnv *env,
    jclass,
    jstring library_name) {
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
#define LOAD_SYMBOL(name)                                                                     \
    g_wui.name = reinterpret_cast<decltype(&::name)>(dlsym(handle, #name));                   \
    if (g_wui.name == nullptr) {                                                              \
        std::string error = "Unable to resolve symbol ";                                      \
        error += #name;                                                                       \
        error += ": ";                                                                        \
        error += dlerror();                                                                   \
        throw_unsatisfied(env, error);                                                        \
        return;                                                                               \
    }
    WATERUI_SYMBOL_LIST(LOAD_SYMBOL)
#undef LOAD_SYMBOL

    g_symbols_ready = true;
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Bound WaterUI symbols from %s", so_name.c_str());
}

class ScopedEnv {
public:
    JNIEnv *env = nullptr;
    bool attached = false;

    ScopedEnv() {
        if (g_vm == nullptr) {
            return;
        }
        if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
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

template <typename T>
inline jlong ptr_to_jlong(T *ptr) {
    return reinterpret_cast<jlong>(ptr);
}

template <typename T>
inline T *jlong_to_ptr(jlong value) {
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

WuiProposalSize proposal_from_java(JNIEnv *env, jobject proposal_obj) {
    jclass cls = env->GetObjectClass(proposal_obj);
    jmethodID getWidth =
        env->GetMethodID(cls, "getWidth", "()F");
    jmethodID getHeight =
        env->GetMethodID(cls, "getHeight", "()F");

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
    jobject obj = env->NewObject(
        cls, ctor,
        proposal.width,
        proposal.height
    );
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
    jobject obj = env->NewObject(
        cls,
        ctor,
        rect.origin.x,
        rect.origin.y,
        rect.size.width,
        rect.size.height
    );
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

    jmethodID getProposal =
        env->GetMethodID(
            cls,
            "getProposal",
            "()Ldev/waterui/android/runtime/ProposalStruct;");
    jmethodID getPriority =
        env->GetMethodID(cls, "getPriority", "()I");
    jmethodID getStretch =
        env->GetMethodID(cls, "isStretch", "()Z");

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

WuiArray_WuiChildMetadata children_from_java(JNIEnv *env, jobjectArray children) {
    jsize len = env->GetArrayLength(children);
    auto *holder = static_cast<ChildArrayHolder *>(std::malloc(sizeof(ChildArrayHolder)));
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

jobjectArray proposal_array_to_java(JNIEnv *env, const WuiArray_WuiProposalSize &array) {
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
    auto *holder = static_cast<ByteArrayHolder *>(std::malloc(sizeof(ByteArrayHolder)));
    holder->len = static_cast<size_t>(len);
    holder->data = static_cast<uint8_t *>(std::malloc(holder->len));
    env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte *>(holder->data));

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
        cls,
        "onChanged",
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
    return env->NewObject(gWatcherStructClass, gWatcherStructCtor, data, call, drop);
}

jobject new_resolved_color(JNIEnv *env, const WuiResolvedColor &color) {
    jclass cls = env->FindClass("dev/waterui/android/runtime/ResolvedColorStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(FFFF)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        color.red,
        color.green,
        color.blue,
        color.opacity
    );
    env->DeleteLocalRef(cls);
    return obj;
}

jobject new_text_style(JNIEnv *env, const WuiTextStyle &style, jclass cls, jmethodID ctor) {
    return env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(style.font),
        style.italic ? JNI_TRUE : JNI_FALSE,
        style.underline ? JNI_TRUE : JNI_FALSE,
        style.strikethrough ? JNI_TRUE : JNI_FALSE,
        ptr_to_jlong(style.foreground),
        ptr_to_jlong(style.background)
    );
}

jobject new_styled_chunk(
    JNIEnv *env,
    const WuiStyledChunk &chunk,
    jclass chunkCls,
    jmethodID chunkCtor,
    jclass styleCls,
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

    jclass styleCls = env->FindClass("dev/waterui/android/runtime/TextStyleStruct");
    jmethodID styleCtor = env->GetMethodID(styleCls, "<init>", "(JZZZJJ)V");
    jclass chunkCls = env->FindClass("dev/waterui/android/runtime/StyledChunkStruct");
    jmethodID chunkCtor = env->GetMethodID(
        chunkCls,
        "<init>",
        "(Ljava/lang/String;Ldev/waterui/android/runtime/TextStyleStruct;)V");
    jclass strCls = env->FindClass("dev/waterui/android/runtime/StyledStrStruct");
    jmethodID strCtor = env->GetMethodID(
        strCls,
        "<init>",
        "([Ldev/waterui/android/runtime/StyledChunkStruct;)V");

    jobjectArray chunkArray = env->NewObjectArray(
        static_cast<jsize>(slice.len),
        chunkCls,
        nullptr
    );

    for (uintptr_t i = 0; i < slice.len; ++i) {
        jobject chunkObj = new_styled_chunk(env, slice.head[i], chunkCls, chunkCtor, styleCls, styleCtor);
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

jobject box_boolean(JNIEnv *env, bool value) {
    return env->CallStaticObjectMethod(gBooleanClass, gBooleanValueOf, value ? JNI_TRUE : JNI_FALSE);
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

void invoke_watcher(JNIEnv *env, WatcherCallbackState *state, jobject value_obj, WuiWatcherMetadata *metadata) {
    if (env == nullptr || state == nullptr) {
        g_wui.waterui_drop_watcher_metadata(metadata);
        return;
    }
    jobject metadata_obj = new_metadata(env, metadata);
    env->CallVoidMethod(state->callback, state->method, value_obj, metadata_obj);
    env->DeleteLocalRef(metadata_obj);
    g_wui.waterui_drop_watcher_metadata(metadata);
}

void watcher_bool_call(const void *data, bool value, WuiWatcherMetadata *metadata) {
    ScopedEnv scoped;
    if (scoped.env == nullptr) {
        g_wui.waterui_drop_watcher_metadata(metadata);
        return;
    }
    auto *state = static_cast<WatcherCallbackState const *>(data);
    jobject boxed = box_boolean(scoped.env, value);
    invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed, metadata);
    scoped.env->DeleteLocalRef(boxed);
}

void watcher_bool_drop(void *data) {
    ScopedEnv scoped;
    drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_int_call(const void *data, int32_t value, WuiWatcherMetadata *metadata) {
    ScopedEnv scoped;
    if (scoped.env == nullptr) {
        g_wui.waterui_drop_watcher_metadata(metadata);
        return;
    }
    auto *state = static_cast<WatcherCallbackState const *>(data);
    jobject boxed = box_int(scoped.env, value);
    invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed, metadata);
    scoped.env->DeleteLocalRef(boxed);
}

void watcher_int_drop(void *data) {
    ScopedEnv scoped;
    drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_double_call(const void *data, double value, WuiWatcherMetadata *metadata) {
    ScopedEnv scoped;
    if (scoped.env == nullptr) {
        g_wui.waterui_drop_watcher_metadata(metadata);
        return;
    }
    auto *state = static_cast<WatcherCallbackState const *>(data);
    jobject boxed = box_double(scoped.env, value);
    invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed, metadata);
    scoped.env->DeleteLocalRef(boxed);
}

void watcher_double_drop(void *data) {
    ScopedEnv scoped;
    drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_str_call(const void *data, WuiStr value, WuiWatcherMetadata *metadata) {
    ScopedEnv scoped;
    if (scoped.env == nullptr) {
        g_wui.waterui_drop_watcher_metadata(metadata);
        return;
    }
    auto *state = static_cast<WatcherCallbackState const *>(data);
    jstring str = wui_str_to_jstring(scoped.env, value);
    invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), str, metadata);
    scoped.env->DeleteLocalRef(str);
}

void watcher_str_drop(void *data) {
    ScopedEnv scoped;
    drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_styled_str_call(const void *data, WuiStyledStr value, WuiWatcherMetadata *metadata) {
    ScopedEnv scoped;
    if (scoped.env == nullptr) {
        g_wui.waterui_drop_watcher_metadata(metadata);
        return;
    }
    auto *state = static_cast<WatcherCallbackState const *>(data);
    jobject styled = new_styled_str(scoped.env, value);
    invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), styled, metadata);
    scoped.env->DeleteLocalRef(styled);
}

void watcher_styled_str_drop(void *data) {
    ScopedEnv scoped;
    drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_resolved_color_call(const void *data, WuiResolvedColor value, WuiWatcherMetadata *metadata) {
    ScopedEnv scoped;
    if (scoped.env == nullptr) {
        g_wui.waterui_drop_watcher_metadata(metadata);
        return;
    }
    auto *state = static_cast<WatcherCallbackState const *>(data);
    jobject color_obj = new_resolved_color(scoped.env, value);
    invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), color_obj, metadata);
    scoped.env->DeleteLocalRef(color_obj);
}

void watcher_resolved_color_drop(void *data) {
    ScopedEnv scoped;
    drop_watcher_state(scoped.env, static_cast<WatcherCallbackState *>(data));
}

void watcher_anyview_call(const void *data, WuiAnyView *value, WuiWatcherMetadata *metadata) {
    ScopedEnv scoped;
    if (scoped.env == nullptr) {
        g_wui.waterui_drop_watcher_metadata(metadata);
        return;
    }
    auto *state = static_cast<WatcherCallbackState const *>(data);
    jobject boxed = box_long(scoped.env, ptr_to_jlong(value));
    invoke_watcher(scoped.env, const_cast<WatcherCallbackState *>(state), boxed, metadata);
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
    gMetadataClass = init_class("dev/waterui/android/reactive/WuiWatcherMetadata");
    gWatcherStructClass = init_class("dev/waterui/android/runtime/WatcherStruct");

    if (!gBooleanClass || !gIntegerClass || !gDoubleClass || !gLongClass ||
        !gMetadataClass || !gWatcherStructClass) {
        return JNI_ERR;
    }

    gBooleanValueOf = env->GetStaticMethodID(gBooleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    gIntegerValueOf = env->GetStaticMethodID(gIntegerClass, "valueOf", "(I)Ljava/lang/Integer;");
    gDoubleValueOf = env->GetStaticMethodID(gDoubleClass, "valueOf", "(D)Ljava/lang/Double;");
    gLongValueOf = env->GetStaticMethodID(gLongClass, "valueOf", "(J)Ljava/lang/Long;");
    gMetadataCtor = env->GetMethodID(gMetadataClass, "<init>", "(J)V");
    gWatcherStructCtor = env->GetMethodID(gWatcherStructClass, "<init>", "(JJJ)V");

    if (!gBooleanValueOf || !gIntegerValueOf || !gDoubleValueOf || !gLongValueOf ||
        !gMetadataCtor || !gWatcherStructCtor) {
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
Java_dev_waterui_android_runtime_NativeBindings_waterui_1init(
    JNIEnv *, jclass) {
    return ptr_to_jlong(g_wui.waterui_init());
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1main(
    JNIEnv *, jclass) {
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

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1anyview(
    JNIEnv *, jclass, jlong any_view_ptr) {
    WuiAnyView *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    g_wui.waterui_drop_anyview(view);
}

#define WATERUI_ID_EXPORT(javaName, ffiFunc)                                             \
JNIEXPORT jstring JNICALL                                                                \
Java_dev_waterui_android_runtime_NativeBindings_##javaName(                              \
    JNIEnv *env, jclass) {                                                               \
    WuiStr id = g_wui.ffiFunc();                                                         \
    return wui_str_to_jstring(env, id);                                                  \
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

JNIEXPORT jobjectArray JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1layout_1propose(
    JNIEnv *env,
    jclass,
    jlong layout_ptr,
    jobject parent_obj,
    jobjectArray children_array) {
    WuiLayout *layout = jlong_to_ptr<WuiLayout>(layout_ptr);
    WuiProposalSize parent = proposal_from_java(env, parent_obj);
    WuiArray_WuiChildMetadata children = children_from_java(env, children_array);

    WuiArray_WuiProposalSize result = g_wui.waterui_layout_propose(layout, parent, children);
    return proposal_array_to_java(env, result);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1layout_1size(
    JNIEnv *env,
    jclass,
    jlong layout_ptr,
    jobject parent_obj,
    jobjectArray children_array) {
    WuiLayout *layout = jlong_to_ptr<WuiLayout>(layout_ptr);
    WuiProposalSize parent = proposal_from_java(env, parent_obj);
    WuiArray_WuiChildMetadata children = children_from_java(env, children_array);

    WuiSize size = g_wui.waterui_layout_size(layout, parent, children);
    return size_to_java(env, size);
}

JNIEXPORT jobjectArray JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1layout_1place(
    JNIEnv *env,
    jclass,
    jlong layout_ptr,
    jobject bounds_obj,
    jobject proposal_obj,
    jobjectArray children_array) {
    WuiLayout *layout = jlong_to_ptr<WuiLayout>(layout_ptr);
    WuiRect bounds = rect_from_java(env, bounds_obj);
    WuiProposalSize proposal = proposal_from_java(env, proposal_obj);
    WuiArray_WuiChildMetadata children = children_from_java(env, children_array);

    WuiArray_WuiRect placed = g_wui.waterui_layout_place(layout, bounds, proposal, children);
    return rect_array_to_java(env, placed);
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1bool_1watcher(
    JNIEnv *env, jclass, jobject callback) {
    auto *state = create_watcher_state(env, callback);
    return new_watcher_struct(
        env,
        ptr_to_jlong(state),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_bool_call)),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_bool_drop)));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1int_1watcher(
    JNIEnv *env, jclass, jobject callback) {
    auto *state = create_watcher_state(env, callback);
    return new_watcher_struct(
        env,
        ptr_to_jlong(state),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_int_call)),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_int_drop)));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1double_1watcher(
    JNIEnv *env, jclass, jobject callback) {
    auto *state = create_watcher_state(env, callback);
    return new_watcher_struct(
        env,
        ptr_to_jlong(state),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_double_call)),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_double_drop)));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1string_1watcher(
    JNIEnv *env, jclass, jobject callback) {
    auto *state = create_watcher_state(env, callback);
    return new_watcher_struct(
        env,
        ptr_to_jlong(state),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_str_call)),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_str_drop)));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1any_1view_1watcher(
    JNIEnv *env, jclass, jobject callback) {
    auto *state = create_watcher_state(env, callback);
    return new_watcher_struct(
        env,
        ptr_to_jlong(state),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_anyview_call)),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_anyview_drop)));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1styled_1str_1watcher(
    JNIEnv *env, jclass, jobject callback) {
    auto *state = create_watcher_state(env, callback);
    return new_watcher_struct(
        env,
        ptr_to_jlong(state),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_styled_str_call)),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_styled_str_drop)));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1create_1resolved_1color_1watcher(
    JNIEnv *env, jclass, jobject callback) {
    auto *state = create_watcher_state(env, callback);
    return new_watcher_struct(
        env,
        ptr_to_jlong(state),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_resolved_color_call)),
        ptr_to_jlong(reinterpret_cast<void *>(watcher_resolved_color_drop)));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1set_1binding_1bool(
    JNIEnv *, jclass, jlong binding_ptr, jboolean value) {
    auto *binding = jlong_to_ptr<WuiBinding_bool>(binding_ptr);
    g_wui.waterui_set_binding_bool(binding, value == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1binding_1bool(
    JNIEnv *, jclass, jlong binding_ptr) {
    auto *binding = jlong_to_ptr<WuiBinding_bool>(binding_ptr);
    return g_wui.waterui_read_binding_bool(binding) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1binding_1bool(
    JNIEnv *, jclass, jlong binding_ptr) {
    auto *binding = jlong_to_ptr<WuiBinding_bool>(binding_ptr);
    g_wui.waterui_drop_binding_bool(binding);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1binding_1bool(
    JNIEnv *env, jclass, jlong binding_ptr, jobject watcher_obj) {
    auto *binding = jlong_to_ptr<WuiBinding_bool>(binding_ptr);
    WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
    WuiWatcher_bool watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, bool, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
    return ptr_to_jlong(
        g_wui.waterui_watch_binding_bool(
            reinterpret_cast<const Binding_bool *>(binding),
            watcher));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1set_1binding_1int(
    JNIEnv *, jclass, jlong binding_ptr, jint value) {
    auto *binding = jlong_to_ptr<WuiBinding_i32>(binding_ptr);
    g_wui.waterui_set_binding_i32(binding, static_cast<int32_t>(value));
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1binding_1int(
    JNIEnv *, jclass, jlong binding_ptr) {
    auto *binding = jlong_to_ptr<WuiBinding_i32>(binding_ptr);
    return static_cast<jint>(g_wui.waterui_read_binding_i32(binding));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1binding_1int(
    JNIEnv *, jclass, jlong binding_ptr) {
    auto *binding = jlong_to_ptr<WuiBinding_i32>(binding_ptr);
    g_wui.waterui_drop_binding_i32(binding);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1binding_1int(
    JNIEnv *env, jclass, jlong binding_ptr, jobject watcher_obj) {
    auto *binding = jlong_to_ptr<WuiBinding_i32>(binding_ptr);
    WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
    WuiWatcher_i32 watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, int32_t, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
    return ptr_to_jlong(
        g_wui.waterui_watch_binding_i32(
            reinterpret_cast<const Binding_i32 *>(binding),
            watcher));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1set_1binding_1double(
    JNIEnv *, jclass, jlong binding_ptr, jdouble value) {
    auto *binding = jlong_to_ptr<WuiBinding_f64>(binding_ptr);
    g_wui.waterui_set_binding_f64(binding, static_cast<double>(value));
}

JNIEXPORT jdouble JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1binding_1double(
    JNIEnv *, jclass, jlong binding_ptr) {
    auto *binding = jlong_to_ptr<WuiBinding_f64>(binding_ptr);
    return static_cast<jdouble>(g_wui.waterui_read_binding_f64(binding));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1binding_1double(
    JNIEnv *, jclass, jlong binding_ptr) {
    auto *binding = jlong_to_ptr<WuiBinding_f64>(binding_ptr);
    g_wui.waterui_drop_binding_f64(binding);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1binding_1double(
    JNIEnv *env, jclass, jlong binding_ptr, jobject watcher_obj) {
    auto *binding = jlong_to_ptr<WuiBinding_f64>(binding_ptr);
    WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
    WuiWatcher_f64 watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, double, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
    return ptr_to_jlong(
        g_wui.waterui_watch_binding_f64(
            reinterpret_cast<const Binding_f64 *>(binding),
            watcher));
}

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
    WuiWatcher_WuiStr watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, WuiStr, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
    return ptr_to_jlong(
        g_wui.waterui_watch_binding_str(
            reinterpret_cast<const Binding_Str *>(binding),
            watcher));
}

JNIEXPORT jdouble JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1computed_1f64(
    JNIEnv *, jclass, jlong computed_ptr) {
    auto *computed = jlong_to_ptr<WuiComputed_f64>(computed_ptr);
    return g_wui.waterui_read_computed_f64(computed);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1computed_1f64(
    JNIEnv *env, jclass, jlong computed_ptr, jobject watcher_obj) {
    auto *computed = jlong_to_ptr<WuiComputed_f64>(computed_ptr);
    WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
    WuiWatcher_f64 watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, double, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
    return ptr_to_jlong(
        g_wui.waterui_watch_computed_f64(
            reinterpret_cast<const Computed_f64 *>(computed),
            watcher));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1computed_1f64(
    JNIEnv *, jclass, jlong computed_ptr) {
    auto *computed = jlong_to_ptr<WuiComputed_f64>(computed_ptr);
    g_wui.waterui_drop_computed_f64(computed);
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1computed_1i32(
    JNIEnv *, jclass, jlong computed_ptr) {
    auto *computed = jlong_to_ptr<WuiComputed_i32>(computed_ptr);
    return static_cast<jint>(g_wui.waterui_read_computed_i32(computed));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1watch_1computed_1i32(
    JNIEnv *env, jclass, jlong computed_ptr, jobject watcher_obj) {
    auto *computed = jlong_to_ptr<WuiComputed_i32>(computed_ptr);
    WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
    WuiWatcher_i32 watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, int32_t, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
    return ptr_to_jlong(
        g_wui.waterui_watch_computed_i32(
            reinterpret_cast<const Computed_i32 *>(computed),
            watcher));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1computed_1i32(
    JNIEnv *, jclass, jlong computed_ptr) {
    auto *computed = jlong_to_ptr<WuiComputed_i32>(computed_ptr);
    g_wui.waterui_drop_computed_i32(computed);
}

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
    WuiWatcher_WuiStyledStr watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, WuiStyledStr, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
    return ptr_to_jlong(
        g_wui.waterui_watch_computed_styled_str(
            reinterpret_cast<const Computed_StyledStr *>(computed),
            watcher));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1computed_1styled_1str(
    JNIEnv *, jclass, jlong computed_ptr) {
    auto *computed = jlong_to_ptr<WuiComputed_StyledStr>(computed_ptr);
    g_wui.waterui_drop_computed_styled_str(computed);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1font(
    JNIEnv *, jclass, jlong font_ptr) {
    auto *font = jlong_to_ptr<WuiFont>(font_ptr);
    g_wui.waterui_drop_font(font);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1color(
    JNIEnv *, jclass, jlong color_ptr) {
    auto *color = jlong_to_ptr<WuiColor>(color_ptr);
    g_wui.waterui_drop_color(color);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1resolve_1font(
    JNIEnv *, jclass, jlong font_ptr, jlong env_ptr) {
    auto *font = jlong_to_ptr<WuiFont>(font_ptr);
    auto *env = jlong_to_ptr<WuiEnv>(env_ptr);
    return ptr_to_jlong(g_wui.waterui_resolve_font(font, env));
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1read_1computed_1resolved_1font(
    JNIEnv *env, jclass, jlong computed_ptr) {
    auto *computed = jlong_to_ptr<WuiComputed_ResolvedFont>(computed_ptr);
    WuiResolvedFont font = g_wui.waterui_read_computed_resolved_font(computed);
    jclass cls = env->FindClass("dev/waterui/android/runtime/ResolvedFontStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(FI)V");
    jobject obj = env->NewObject(cls, ctor, font.size, static_cast<jint>(font.weight));
    env->DeleteLocalRef(cls);
    return obj;
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
    auto *computed = jlong_to_ptr<WuiComputed_ResolvedColor>(computed_ptr);
    WatcherStructFields fields = watcher_struct_from_java(env, watcher_obj);
    WuiWatcher_WuiResolvedColor watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, WuiResolvedColor, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
    return ptr_to_jlong(
        g_wui.waterui_watch_computed_resolved_color(
            reinterpret_cast<const Computed_ResolvedColor *>(computed),
            watcher));
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
    return ptr_to_jlong(g_wui.waterui_anyviews_get_view(views, static_cast<uintptr_t>(index)));
}

JNIEXPORT jint JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1any_1views_1get_1id(
    JNIEnv *, jclass, jlong handle, jint index) {
    auto *views = jlong_to_ptr<WuiAnyViews>(handle);
    WuiId id = g_wui.waterui_anyviews_get_id(views, static_cast<uintptr_t>(index));
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

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1plain(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiStr str = g_wui.waterui_force_as_plain(view);
    jbyteArray bytes = wui_str_to_byte_array(env, str);
    jclass cls = env->FindClass("dev/waterui/android/runtime/PlainStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([B)V");
    jobject obj = env->NewObject(cls, ctor, bytes);
    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(bytes);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1text(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiText text = g_wui.waterui_force_as_text(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/TextStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
    jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(text.content));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1button(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiButton button = g_wui.waterui_force_as_button(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/ButtonStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(button.label),
        ptr_to_jlong(button.action));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1color(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiColor *color = g_wui.waterui_force_as_color(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/ColorStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
    jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(color));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1text_1field(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiTextField field = g_wui.waterui_force_as_text_field(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/TextFieldStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJI)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(field.label),
        ptr_to_jlong(field.value),
        ptr_to_jlong(field.prompt.content),
        static_cast<jint>(field.keyboard));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1toggle(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiToggle toggle = g_wui.waterui_force_as_toggle(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/ToggleStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(toggle.label),
        ptr_to_jlong(toggle.toggle));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1slider(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiSlider slider = g_wui.waterui_force_as_slider(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/SliderStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJDDJ)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(slider.label),
        ptr_to_jlong(slider.min_value_label),
        ptr_to_jlong(slider.max_value_label),
        slider.range.start,
        slider.range.end,
        ptr_to_jlong(slider.value));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1stepper(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiStepper stepper = g_wui.waterui_force_as_stepper(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/StepperStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJII)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(stepper.value),
        ptr_to_jlong(stepper.step),
        ptr_to_jlong(stepper.label),
        static_cast<jint>(stepper.range.start),
        static_cast<jint>(stepper.range.end));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1progress(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiProgress progress = g_wui.waterui_force_as_progress(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/ProgressStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJJI)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(progress.label),
        ptr_to_jlong(progress.value_label),
        ptr_to_jlong(progress.value),
        static_cast<jint>(progress.style));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1scroll(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiScrollView scroll = g_wui.waterui_force_as_scroll_view(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/ScrollStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IJ)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        static_cast<jint>(scroll.axis),
        ptr_to_jlong(scroll.content));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1layout_1container(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiContainer container = g_wui.waterui_force_as_layout_container(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/ContainerStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JJ)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(container.layout),
        ptr_to_jlong(container.contents));
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1fixed_1container(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiFixedContainer container = g_wui.waterui_force_as_fixed_container(view);
    jlongArray children = any_view_array_to_java(env, container.contents);
    if (children == nullptr) {
        return nullptr;
    }
    jclass cls = env->FindClass("dev/waterui/android/runtime/FixedContainerStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(J[J)V");
    jobject obj = env->NewObject(
        cls,
        ctor,
        ptr_to_jlong(container.layout),
        children);
    env->DeleteLocalRef(children);
    env->DeleteLocalRef(cls);
    return obj;
}

JNIEXPORT jobject JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1force_1as_1dynamic(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    auto *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiDynamic *dynamic = g_wui.waterui_force_as_dynamic(view);
    jclass cls = env->FindClass("dev/waterui/android/runtime/DynamicStruct");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(J)V");
    jobject obj = env->NewObject(cls, ctor, ptr_to_jlong(dynamic));
    env->DeleteLocalRef(cls);
    return obj;
}

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
    WuiWatcher_____WuiAnyView watcher{};
    watcher.data = jlong_to_ptr<void>(fields.data);
    watcher.call = reinterpret_cast<void (*)(const void *, WuiAnyView *, WuiWatcherMetadata *)>(fields.call);
    watcher.drop = reinterpret_cast<void (*)(void *)>(fields.drop);
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
    return static_cast<jint>(g_wui.waterui_renderer_view_preferred_format(renderer));
}

JNIEXPORT jboolean JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1renderer_1view_1render_1cpu(
    JNIEnv *env,
    jclass,
    jlong handle,
    jbyteArray pixel_array,
    jint width,
    jint height,
    jint stride,
    jint format) {
    auto *renderer = jlong_to_ptr<WuiRendererView>(handle);
    jboolean is_copy = JNI_FALSE;
    jbyte *pixels = env->GetByteArrayElements(pixel_array, &is_copy);
    bool ok = g_wui.waterui_renderer_view_render_cpu(
        renderer,
        reinterpret_cast<uint8_t *>(pixels),
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
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
