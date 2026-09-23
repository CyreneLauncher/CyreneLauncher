#include <jni.h>
#include <orbis/libkernel.h>
#include <fcntl.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "tools/orbis_runtime.h"
#include "launcher_profile.h"

typedef jint (*create_vm_fn)(JavaVM **, void **, void *);
typedef int (*run_initializers_fn)(void);

enum launcher_probe_mode {
    PROBE_MODE_JAVA = 0,
    PROBE_MODE_ALLOCATOR = 1,
    PROBE_MODE_FATAL_ALLOCATION = 2
};

#ifndef ORBIS_JAVA_BUILD_VERSION
#define ORBIS_JAVA_BUILD_VERSION "dev"
#endif

static const char *ORBis_LOG_PATH = ORBIS_PROFILE_LOG_PATH;
static const char *ORBis_LOG_ROTATED_PATH = ORBIS_PROFILE_ROTATED_LOG_PATH;
static const off_t ORBis_LOG_LIMIT = 256 * 1024;
static int orbis_log_fd = -1;

static int mkdir_path(const char *path) {
    char current[512];
    size_t length = strlen(path);
    if (length == 0 || length >= sizeof(current))
        return -1;
    memcpy(current, path, length + 1);
    for (size_t i = 1; i <= length; ++i) {
        if (current[i] != '/' && current[i] != '\0')
            continue;
        char saved = current[i];
        current[i] = '\0';
        if (mkdir(current, 0777) < 0 && errno != EEXIST)
            return -1;
        current[i] = saved;
    }
    return 0;
}

static int prepare_profile_directories(void) {
    if (mkdir_path("/data/orbis-jdk/logs") < 0)
        return -1;
    if (strcmp(ORBIS_PROFILE_USER_DIR, "/app0") != 0 &&
        mkdir_path(ORBIS_PROFILE_USER_DIR) < 0)
        return -1;
    if (mkdir_path(ORBIS_PROFILE_TMP_DIR) < 0)
        return -1;
    return 0;
}

