/*
 * Orbis LWJGL platform PRX: EGL display, one full-screen window surface, one desktop
 * OpenGL context, presentation and GL function lookup for the Java GLFW facade.
 *
 * Natives of orbis.graphics.NativePlatform. All calls come from Java threads; the
 * facade serialises window/context lifecycle. Every failure leaves a stage and EGL
 * error in nLastError() and releases what the failed call acquired. Mesa's
 * environment knobs are seeded here because this PRX reads its own environment.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <jni.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* libSceGnmDriver: the system will not suspend a process that submitted GPU work until it calls this. */
int32_t sceGnmSubmitDone(void);

#define PLATFORM_WINDOW 1 /* the only window handle */

static pthread_mutex_t platform_lock = PTHREAD_MUTEX_INITIALIZER;
static EGLDisplay display = EGL_NO_DISPLAY;
static EGLConfig config;
static EGLSurface surface = EGL_NO_SURFACE;
static EGLContext context = EGL_NO_CONTEXT;
static EGLint surface_width, surface_height;
static char last_error[512] = "none";

static void record(const char *format, ...) {
    va_list arguments;
    va_start(arguments, format);
    vsnprintf(last_error, sizeof(last_error), format, arguments);
    va_end(arguments);
}

static void record_egl(const char *stage) {
    record("%s failed (EGL error 0x%04x)", stage, (unsigned)eglGetError());
}

static void release_window_locked(void) {
    if (display == EGL_NO_DISPLAY) return;
    if (context != EGL_NO_CONTEXT || surface != EGL_NO_SURFACE)
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
    if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
    context = EGL_NO_CONTEXT;
    surface = EGL_NO_SURFACE;
    surface_width = surface_height = 0;
}

static int valid_window_locked(jlong window) {
    if (window != PLATFORM_WINDOW || surface == EGL_NO_SURFACE || context == EGL_NO_CONTEXT) {
        record("invalid window handle %lld", (long long)window);
        return 0;
    }
    return 1;
}

/* orbis-compat's diagnostics (thread stack floors, failed thread creation with callers) are
 * silent until a logger is installed; they go to "<mesa log>.compat", or stderr without one. */
void orbis_set_log(void (*fn)(const char *fmt, va_list ap));
static FILE *compat_log;
static pthread_mutex_t compat_log_lock = PTHREAD_MUTEX_INITIALIZER;

static void compat_logger(const char *fmt, va_list ap) {
    pthread_mutex_lock(&compat_log_lock);
    FILE *out = compat_log ? compat_log : stderr;
    fputs("orbis-compat: ", out);
    vfprintf(out, fmt, ap);
    fputc('\n', out);
    fflush(out);
    pthread_mutex_unlock(&compat_log_lock);
}

static void install_compat_logger(const char *mesa_log_file) {
    if (mesa_log_file && *mesa_log_file && strcmp(mesa_log_file, "stderr") != 0) {
        char path[512];
        if (snprintf(path, sizeof(path), "%s.compat", mesa_log_file) < (int)sizeof(path))
            compat_log = fopen(path, "w");
    }
    orbis_set_log(compat_logger);
}

/* Diagnostics at Mesa's thread creation: linked with --wrap=pthread_create, so every thread
 * Mesa starts passes through here before orbis-compat's interposer. A failure is logged with
 * what the process looked like at that moment, then retried (and a successful retry used). */
int __real_pthread_create(pthread_t *thread, const pthread_attr_t *attr,
                          void *(*start)(void *), void *arg);
int32_t sceKernelAvailableFlexibleMemorySize(size_t *size) __asm__("sceKernelAvailableFlexibleMemorySize");
int sceKernelUsleep(unsigned int microseconds);

static void diag(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    compat_logger(fmt, ap);
    va_end(ap);
}

static int pool_headroom(void) {
    enum { CAP = 4096 };
    static pthread_mutex_t probe[CAP];
    int n = 0;
    while (n < CAP && pthread_mutex_init(&probe[n], NULL) == 0) n++;
    for (int i = 0; i < n; i++) pthread_mutex_destroy(&probe[i]);
    return n;
}

/* A thread that only creates threads on others' behalf, started before Mesa initialises. Under
 * Parallel GC, creation requested by the render thread fails with EAGAIN while JVM-thread
 * requests succeed; a failed request is handed to this thread to tell the creator apart. */
