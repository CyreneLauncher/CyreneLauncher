#include "orbis_desktop_bridge.h"
#include "orbis_display_service.h"
#include "orbis_input_packet.h"
#include "orbis_input_service.h"
#include <pthread.h>
#include <stdlib.h>

namespace {
constexpr jint kVersion = ORBIS_INPUT_PACKET_VERSION, kWords = ORBIS_INPUT_PACKET_WORDS;
pthread_mutex_t bridge_lock = PTHREAD_MUTEX_INITIALIZER;
jint *scratch;
bool active, closed;
jlong last_sequence;
struct Guard {
    Guard() { pthread_mutex_lock(&bridge_lock); }
    ~Guard() { pthread_mutex_unlock(&bridge_lock); }
};
bool array_length(JNIEnv *env, jintArray array, jsize expected) {
    if (env->ExceptionCheck() || array == nullptr) return false;
    jsize length = env->GetArrayLength(array);
    return !env->ExceptionCheck() && length == expected;
}
jint JNICALL query(JNIEnv *env, jclass, jintArray capabilities) {
    if (!array_length(env, capabilities, 6)) return -1;
    const jint values[] = {kVersion, 1280, 720, 1280, 1, kWords};
    env->SetIntArrayRegion(capabilities, 0, 6, values);
    return env->ExceptionCheck() ? -1 : 0;
}
void disable() {
    active = false;
    orbis_input_set_desktop_enabled(0);
    orbis_input_set_focus(ORBIS_INPUT_FOCUS_CONSOLE);
    orbis_display_set_desktop_enabled(0);
    free(scratch);
    scratch = nullptr;
}
jint JNICALL start(JNIEnv *env, jclass) {
    if (env->ExceptionCheck()) return -1;
    Guard guard;
    if (closed) return -2;
    if (active) return 0;
    scratch = static_cast<jint *>(malloc(ORBIS_DISPLAY_PIXEL_COUNT * sizeof(jint)));
    if (scratch == nullptr) return -1;
    // Service ownership stays with the launcher's console lifetime. Calling
    // start on an existing display verifies availability without restarting it.
    if (orbis_display_start() < 0 || orbis_input_start() < 0 ||
        orbis_display_set_desktop_enabled(1) < 0 ||
        orbis_input_set_desktop_enabled(1) < 0 ||
        orbis_input_set_focus(ORBIS_INPUT_FOCUS_DESKTOP) < 0) {
        disable();
        closed = true;
        return -1;
    }
    active = true;
    return 0;
}
jint JNICALL submit(JNIEnv *env, jclass, jintArray frame, jlong sequence) {
    if (!array_length(env, frame, ORBIS_DISPLAY_PIXEL_COUNT)) return -1;
    Guard guard;
    if (!active || closed) return -2;
    if (sequence <= 0 || sequence <= last_sequence) return -1;
    env->GetIntArrayRegion(frame, 0, ORBIS_DISPLAY_PIXEL_COUNT, scratch);
    if (env->ExceptionCheck()) return -1;
    int result = orbis_display_submit_argb(reinterpret_cast<uint32_t *>(scratch),
        ORBIS_DISPLAY_PIXEL_COUNT, 1280, 720, 1280, static_cast<uint64_t>(sequence));
    if (result == 0) last_sequence = sequence;
    return result;
}
jint JNICALL read_event(JNIEnv *env, jclass, jintArray packet, jint timeout) {
    if (timeout < 0 || timeout > 50 || !array_length(env, packet, kWords)) return -1;
    { Guard guard; if (!active || closed) return -2; }
    orbis_input_event event{};
    int result = orbis_input_read_event(ORBIS_INPUT_FOCUS_DESKTOP, &event,
                                       static_cast<uint32_t>(timeout));
    Guard guard;
    if (!active || closed) return -2;
    if (result != 0) return result;
    if (!orbis_input_event_valid(&event, ORBIS_INPUT_FOCUS_DESKTOP)) return -1;
    jint words[kWords];
    orbis_input_pack_event(&event, words);
    env->SetIntArrayRegion(packet, 0, kWords, words);
    return env->ExceptionCheck() ? -1 : 0;
}
void JNICALL stop(JNIEnv *, jclass) {
    Guard guard;
    closed = true;
    disable();
}
}
extern "C" int orbis_desktop_bridge_register(JNIEnv *env) {
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
        name = env->NewStringUTF("runner.desktop.NativeDesktopBridge");
        if (name == nullptr || env->ExceptionCheck()) break;
        bridge = static_cast<jclass>(env->CallObjectMethod(loader, load, name));
        if (bridge == nullptr || env->ExceptionCheck()) break;
        JNINativeMethod methods[] = {
            {const_cast<char *>("query"), const_cast<char *>("([I)I"), reinterpret_cast<void *>(query)},
            {const_cast<char *>("start"), const_cast<char *>("()I"), reinterpret_cast<void *>(start)},
            {const_cast<char *>("submit"), const_cast<char *>("([IJ)I"), reinterpret_cast<void *>(submit)},
            {const_cast<char *>("readEvent"), const_cast<char *>("([II)I"), reinterpret_cast<void *>(read_event)},
            {const_cast<char *>("stop"), const_cast<char *>("()V"), reinterpret_cast<void *>(stop)}
        };
        jint registered = env->RegisterNatives(bridge, methods, 5);
        if (registered == JNI_OK && !env->ExceptionCheck()) result = 0;
    } while (false);
    if (bridge != nullptr) env->DeleteLocalRef(bridge);
    if (name != nullptr) env->DeleteLocalRef(name);
    if (loader != nullptr) env->DeleteLocalRef(loader);
    if (loader_class != nullptr) env->DeleteLocalRef(loader_class);
    return result;
}