static void orbis_log_open(void) {
    mkdir("/data/orbis-jdk", 0777);
    mkdir("/data/orbis-jdk/logs", 0777);
    orbis_log_fd = open(ORBis_LOG_PATH, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (orbis_log_fd < 0)
        return;
    off_t size = lseek(orbis_log_fd, 0, SEEK_END);
    if (size >= ORBis_LOG_LIMIT) {
        close(orbis_log_fd);
        orbis_log_fd = -1;
        if (rename(ORBis_LOG_PATH, ORBis_LOG_ROTATED_PATH) < 0)
            unlink(ORBis_LOG_PATH);
        orbis_log_fd = open(ORBis_LOG_PATH,
            O_WRONLY | O_CREAT | O_TRUNC, 0666);
    }
}

static void orbis_log_line(const char *level, const char *component,
                           const char *format, ...) {
    if (orbis_log_fd < 0)
        return;
    char message[768];
    va_list args;
    va_start(args, format);
    int message_length = vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    if (message_length < 0)
        return;
    if (message_length >= (int)sizeof(message))
        message_length = sizeof(message) - 1;

    time_t now = time(NULL);
    struct tm utc;
    memset(&utc, 0, sizeof(utc));
    gmtime_r(&now, &utc);
    char line[1024];
    int line_length = snprintf(line, sizeof(line),
        "%04d-%02d-%02dT%02d:%02d:%02dZ %s %s %.*s\n",
        utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday,
        utc.tm_hour, utc.tm_min, utc.tm_sec, level, component,
        message_length, message);
    if (line_length <= 0)
        return;
    if (line_length > (int)sizeof(line))
        line_length = sizeof(line);
    off_t size = lseek(orbis_log_fd, 0, SEEK_END);
    if (size >= 0 && size + line_length > ORBis_LOG_LIMIT) {
        close(orbis_log_fd);
        orbis_log_fd = -1;
        if (rename(ORBis_LOG_PATH, ORBis_LOG_ROTATED_PATH) < 0)
            unlink(ORBis_LOG_PATH);
        orbis_log_fd = open(ORBis_LOG_PATH,
            O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (orbis_log_fd < 0)
            return;
    }
    write(orbis_log_fd, line, line_length);
}

static void orbis_log_close(void) {
    if (orbis_log_fd >= 0) {
        close(orbis_log_fd);
        orbis_log_fd = -1;
    }
}

static void park_after_error(void) {
    for (;;) {
        sceKernelUsleep(1000000);
    }
}

static int is_control_argument(const char *arg) {
    return arg && (strncmp(arg, "--main=", 7) == 0 ||
                   strcmp(arg, "--no-park") == 0 ||
                   strcmp(arg, "--allocator-probe") == 0 ||
                   strcmp(arg, "--fatal-allocation-probe") == 0);
}

static int parse_launcher_options(int argc, char **argv, char *main_class,
                                  size_t main_class_size, int *park,
                                  enum launcher_probe_mode *probe_mode) {
    const char *default_class = ORBIS_PROFILE_MAIN_CLASS;
    size_t default_length = strlen(default_class);
    if (default_length + 1 > main_class_size)
        return -1;
    memcpy(main_class, default_class, default_length + 1);
    *park = 1;
    *probe_mode = PROBE_MODE_JAVA;
    for (int i = 1; i < argc; ++i) {
        if (strncmp(argv[i], "--main=", 7) == 0) {
            const char *class_name = argv[i] + 7;
            size_t length = strlen(class_name);
            if (length == 0 || length + 1 > main_class_size)
                return -1;
            memcpy(main_class, class_name, length + 1);
            for (size_t j = 0; j < length; ++j)
                if (main_class[j] == '.') main_class[j] = '/';
        } else if (strcmp(argv[i], "--no-park") == 0) {
            *park = 0;
        } else if (strcmp(argv[i], "--allocator-probe") == 0) {
            *probe_mode = PROBE_MODE_ALLOCATOR;
        } else if (strcmp(argv[i], "--fatal-allocation-probe") == 0) {
            *probe_mode = PROBE_MODE_FATAL_ALLOCATION;
        }
    }
    return 0;
}

static void debug_text(const char *text) {
    sceKernelDebugOutText(0, text);
}

static void debug_hex(const char *label, uint32_t value) {
    char digits[12] = "0x00000000\n";
    for (unsigned i = 0; i < 8; ++i)
        digits[2 + i] = "0123456789abcdef"[(value >> (28 - 4 * i)) & 15];
    debug_text(label);
    debug_text(digits);
}

static int run_allocator_probe(void) {
    struct orbis_alloc_result result;
    const size_t limit = 48U * 1024U * 1024U;
    int error = orbis_runtime_allocator_probe(64U * 1024U, limit, &result);
    orbis_log_line(error == 0 ? "INFO" : "ERROR", "allocator",
        "configured=%llu extended=%u allocated=%llu released=%llu error=%d",
        (unsigned long long)result.configured_heap_size,
        result.extended_allocation,
        (unsigned long long)result.allocated_bytes,
        (unsigned long long)result.released_bytes, error);
    if (error == 0 && result.allocated_bytes == limit &&
        result.released_bytes == limit) {
        debug_text("orbis-java: allocator probe PASS\n");
        orbis_log_line("INFO", "allocator", "PASS");
        return 0;
    }
    debug_text("orbis-java: allocator probe FAIL\n");
    orbis_log_line("ERROR", "allocator", "FAIL");
    return 1;
}

static void run_fatal_allocation_probe(void) {
    debug_text("orbis-java: fatal allocation probe primary marker\n");
    orbis_log_line("ERROR", "allocator", "fatal allocation primary marker");
    volatile size_t impossible = SIZE_MAX;
    void *allocation = malloc(impossible);
    if (allocation != NULL) {
        free(allocation);
        orbis_log_line("ERROR", "allocator", "impossible allocation succeeded");
    }
    orbis_log_close();
    orbis_runtime_exit(86);
}

static void probe_app_jar(void) {
    int fd = open("/app0/app.jar", O_RDONLY);
    debug_hex("orbis-java: app.jar open result: ", (uint32_t)fd);
    if (fd < 0)
        return;

    unsigned char header[4] = {0, 0, 0, 0};
    ssize_t count = read(fd, header, sizeof(header));
    close(fd);
    debug_hex("orbis-java: app.jar read result: ", (uint32_t)count);
    if (count == 4) {
        uint32_t signature = ((uint32_t)header[0] << 24) |
            ((uint32_t)header[1] << 16) |
            ((uint32_t)header[2] << 8) | (uint32_t)header[3];
        debug_hex("orbis-java: app.jar signature: ", signature);
    }
}

static void debug_java_string(const char *label, JNIEnv *env, jstring value) {
    if (value == NULL) {
        orbis_log_line("WARN", "launcher", "%s<null>", label);
        debug_text(label);
        debug_text("<null>\n");
        return;
    }
    const char *text = env->GetStringUTFChars(value, NULL);
    if (text == NULL) {
        orbis_log_line("WARN", "launcher", "%s<unavailable>", label);
        debug_text(label);
        debug_text("<unavailable>\n");
        return;
    }
    orbis_log_line("INFO", "launcher", "%s%s", label, text);
    debug_text(label);
    debug_text(text);
    debug_text("\n");
    env->ReleaseStringUTFChars(value, text);
}

static void debug_pending_exception(JNIEnv *env) {
    jthrowable exception = env->ExceptionOccurred();
    if (exception == NULL)
        return;

    env->ExceptionClear();
    jclass throwable_class = env->FindClass("java/lang/Throwable");
    if (throwable_class == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: exception text unavailable\n");
        return;
    }
    jmethodID to_string = env->GetMethodID(
        throwable_class, "toString", "()Ljava/lang/String;");
    jmethodID get_cause = env->GetMethodID(
        throwable_class, "getCause", "()Ljava/lang/Throwable;");
    if (to_string == NULL || get_cause == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: exception toString unavailable\n");
        return;
    }

    jthrowable current = exception;
    const char *labels[] = {
        "orbis-java: exception: ",
        "orbis-java: exception cause: ",
        "orbis-java: exception cause cause: ",
        "orbis-java: exception cause cause cause: "
    };
    for (unsigned depth = 0; current != NULL && depth < 4; ++depth) {
        jstring text = static_cast<jstring>(
            env->CallObjectMethod(current, to_string));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            debug_text("orbis-java: exception toString call failed\n");
            return;
        }
        debug_java_string(labels[depth], env, text);
        current = static_cast<jthrowable>(
            env->CallObjectMethod(current, get_cause));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            debug_text("orbis-java: exception getCause call failed\n");
            return;
        }
    }
}