static pthread_mutex_t creator_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t creator_wake = PTHREAD_COND_INITIALIZER;
static int creator_started;
static struct {
    int pending, done, rc;
    pthread_t *thread;
    const pthread_attr_t *attr;
    void *(*start)(void *);
    void *arg;
} creator_request;

static void *creator_main(void *unused) {
    (void)unused;
    pthread_mutex_lock(&creator_lock);
    for (;;) {
        while (!creator_request.pending) pthread_cond_wait(&creator_wake, &creator_lock);
        creator_request.rc = __real_pthread_create(creator_request.thread, creator_request.attr,
                                                   creator_request.start, creator_request.arg);
        creator_request.pending = 0;
        creator_request.done = 1;
        pthread_cond_broadcast(&creator_wake);
    }
    return NULL;
}

static void start_creator(void) {
    pthread_mutex_lock(&creator_lock);
    if (!creator_started) {
        pthread_t creator;
        int rc = __real_pthread_create(&creator, NULL, creator_main, NULL);
        creator_started = rc == 0 ? 1 : -1;
        pthread_mutex_unlock(&creator_lock);
        diag("thread diag: creator thread start rc=%d", rc);
        return;
    }
    pthread_mutex_unlock(&creator_lock);
}

static int create_via_creator(pthread_t *thread, const pthread_attr_t *attr, void *(*start)(void *), void *arg) {
    pthread_mutex_lock(&creator_lock);
    if (creator_started != 1) { pthread_mutex_unlock(&creator_lock); return -1; }
    while (creator_request.pending) pthread_cond_wait(&creator_wake, &creator_lock);
    creator_request.thread = thread;
    creator_request.attr = attr;
    creator_request.start = start;
    creator_request.arg = arg;
    creator_request.done = 0;
    creator_request.pending = 1;
    pthread_cond_broadcast(&creator_wake);
    while (!creator_request.done) pthread_cond_wait(&creator_wake, &creator_lock);
    int rc = creator_request.rc;
    pthread_mutex_unlock(&creator_lock);
    return rc;
}

int __wrap_pthread_create(pthread_t *thread, const pthread_attr_t *attr,
                          void *(*start)(void *), void *arg) {
    int rc = __real_pthread_create(thread, attr, start, arg);
    if (rc == 0) return 0;
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    size_t stack = 0;
    if (attr) pthread_attr_getstacksize(attr, &stack);
    size_t flexible = 0;
    sceKernelAvailableFlexibleMemorySize(&flexible);
    diag("thread diag: pthread_create failed rc=%d at %ld.%03ld s (monotonic), caller %p, attr %s stack %zu KiB, "
         "pool headroom %d mutexes (cap 4096), flexible %zu MiB",
         rc, (long)now.tv_sec, now.tv_nsec / 1000000, (void *)pthread_self(), attr ? "set" : "NULL",
         stack / 1024, pool_headroom(), flexible >> 20);
    rc = create_via_creator(thread, attr, start, arg);
    diag("thread diag: same request through the creator thread: rc=%d", rc);
    if (rc == 0) return 0;
    static const unsigned delays[] = {0, 1000, 10000, 100000, 500000};
    for (unsigned i = 0; i < sizeof(delays) / sizeof(delays[0]); i++) {
        if (delays[i]) sceKernelUsleep(delays[i]);
        rc = __real_pthread_create(thread, attr, start, arg);
        diag("thread diag: retry after %u us: rc=%d", delays[i], rc);
        if (rc == 0) return 0;
    }
    rc = __real_pthread_create(thread, NULL, start, arg);
    diag("thread diag: retry with default attributes: rc=%d", rc);
    return rc;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_8;
}

