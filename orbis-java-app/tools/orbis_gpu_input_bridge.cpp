/*
 * GPU display mode input: the runner's native input service is the single input
 * owner; the graphics bundle owns VideoOut. Natives of runner.gpu.NativeInput,
 * registered by the launcher. The F12 console toggle is disabled because no
 * console is visible, and the display service is never started here.
 */
#include "orbis_gpu_input_bridge.h"
#include "orbis_input_packet.h"
#include "orbis_input_service.h"
#include <pthread.h>

namespace {
pthread_mutex_t bridge_lock = PTHREAD_MUTEX_INITIALIZER;
bool active, closed;
struct Guard {
    Guard() { pthread_mutex_lock(&bridge_lock); }
    ~Guard() { pthread_mutex_unlock(&bridge_lock); }
};

void disable() {
    active = false;
    orbis_input_set_desktop_enabled(0);
    orbis_input_set_focus_toggle_enabled(1);
    orbis_input_stop();
}

jint JNICALL start(JNIEnv *env, jclass) {
    if (env->ExceptionCheck()) return -1;
    Guard guard;
    if (closed) return -2;
    if (active) return 0;
    if (orbis_input_start() < 0 || orbis_input_set_focus_toggle_enabled(0) < 0 ||
        orbis_input_set_desktop_enabled(1) < 0 ||
        orbis_input_set_focus(ORBIS_INPUT_FOCUS_DESKTOP) < 0) {
        disable();
        closed = true;
        return -1;
    }
    active = true;
    return 0;
}

/* 0 with a packet, 1 on timeout, negative when stopped or invalid. */
jint JNICALL read_event(JNIEnv *env, jclass, jintArray packet, jint timeout) {
    if (env->ExceptionCheck() || packet == nullptr || timeout < 0 || timeout > 50 ||
        env->GetArrayLength(packet) != ORBIS_INPUT_PACKET_WORDS) return -1;
    { Guard guard; if (!active || closed) return -2; }
    orbis_input_event event{};
    int result = orbis_input_read_event(ORBIS_INPUT_FOCUS_DESKTOP, &event,
                                       static_cast<uint32_t>(timeout));
    if (result != 0) return result;
    if (!orbis_input_event_valid(&event, ORBIS_INPUT_FOCUS_DESKTOP)) return -1;
    jint words[ORBIS_INPUT_PACKET_WORDS];
    orbis_input_pack_event(&event, words);
    env->SetIntArrayRegion(packet, 0, ORBIS_INPUT_PACKET_WORDS, words);
    return env->ExceptionCheck() ? -1 : 0;
}

jint JNICALL set_pointer(JNIEnv *, jclass, jint x, jint y) {
    Guard guard;
    if (!active || closed) return -2;
    return orbis_input_set_pointer(x, y);
}

void JNICALL stop(JNIEnv *, jclass) {
    Guard guard;
    if (active) disable();
    closed = true;
}
}

extern "C" int orbis_gpu_input_bridge_register(JNIEnv *env) {
    if (env == nullptr || env->ExceptionCheck()) return -1;
    jclass loader_class = env->FindClass("java/lang/ClassLoader");
    jobject loader = nullptr;
    jstring name = nullptr;
    jclass bridge = nullptr;
    int result = -1;
    do {
        if (loader_class == nullptr || env->ExceptionCheck()) break;
        jmethodID system = env->GetStaticMethodID(loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        if (system == nullptr || env->ExceptionCheck()) break;
        jmethodID load = env->GetMethodID(loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (load == nullptr || env->ExceptionCheck()) break;
        loader = env->CallStaticObjectMethod(loader_class, system);
        if (loader == nullptr || env->ExceptionCheck()) break;
        name = env->NewStringUTF("runner.gpu.NativeInput");
        if (name == nullptr || env->ExceptionCheck()) break;
        bridge = static_cast<jclass>(env->CallObjectMethod(loader, load, name));
        if (bridge == nullptr || env->ExceptionCheck()) break;
        JNINativeMethod methods[] = {
            {const_cast<char *>("start"), const_cast<char *>("()I"), reinterpret_cast<void *>(start)},
            {const_cast<char *>("readEvent"), const_cast<char *>("([II)I"), reinterpret_cast<void *>(read_event)},
            {const_cast<char *>("setPointer"), const_cast<char *>("(II)I"), reinterpret_cast<void *>(set_pointer)},
            {const_cast<char *>("stop"), const_cast<char *>("()V"), reinterpret_cast<void *>(stop)}
        };
        jint registered = env->RegisterNatives(bridge, methods, 4);
        if (registered == JNI_OK && !env->ExceptionCheck()) result = 0;
    } while (false);
    if (bridge != nullptr) env->DeleteLocalRef(bridge);
    if (name != nullptr) env->DeleteLocalRef(name);
    if (loader != nullptr) env->DeleteLocalRef(loader);
    if (loader_class != nullptr) env->DeleteLocalRef(loader_class);
    return result;
}