static void debug_system_property(JNIEnv *env, const char *key) {
    jclass system_class = env->FindClass("java/lang/System");
    if (system_class == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: System class unavailable\n");
        return;
    }
    jmethodID get_property = env->GetStaticMethodID(
        system_class, "getProperty",
        "(Ljava/lang/String;)Ljava/lang/String;");
    if (get_property == NULL || env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: System.getProperty unavailable\n");
        return;
    }
    jstring key_string = env->NewStringUTF(key);
    jstring value = static_cast<jstring>(env->CallStaticObjectMethod(
        system_class, get_property, key_string));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        debug_text("orbis-java: System.getProperty failed\n");
        return;
    }
    debug_java_string("orbis-java: property: ", env, value);
}

static int check_exception(JNIEnv *env) {
    if (!env->ExceptionCheck())
        return 0;
    debug_text("orbis-java: Java exception pending\n");
    orbis_log_line("ERROR", "launcher", "Java exception pending");
    debug_pending_exception(env);
    env->ExceptionDescribe();
    return 1;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    if (prepare_profile_directories() < 0) {
        debug_text("orbis-java: profile directory setup failed\n");
        return 3;
    }
    char main_class_name[256];
    int park = 1;
    enum launcher_probe_mode probe_mode;
    if (parse_launcher_options(argc, argv, main_class_name,
                               sizeof(main_class_name), &park,
                               &probe_mode) < 0) {
        debug_text("orbis-java: invalid launcher options\n");
        return 2;
    }
    orbis_log_open();
    orbis_log_line("INFO", "launcher", "loader build %s",
                   ORBIS_JAVA_BUILD_VERSION);
    debug_text("orbis-java: loader build ");
    debug_text(ORBIS_JAVA_BUILD_VERSION);
    debug_text(" (generic main and exit modes)\n");
    debug_text("orbis-java: entered main\n");
    orbis_log_line("INFO", "launcher", "entered main");
    if (probe_mode == PROBE_MODE_ALLOCATOR) {
        int probe_result = run_allocator_probe();
        orbis_log_close();
        if (park) park_after_error();
        return probe_result;
    }
    if (probe_mode == PROBE_MODE_FATAL_ALLOCATION)
        run_fatal_allocation_probe();
    probe_app_jar();
    debug_text("orbis-java: before sceKernelLoadStartModule\n");
    int start_result = 0;
    int32_t jvm_handle = (int32_t)sceKernelLoadStartModule(
        "/app0/sce_module/libjvm.prx", 0, NULL, 0, NULL, &start_result);
    debug_hex("orbis-java: native load result: ", (uint32_t)jvm_handle);
    debug_hex("orbis-java: module start result: ", (uint32_t)start_result);
    orbis_log_line("INFO", "launcher", "JVM load=%d start=%d",
                   jvm_handle, start_result);
    if (jvm_handle < 0) {
        debug_text("orbis-java: native loader rejected JVM module; VM not started\n");
        park_after_error();
    }

    debug_text("orbis-java: before JVM initializers\n");
    void *initializer_entry = NULL;
    int32_t initializer_symbol_result = sceKernelDlsym(
        jvm_handle, "orbis_run_initializers", &initializer_entry);
    debug_hex("orbis-java: initializer symbol result: ",
              (uint32_t)initializer_symbol_result);
    if (initializer_symbol_result < 0 || initializer_entry == NULL) {
        debug_text("orbis-java: JVM initializer lookup failed\n");
        park_after_error();
    }
    int initializer_count =
        reinterpret_cast<run_initializers_fn>(initializer_entry)();
    debug_hex("orbis-java: initializer count: ", (uint32_t)initializer_count);
    if (initializer_count < 0) {
        debug_text("orbis-java: JVM initializer recursion detected\n");
        park_after_error();
    }

    debug_text("orbis-java: before native symbol lookup\n");
    void *entry = NULL;
    int32_t symbol_result = sceKernelDlsym(jvm_handle, "JNI_CreateJavaVM", &entry);
    debug_hex("orbis-java: native symbol result: ", (uint32_t)symbol_result);
    create_vm_fn create_vm = symbol_result < 0 ? NULL :
        reinterpret_cast<create_vm_fn>(entry);
    debug_text("orbis-java: after native symbol lookup\n");
    if (create_vm == NULL) {
        debug_text("orbis-java: JNI_CreateJavaVM lookup failed\n");
        park_after_error();
    }

    JavaVMOption options[ORBIS_PROFILE_VM_OPTION_COUNT];
    for (int i = 0; i < ORBIS_PROFILE_VM_OPTION_COUNT; ++i)
        options[i].optionString = const_cast<char *>(orbis_profile_vm_options[i]);

    JavaVMInitArgs vm_args;
    vm_args.version = JNI_VERSION_1_8;
    vm_args.nOptions = ORBIS_PROFILE_VM_OPTION_COUNT;
    vm_args.options = options;
    vm_args.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    debug_text("orbis-java: before JNI_CreateJavaVM\n");
    orbis_log_line("INFO", "launcher", "before JNI_CreateJavaVM");
    jint result = create_vm(&vm, (void **)&env, &vm_args);
    debug_text("orbis-java: after JNI_CreateJavaVM\n");
    orbis_log_line("INFO", "launcher", "after JNI_CreateJavaVM result=%d", result);
    if (result != JNI_OK) {
        debug_hex("orbis-java: JNI_CreateJavaVM result: ", (uint32_t)result);
        debug_text("orbis-java: JNI_CreateJavaVM failed\n");
        park_after_error();
    }

    debug_system_property(env, "java.home");
    debug_system_property(env, "java.class.path");

    debug_text("orbis-java: before FindClass main class\n");
    jclass main_class = env->FindClass(main_class_name);
    debug_text("orbis-java: after FindClass main class\n");
    orbis_log_line("INFO", "launcher", "main class=%s", main_class_name);
    int main_class_exception = check_exception(env);
    if (main_class == NULL || main_class_exception) {
        debug_text("orbis-java: FindClass demo/Main failed\n");
        vm->DestroyJavaVM();
        return 1;
    }

    debug_text("orbis-java: before GetStaticMethodID main\n");
    jmethodID main_method = env->GetStaticMethodID(
        main_class, "main", "([Ljava/lang/String;)V");
    debug_text("orbis-java: after GetStaticMethodID main\n");
    int main_method_exception = check_exception(env);
    if (main_method == NULL || main_method_exception) {
        debug_text("orbis-java: GetStaticMethodID main failed\n");
        vm->DestroyJavaVM();
        return 1;
    }

    debug_text("orbis-java: before FindClass String\n");
    jclass string_class = env->FindClass("java/lang/String");
    debug_text("orbis-java: after FindClass String\n");
    int string_class_exception = check_exception(env);
    if (string_class == NULL || string_class_exception) {
        debug_text("orbis-java: FindClass String failed\n");
        vm->DestroyJavaVM();
        return 1;
    }
    debug_text("orbis-java: before NewObjectArray\n");
    int java_argc = 0;
    for (int i = 1; i < argc; ++i)
        if (!is_control_argument(argv[i])) ++java_argc;
    jobjectArray java_args = env->NewObjectArray(java_argc, string_class, NULL);
    debug_text("orbis-java: after NewObjectArray\n");

    int java_arg_index = 0;
    for (int i = 1; i < argc; i++) {
        if (is_control_argument(argv[i])) continue;
        jstring value = env->NewStringUTF(argv[i]);
        env->SetObjectArrayElement(java_args, java_arg_index++, value);
    }

    debug_text("orbis-java: before CallStaticVoidMethod main\n");
    env->CallStaticVoidMethod(main_class, main_method, java_args);
    debug_text("orbis-java: after CallStaticVoidMethod main\n");
    int failed = check_exception(env);
    orbis_log_line(failed ? "ERROR" : "INFO", "launcher",
                   "Java main returned failed=%d", failed);

    debug_text("orbis-java: before DestroyJavaVM\n");
    orbis_log_line("INFO", "launcher", "before DestroyJavaVM");
    vm->DestroyJavaVM();
    debug_text("orbis-java: after DestroyJavaVM\n");
    orbis_log_line("INFO", "launcher", "after DestroyJavaVM");
    printf("orbis-java: Java main completed (failed=%d)\n", failed);
    orbis_log_line("INFO", "launcher", "Java main completed failed=%d", failed);
    orbis_log_close();
    if (park)
        park_after_error();
    return failed ? 1 : 0;
}