/* Returns 0, or -1 with nLastError(). Idempotent while initialised. */
JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nInitialize(
        JNIEnv *env, jclass klass, jstring process_name, jint arena_mib, jstring mesa_log_file) {
    (void)klass;
    jint result = -1;
    pthread_mutex_lock(&platform_lock);
    if (display != EGL_NO_DISPLAY) { result = 0; goto done; }
    if (arena_mib < 0) { record("invalid Mesa arena size %d MiB", (int)arena_mib); goto done; }
    const char *name = process_name ? (*env)->GetStringUTFChars(env, process_name, NULL) : NULL;
    if (process_name && !name) { record("process name unavailable"); goto done; }
    /* Mesa's driconf path passes the process name to regexec; never leave it unset. */
    int env_failed = setenv("MESA_PROCESS_NAME", name && *name ? name : "orbis-lwjgl", 0) != 0;
    if (name) (*env)->ReleaseStringUTFChars(env, process_name, name);
    char arena[16];
    if (!env_failed && arena_mib > 0) {
        snprintf(arena, sizeof(arena), "%d", (int)arena_mib);
        env_failed = setenv("ORBIS_ARENA_MIB", arena, 1) != 0;
    }
    /* Mesa reads MESA_LOG_FILE once, on its first log line, so it must be set before EGL starts.
     * A value already in the environment wins. */
    const char *log_file = mesa_log_file ? (*env)->GetStringUTFChars(env, mesa_log_file, NULL) : NULL;
    if (!env_failed && log_file && *log_file)
        env_failed = setenv("MESA_LOG_FILE", log_file, 0) != 0;
    install_compat_logger(log_file);
    start_creator();
    if (log_file) (*env)->ReleaseStringUTFChars(env, mesa_log_file, log_file);
    if (env_failed) { record("cannot seed Mesa environment"); goto done; }
    EGLDisplay candidate = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (candidate == EGL_NO_DISPLAY) { record_egl("eglGetDisplay"); goto done; }
    EGLint major, minor;
    if (!eglInitialize(candidate, &major, &minor)) { record_egl("eglInitialize"); goto done; }
    if (!eglBindAPI(EGL_OPENGL_API)) {
        record_egl("eglBindAPI(EGL_OPENGL_API)");
        eglTerminate(candidate);
        goto done;
    }
    display = candidate;
    record("none");
    result = 0;
done:
    pthread_mutex_unlock(&platform_lock);
    return result;
}

/* Creates the single full-screen window and its context; returns the handle or 0. */
JNIEXPORT jlong JNICALL Java_orbis_graphics_NativePlatform_nCreateWindow(
        JNIEnv *env, jclass klass, jint gl_major, jint gl_minor, jboolean core_profile,
        jboolean forward_compatible, jint depth_bits, jint stencil_bits) {
    (void)env; (void)klass;
    jlong result = 0;
    pthread_mutex_lock(&platform_lock);
    if (display == EGL_NO_DISPLAY) { record("platform is not initialised"); goto done; }
    if (surface != EGL_NO_SURFACE) { record("only one window is supported"); goto done; }
    if (gl_major < 1 || gl_minor < 0 || depth_bits < 0 || stencil_bits < 0) {
        record("invalid context request %d.%d depth=%d stencil=%d",
               (int)gl_major, (int)gl_minor, (int)depth_bits, (int)stencil_bits);
        goto done;
    }
    const EGLint config_attributes[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, depth_bits, EGL_STENCIL_SIZE, stencil_bits, EGL_NONE
    };
    EGLint count = 0;
    if (!eglChooseConfig(display, config_attributes, &config, 1, &count) || count < 1) {
        record_egl("eglChooseConfig");
        goto done;
    }
    /* The Orbis EGL platform rejects NULL; token 1 selects the scanout surface. */
    surface = eglCreateWindowSurface(display, config, (EGLNativeWindowType)1, NULL);
    if (surface == EGL_NO_SURFACE) { record_egl("eglCreateWindowSurface"); goto done; }
    EGLint flags = forward_compatible ? EGL_CONTEXT_OPENGL_FORWARD_COMPATIBLE_BIT_KHR : 0;
    const EGLint context_attributes[] = {
        EGL_CONTEXT_MAJOR_VERSION, gl_major, EGL_CONTEXT_MINOR_VERSION, gl_minor,
        EGL_CONTEXT_OPENGL_PROFILE_MASK, core_profile ? EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT
                                                      : EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT,
        EGL_CONTEXT_FLAGS_KHR, flags, EGL_NONE
    };
    context = eglCreateContext(display, config, EGL_NO_CONTEXT, context_attributes);
    if (context == EGL_NO_CONTEXT) {
        record_egl("eglCreateContext");
        release_window_locked();
        goto done;
    }
    if (!eglQuerySurface(display, surface, EGL_WIDTH, &surface_width) ||
        !eglQuerySurface(display, surface, EGL_HEIGHT, &surface_height) ||
        surface_width <= 0 || surface_height <= 0) {
        record_egl("eglQuerySurface dimensions");
        release_window_locked();
        goto done;
    }
    record("none");
    result = PLATFORM_WINDOW;
done:
    pthread_mutex_unlock(&platform_lock);
    return result;
}

