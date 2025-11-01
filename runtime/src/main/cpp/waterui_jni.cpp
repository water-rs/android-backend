#include <jni.h>
#include <cstdlib>
#include <cstring>
#include <string>

#include "../../../../ffi/waterui.h"

namespace {

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

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1init(
    JNIEnv *, jclass) {
    return ptr_to_jlong(waterui_init());
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1main(
    JNIEnv *, jclass) {
    return ptr_to_jlong(waterui_main());
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1main_1reloadble(
    JNIEnv *, jclass) {
    return ptr_to_jlong(waterui_main_reloadble());
}

JNIEXPORT jstring JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1view_1id(
    JNIEnv *env, jclass, jlong any_view_ptr) {
    WuiAnyView *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiStr id = waterui_view_id(view);
    return wui_str_to_jstring(env, id);
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1view_1body(
    JNIEnv *, jclass, jlong any_view_ptr, jlong env_ptr) {
    WuiAnyView *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    WuiEnv *env = jlong_to_ptr<WuiEnv>(env_ptr);
    return ptr_to_jlong(waterui_view_body_with_env(view, env));
}

JNIEXPORT jlong JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1clone_1env(
    JNIEnv *, jclass, jlong env_ptr) {
    WuiEnv *env = jlong_to_ptr<WuiEnv>(env_ptr);
    return ptr_to_jlong(waterui_clone_env(env));
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1env(
    JNIEnv *, jclass, jlong env_ptr) {
    WuiEnv *env = jlong_to_ptr<WuiEnv>(env_ptr);
    waterui_drop_env(env);
}

JNIEXPORT void JNICALL
Java_dev_waterui_android_runtime_NativeBindings_waterui_1drop_1anyview(
    JNIEnv *, jclass, jlong any_view_ptr) {
    WuiAnyView *view = jlong_to_ptr<WuiAnyView>(any_view_ptr);
    waterui_drop_anyview(view);
}

#define WATERUI_ID_EXPORT(javaName, ffiFunc)                                             \
JNIEXPORT jstring JNICALL                                                                \
Java_dev_waterui_android_runtime_NativeBindings_##javaName(                              \
    JNIEnv *env, jclass) {                                                               \
    WuiStr id = ffiFunc();                                                               \
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

    WuiArray_WuiProposalSize result = waterui_layout_propose(layout, parent, children);
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

    WuiSize size = waterui_layout_size(layout, parent, children);
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

    WuiArray_WuiRect placed = waterui_layout_place(layout, bounds, proposal, children);
    return rect_array_to_java(env, placed);
}

} // extern "C"