/* window 0 releases the calling thread's context. */
JNIEXPORT jboolean JNICALL Java_orbis_graphics_NativePlatform_nMakeCurrent(
        JNIEnv *env, jclass klass, jlong window) {
    (void)env; (void)klass;
    jboolean result = JNI_FALSE;
    pthread_mutex_lock(&platform_lock);
    if (display == EGL_NO_DISPLAY) { record("platform is not initialised"); goto done; }
    if (window == 0) {
        result = eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) ? JNI_TRUE : JNI_FALSE;
        if (!result) record_egl("eglMakeCurrent(release)");
        goto done;
    }
    if (!valid_window_locked(window)) goto done;
    result = eglMakeCurrent(display, surface, surface, context) ? JNI_TRUE : JNI_FALSE;
    if (!result) record_egl("eglMakeCurrent");
done:
    pthread_mutex_unlock(&platform_lock);
    return result;
}

/* Presentation runs without the lifecycle lock; the facade forbids concurrent destroy. */
JNIEXPORT jboolean JNICALL Java_orbis_graphics_NativePlatform_nSwapBuffers(
        JNIEnv *env, jclass klass, jlong window) {
    (void)env; (void)klass;
    pthread_mutex_lock(&platform_lock);
    int valid = valid_window_locked(window);
    EGLDisplay current_display = display;
    EGLSurface current_surface = surface;
    pthread_mutex_unlock(&platform_lock);
    if (!valid) return JNI_FALSE;
    if (!eglSwapBuffers(current_display, current_surface)) {
        record_egl("eglSwapBuffers");
        return JNI_FALSE;
    }
    sceGnmSubmitDone();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_orbis_graphics_NativePlatform_nSwapInterval(
        JNIEnv *env, jclass klass, jint interval) {
    (void)env; (void)klass;
    if (interval < 0) { record("negative swap interval %d", (int)interval); return JNI_FALSE; }
    pthread_mutex_lock(&platform_lock);
    EGLDisplay current_display = display;
    pthread_mutex_unlock(&platform_lock);
    if (current_display == EGL_NO_DISPLAY) { record("platform is not initialised"); return JNI_FALSE; }
    if (!eglSwapInterval(current_display, interval)) { record_egl("eglSwapInterval"); return JNI_FALSE; }
    return JNI_TRUE;
}

/* GL entry point for LWJGL's FunctionProvider; 0 when Mesa does not provide it. */
JNIEXPORT jlong JNICALL Java_orbis_graphics_NativePlatform_nGetProcAddress(
        JNIEnv *env, jclass klass, jstring function) {
    (void)klass;
    if (!function) return 0;
    const char *name = (*env)->GetStringUTFChars(env, function, NULL);
    if (!name) return 0;
    jlong address = (jlong)(intptr_t)eglGetProcAddress(name);
    (*env)->ReleaseStringUTFChars(env, function, name);
    return address;
}

JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nFramebufferWidth(
        JNIEnv *env, jclass klass, jlong window) {
    (void)env; (void)klass;
    pthread_mutex_lock(&platform_lock);
    jint width = valid_window_locked(window) ? surface_width : 0;
    pthread_mutex_unlock(&platform_lock);
    return width;
}

JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nFramebufferHeight(
        JNIEnv *env, jclass klass, jlong window) {
    (void)env; (void)klass;
    pthread_mutex_lock(&platform_lock);
    jint height = valid_window_locked(window) ? surface_height : 0;
    pthread_mutex_unlock(&platform_lock);
    return height;
}

/* The caller finishes GPU work first (glFinish); EGL destruction follows. */
JNIEXPORT void JNICALL Java_orbis_graphics_NativePlatform_nDestroyWindow(
        JNIEnv *env, jclass klass, jlong window) {
    (void)env; (void)klass;
    pthread_mutex_lock(&platform_lock);
    if (valid_window_locked(window)) release_window_locked();
    pthread_mutex_unlock(&platform_lock);
}

JNIEXPORT void JNICALL Java_orbis_graphics_NativePlatform_nTerminate(JNIEnv *env, jclass klass) {
    (void)env; (void)klass;
    pthread_mutex_lock(&platform_lock);
    release_window_locked();
    if (display != EGL_NO_DISPLAY) {
        eglReleaseThread();
        eglTerminate(display);
        display = EGL_NO_DISPLAY;
    }
    pthread_mutex_unlock(&platform_lock);
}

JNIEXPORT jstring JNICALL Java_orbis_graphics_NativePlatform_nLastError(JNIEnv *env, jclass klass) {
    (void)klass;
    return (*env)->NewStringUTF(env, last_error);
}
