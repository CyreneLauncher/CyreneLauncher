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
#include <sys/mman.h>
#include <errno.h>

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
static int ime_cancel_locked(void);

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

/* libkernel's Sony thread API, declared here with the real signatures (the SDK header's are
 * placeholders). Attr and thread handles are pointers. */
int sce_attr_init(void *attr) __asm__("scePthreadAttrInit");
int sce_attr_destroy(void *attr) __asm__("scePthreadAttrDestroy");
int sce_attr_setstacksize(void *attr, size_t size) __asm__("scePthreadAttrSetstacksize");
int sce_attr_setinheritsched(void *attr, int inherit) __asm__("scePthreadAttrSetinheritsched");
int sce_create(void *thread, const void *attr, void *(*start)(void *), void *arg, const char *name)
    __asm__("scePthreadCreate");

static void *variant_probe_entry(void *unused) { (void)unused; return NULL; }

/* One creation variant: Sony or POSIX attrs, inherited or explicit scheduling, 2 MiB stack,
 * through scePthreadCreate as orbis-compat does. Runs start(arg), or a probe that is joined. */
static int create_variant(int sony, int explicit_sched, pthread_t *thread,
                          void *(*start)(void *), void *arg) {
    pthread_attr_t posix;
    void *sce = NULL;
    void *attr;
    int rc;
    if (sony) {
        rc = sce_attr_init(&sce);
        if (rc) return rc;
        sce_attr_setstacksize(&sce, 2u << 20);
        if (explicit_sched) sce_attr_setinheritsched(&sce, PTHREAD_EXPLICIT_SCHED);
        attr = &sce;
    } else {
        rc = pthread_attr_init(&posix);
        if (rc) return rc;
        pthread_attr_setstacksize(&posix, 2u << 20);
        if (explicit_sched) pthread_attr_setinheritsched(&posix, PTHREAD_EXPLICIT_SCHED);
        attr = &posix;
    }
    pthread_t created;
    rc = sce_create(&created, attr, start ? start : variant_probe_entry, start ? arg : NULL, "orbis-diag");
    if (rc == 0) {
        if (start) *thread = created;
        else pthread_join(created, NULL);
    }
    if (sony) sce_attr_destroy(&sce); else pthread_attr_destroy(&posix);
    return rc;
}

/* Whether a fresh anonymous mapping of `size` can be made right now; 0 or errno. */
static int try_anon(size_t size) {
    void *p = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON, -1, 0);
    if (p == MAP_FAILED) return errno;
    munmap(p, size);
    return 0;
}

/* The largest anonymous mapping that succeeds, by halving from 512 MiB, in MiB. */
static size_t largest_anon_mib(void) {
    size_t lo = 0, hi = 512;
    while (lo < hi) {
        size_t mid = (lo + hi + 1) / 2;
        if (try_anon(mid << 20) == 0) lo = mid; else hi = mid - 1;
    }
    return lo;
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
    diag("thread diag: fresh anonymous mmap: 64 KiB %d, 2 MiB %d, 16 MiB %d (0 = ok, else errno); "
         "largest that succeeds %zu MiB",
         try_anon(64 << 10), try_anon(2u << 20), try_anon(16u << 20), largest_anon_mib());
    int policy = -1;
    struct sched_param param;
    memset(&param, 0, sizeof(param));
    pthread_getschedparam(pthread_self(), &policy, &param);
    pthread_attr_t fresh;
    int fresh_inherit = -1;
    if (pthread_attr_init(&fresh) == 0) {
        pthread_attr_getinheritsched(&fresh, &fresh_inherit);
        pthread_attr_destroy(&fresh);
    }
    int variants[4];
    variants[0] = create_variant(1, 0, NULL, NULL, NULL);
    variants[1] = create_variant(1, 1, NULL, NULL, NULL);
    variants[2] = create_variant(0, 0, NULL, NULL, NULL);
    variants[3] = create_variant(0, 1, NULL, NULL, NULL);
    diag("thread diag: creator policy %d priority %d; fresh POSIX attr inheritsched %d (4=inherit, 0=explicit); "
         "variants: sony/default %d, sony/explicit %d, posix/default %d, posix/explicit %d",
         policy, param.sched_priority, fresh_inherit, variants[0], variants[1], variants[2], variants[3]);
    for (int v = 0; v < 4; v++) {
        if (variants[v] == 0) {
            rc = create_variant(v < 2, v & 1, thread, start, arg);
            diag("thread diag: real thread through variant %d: rc=%d", v, rc);
            if (rc == 0) return 0;
        }
    }
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

#if !defined(ORBIS_HEAP_CHECK) && !defined(ORBIS_HEAP_TRACE)
/* ⚠ musl's (old) realloc writes past the block it returns when a LARGE block shrinks. Blocks above its mmap
 * threshold (~224 KiB) are their own mappings, and realloc resizes them with __mremap; Orbis has none (the
 * runtime's stub fails), so musl falls back to malloc + memcpy + free - and copies the OLD size, n0, even
 * when the new block is smaller. Measured with the heap tracer: Mesa's blob grew to 262144 bytes, then
 * blob_finish_get_buffer shrank it to 168740, and the copy ran ~92 KiB past the new block, zeroing the
 * heap chunk after it. A later malloc walked into that chunk's next == NULL: SIGSEGV at 0x10 in malloc,
 * every run of Iris with Complementary, whose shaders serialise to blobs that large.
 *
 * So a shrinking mapped block is moved here, copying the new size; everything else is musl's. A mapped
 * chunk is the one whose size word lacks musl's in-use bit; its usable size is that word minus the
 * 16-byte header. */
void *__real_realloc(void *p, size_t n);

void *__wrap_realloc(void *p, size_t n) {
    if (p) {
        const char *q = p;
        __asm__("" : "+r"(q)); /* the header is before the block: hide that from clang's bounds reasoning */
        const size_t csize = *(const size_t *)(q - sizeof(size_t));
        if (!(csize & 1) && n < csize - 2 * sizeof(size_t)) {
            void *r = malloc(n);
            if (!r) return NULL;
            memcpy(r, p, n);
            free(p);
            return r;
        }
    }
    return __real_realloc(p, n);
}
#endif

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    return JNI_VERSION_1_8;
}

/* -Dorbis.graphics.glthread=true|false sets Mesa's mesa_glthread (read at context creation);
 * unset leaves Mesa's own choice. Read here so the Java signature stays unchanged. */
/* The GPU driver's A/B knobs, set from jvm-options.txt as -Dorbis.graphics.env.<NAME>=<value>. Only these
 * names are passed through, so a typo cannot reach an unrelated environment variable. */
static const char *const DRIVER_KNOBS[] = {
    "ORBIS_GPU_PROFILE", "ORBIS_NUM_SE", "ORBIS_NUM_RB", "ORBIS_TCC_BLOCKS", "ORBIS_CHIP",
    "ORBIS_DUMP_INIT_STATE", "ORBIS_INIT_HW_STATE", "ORBIS_IA_MULTI_VGT", "ORBIS_RASTER_CONFIG", "RADV_DEBUG", "MESA_SHADER_CACHE_DIR", "ORBIS_GL_DRIVER", "AMD_DEBUG", "ORBIS_TILE_SWIZZLE", "GALLIUM_THREAD", "radeonsi_vs_fetch_always_opencode", "MESA_EXTENSION_OVERRIDE", "ORBIS_COMPUTE_BUFFER_OPS", "ORBIS_TRACE_BUFFER_OPS", "ORBIS_VRAM_GARLIC", "ORBIS_CHECK_CLEARS", "ORBIS_FAST_CLEAR", "ORBIS_CMASK_LINEAR", "ORBIS_CMASK_ADDR_TYPE", "ORBIS_USER_FENCE", "ORBIS_SWAP_OVERLAP", "ORBIS_GS_RING_PAD", "ORBIS_GS_RING_PAD_ESGS", "ORBIS_GS_RING_GSVS_MIN_MB", "ORBIS_GS_RING_PAD_CAP_MB", "ORBIS_GS_RING_SCALE", "ORBIS_GS_RING_SCALE_ESGS", "ORBIS_GS_RING_SCALE_GSVS", "ORBIS_TESS_OFFCHIP_PAD", "ORBIS_ALT_TILING", "ORBIS_HTILE",
};

static int seed_driver_knobs(JNIEnv *env) {
    jclass system = (*env)->FindClass(env, "java/lang/System");
    if (!system) { (*env)->ExceptionClear(env); return 0; }
    jmethodID get = (*env)->GetStaticMethodID(env, system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    int failed = 0;
    for (size_t i = 0; get && i < sizeof(DRIVER_KNOBS) / sizeof(DRIVER_KNOBS[0]); i++) {
        char property[96];
        snprintf(property, sizeof(property), "orbis.graphics.env.%s", DRIVER_KNOBS[i]);
        jstring key = (*env)->NewStringUTF(env, property);
        jstring value = key ? (jstring)(*env)->CallStaticObjectMethod(env, system, get, key) : NULL;
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); value = NULL; }
        if (value) {
            const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
            if (chars) {
                failed |= setenv(DRIVER_KNOBS[i], chars, 1) != 0;
                diag("driver knob %s=%s (from -D%s)", DRIVER_KNOBS[i], chars, property);
                (*env)->ReleaseStringUTFChars(env, value, chars);
            }
            (*env)->DeleteLocalRef(env, value);
        }
        if (key) (*env)->DeleteLocalRef(env, key);
    }
    (*env)->DeleteLocalRef(env, system);
    /* Mesa keeps compiled shaders and Zink's pipelines in its disk cache, under MESA_SHADER_CACHE_DIR,
     * XDG_CACHE_HOME or HOME - none of which the console sets, so every session compiled everything again,
     * stuttering whenever new geometry or effects came into view. */
    if (!getenv("MESA_SHADER_CACHE_DIR")) {
        failed |= setenv("MESA_SHADER_CACHE_DIR", "/data/orbis-jdk/mesa-shader-cache", 1) != 0;
        diag("MESA_SHADER_CACHE_DIR=/data/orbis-jdk/mesa-shader-cache");
    }
    return failed;
}

static int seed_glthread_option(JNIEnv *env) {
    jclass system = (*env)->FindClass(env, "java/lang/System");
    if (!system) { (*env)->ExceptionClear(env); return 0; }
    jmethodID get = (*env)->GetStaticMethodID(env, system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring key = get ? (*env)->NewStringUTF(env, "orbis.graphics.glthread") : NULL;
    jstring value = key ? (jstring)(*env)->CallStaticObjectMethod(env, system, get, key) : NULL;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); value = NULL; }
    int failed = 0;
    if (value) {
        const char *chars = (*env)->GetStringUTFChars(env, value, NULL);
        if (chars) {
            failed = setenv("mesa_glthread", chars, 1) != 0;
            diag("mesa_glthread=%s (from orbis.graphics.glthread)", chars);
            (*env)->ReleaseStringUTFChars(env, value, chars);
        }
    }
    if (key) (*env)->DeleteLocalRef(env, key);
    if (value) (*env)->DeleteLocalRef(env, value);
    /* Isolated CU diagnostic: disable the shader disk cache so a cached clock
     * shader cannot bypass the hardware-ID instrumentation. */
    jstring probe_key = get ? (*env)->NewStringUTF(env, "orbis.graphics.cuProbe") : NULL;
    jstring probe_value = probe_key ? (jstring)(*env)->CallStaticObjectMethod(env, system, get, probe_key) : NULL;
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); probe_value = NULL; }
    if (probe_value) {
        const char *chars = (*env)->GetStringUTFChars(env, probe_value, NULL);
        if (chars) {
            if (strcmp(chars, "true") == 0) {
                failed |= setenv("ORBIS_CU_PROBE", "1", 1) != 0;
                failed |= setenv("MESA_SHADER_CACHE_DISABLE", "true", 1) != 0;
                diag("CU hardware-ID diagnostic enabled; shader clocks are not timestamps");
            }
            (*env)->ReleaseStringUTFChars(env, probe_value, chars);
        }
    }
    if (probe_key) (*env)->DeleteLocalRef(env, probe_key);
    if (probe_value) (*env)->DeleteLocalRef(env, probe_value);
    (*env)->DeleteLocalRef(env, system);
    return failed;
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
    if (!env_failed) env_failed = seed_glthread_option(env);
    if (!env_failed) env_failed = seed_driver_knobs(env);
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

/* glGetString is constant for the life of the (single) context, but every call synchronises
 * with Mesa's GL worker - ~70 a second from Minecraft's debug screen. Cached per name. */
typedef const unsigned char *(*get_string_fn)(unsigned int name);
static get_string_fn real_get_string;
static unsigned int cached_string_name[8];
static const unsigned char *cached_string_value[8];

static const unsigned char *cached_get_string(unsigned int name) {
    for (int i = 0; i < 8; i++) {
        if (cached_string_value[i] && cached_string_name[i] == name) return cached_string_value[i];
    }
    const unsigned char *value = real_get_string(name);
    if (value) {
        for (int i = 0; i < 8; i++) {
            if (!cached_string_value[i]) {
                cached_string_name[i] = name;
                cached_string_value[i] = value;
                break;
            }
        }
    }
    return value;
}

/* The facade draws the pointer over each frame and must leave the application's state as it
 * found it. Querying that state synchronises with Mesa's GL worker - half the render thread
 * with item glints on screen - so the few values the pointer touches are recorded as the
 * application sets them, through wrappers handed out by nGetProcAddress. Only the context's
 * thread calls them. Defaults are GL's initial state. */
#define GL_SCISSOR_TEST 0x0C11
#define GL_COLOR_BUFFER_BIT 0x00004000
#define GL_FRAMEBUFFER 0x8D40
#define GL_DRAW_FRAMEBUFFER 0x8CA9

typedef void (*enable_fn)(unsigned int cap);
typedef void (*scissor_fn)(int x, int y, int width, int height);
typedef void (*clear_color_fn)(float red, float green, float blue, float alpha);
typedef void (*color_mask_fn)(unsigned char red, unsigned char green, unsigned char blue, unsigned char alpha);
typedef void (*bind_framebuffer_fn)(unsigned int target, unsigned int framebuffer);
typedef void (*delete_framebuffers_fn)(int count, const unsigned int *framebuffers);
typedef void (*clear_fn)(unsigned int mask);

static enable_fn real_enable, real_disable;
static scissor_fn real_scissor;
static clear_color_fn real_clear_color;
static color_mask_fn real_color_mask;
static bind_framebuffer_fn real_bind_framebuffer;
static delete_framebuffers_fn real_delete_framebuffers;
static clear_fn real_clear;

static int shadow_scissor_enabled;
static int shadow_scissor_set, shadow_scissor[4]; /* unset: the window size, GL's initial box */
static float shadow_clear_color[4];
static unsigned char shadow_color_mask[4] = {1, 1, 1, 1};
static unsigned int shadow_draw_framebuffer;

static void shadow_enable(unsigned int cap) {
    if (cap == GL_SCISSOR_TEST) shadow_scissor_enabled = 1;
    real_enable(cap);
}

static void shadow_disable(unsigned int cap) {
    if (cap == GL_SCISSOR_TEST) shadow_scissor_enabled = 0;
    real_disable(cap);
}

static void shadow_scissor_box(int x, int y, int width, int height) {
    shadow_scissor[0] = x; shadow_scissor[1] = y; shadow_scissor[2] = width; shadow_scissor[3] = height;
    shadow_scissor_set = 1;
    real_scissor(x, y, width, height);
}

static void shadow_clear_color_value(float red, float green, float blue, float alpha) {
    shadow_clear_color[0] = red; shadow_clear_color[1] = green;
    shadow_clear_color[2] = blue; shadow_clear_color[3] = alpha;
    real_clear_color(red, green, blue, alpha);
}

static void shadow_color_write_mask(unsigned char red, unsigned char green, unsigned char blue, unsigned char alpha) {
    shadow_color_mask[0] = red; shadow_color_mask[1] = green;
    shadow_color_mask[2] = blue; shadow_color_mask[3] = alpha;
    real_color_mask(red, green, blue, alpha);
}

static void shadow_bind_framebuffer(unsigned int target, unsigned int framebuffer) {
    if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) shadow_draw_framebuffer = framebuffer;
    real_bind_framebuffer(target, framebuffer);
}

/* Deleting the bound framebuffer reverts the binding to 0. */
static void shadow_delete_framebuffers(int count, const unsigned int *framebuffers) {
    for (int i = 0; framebuffers && i < count; i++) {
        if (framebuffers[i] && framebuffers[i] == shadow_draw_framebuffer) shadow_draw_framebuffer = 0;
    }
    real_delete_framebuffers(count, framebuffers);
}

/* name -> (where the real entry point is kept, the wrapper handed out instead) */
static const struct {
    const char *name;
    void **real;
    void *wrapper;
} shadowed[] = {
    {"glEnable", (void **)&real_enable, (void *)shadow_enable},
    {"glDisable", (void **)&real_disable, (void *)shadow_disable},
    {"glScissor", (void **)&real_scissor, (void *)shadow_scissor_box},
    {"glClearColor", (void **)&real_clear_color, (void *)shadow_clear_color_value},
    {"glColorMask", (void **)&real_color_mask, (void *)shadow_color_write_mask},
    {"glBindFramebuffer", (void **)&real_bind_framebuffer, (void *)shadow_bind_framebuffer},
    {"glDeleteFramebuffers", (void **)&real_delete_framebuffers, (void *)shadow_delete_framebuffers},
};

/* An outlined arrow, top-left at (x, top) in GL window coordinates: a black triangle with a
 * white one inside, built from scissored clears of rows scale pixels tall. Restores the
 * recorded state afterwards. Skipped until the application has looked up every entry point,
 * since only then is the record complete. */
JNIEXPORT void JNICALL Java_orbis_graphics_NativePlatform_nDrawPointer(
        JNIEnv *env, jclass klass, jint x, jint top, jint scale) {
    (void)env; (void)klass;
    for (size_t i = 0; i < sizeof(shadowed) / sizeof(shadowed[0]); i++) {
        if (!*shadowed[i].real) return;
    }
    if (!real_clear) real_clear = (clear_fn)(intptr_t)eglGetProcAddress("glClear");
    if (!real_clear) return;

    real_bind_framebuffer(GL_DRAW_FRAMEBUFFER, 0);
    real_enable(GL_SCISSOR_TEST);
    real_color_mask(1, 1, 1, 1);
    for (int pass = 0; pass < 2; pass++) {
        if (pass == 0) real_clear_color(0.0f, 0.0f, 0.0f, 1.0f);
        else real_clear_color(1.0f, 1.0f, 1.0f, 1.0f);
        for (int row = 0; row < 12; row++) {
            if (pass == 1 && (row == 0 || row == 11)) continue;
            int width = pass == 0 ? row + 2 : row;
            int left = pass == 0 ? 0 : 1;
            real_scissor(x + left * scale, top - (row + 1) * scale, width * scale, scale);
            real_clear(GL_COLOR_BUFFER_BIT);
        }
    }

    real_clear_color(shadow_clear_color[0], shadow_clear_color[1], shadow_clear_color[2], shadow_clear_color[3]);
    real_color_mask(shadow_color_mask[0], shadow_color_mask[1], shadow_color_mask[2], shadow_color_mask[3]);
    if (shadow_scissor_set) {
        real_scissor(shadow_scissor[0], shadow_scissor[1], shadow_scissor[2], shadow_scissor[3]);
    } else {
        pthread_mutex_lock(&platform_lock);
        EGLint width = surface_width, height = surface_height;
        pthread_mutex_unlock(&platform_lock);
        real_scissor(0, 0, width, height);
    }
    if (!shadow_scissor_enabled) real_disable(GL_SCISSOR_TEST);
    real_bind_framebuffer(GL_DRAW_FRAMEBUFFER, shadow_draw_framebuffer);
}

/* GL entry point for LWJGL's FunctionProvider; 0 when Mesa does not provide it. */
JNIEXPORT jlong JNICALL Java_orbis_graphics_NativePlatform_nGetProcAddress(
        JNIEnv *env, jclass klass, jstring function) {
    (void)klass;
    if (!function) return 0;
    const char *name = (*env)->GetStringUTFChars(env, function, NULL);
    if (!name) return 0;
    jlong address = (jlong)(intptr_t)eglGetProcAddress(name);
    if (address && strcmp(name, "glGetString") == 0) {
        real_get_string = (get_string_fn)(intptr_t)address;
        address = (jlong)(intptr_t)cached_get_string;
    }
    for (size_t i = 0; address && i < sizeof(shadowed) / sizeof(shadowed[0]); i++) {
        if (strcmp(name, shadowed[i].name) == 0) {
            *shadowed[i].real = (void *)(intptr_t)address;
            address = (jlong)(intptr_t)shadowed[i].wrapper;
            break;
        }
    }
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

/* The window and context are released; the display is not terminated. glfwTerminate comes right
 * before the process exits (or is replaced by LoadExec), which reclaims the GPU and memory, while
 * eglTerminate's driver teardown never returned after Minecraft's Quit - the app sat on a black screen.
 * The display stays initialised, so a later nInitialize in this process reuses it. */
JNIEXPORT void JNICALL Java_orbis_graphics_NativePlatform_nTerminate(JNIEnv *env, jclass klass) {
    (void)env; (void)klass;
    pthread_mutex_lock(&platform_lock);
    ime_cancel_locked();
    release_window_locked();
    if (display != EGL_NO_DISPLAY) eglReleaseThread();
    pthread_mutex_unlock(&platform_lock);
}

/* DualShock 4 for the GLFW gamepad API. libScePad is opened here, on first read, for the user the
 * application started with; nothing else in the process opens it. */
int32_t scePadInit(void);
int32_t scePadOpen(int32_t user_id, int32_t type, int32_t index, void *param);
int32_t scePadClose(int32_t handle);
int32_t sceUserServiceGetInitialUser(int32_t *user_id);

typedef struct {
    uint32_t buttons;
    uint8_t left_x, left_y, right_x, right_y;
    uint8_t l2, r2;
    uint16_t padding;
    float quat[4], vel[3], accel[3];
    uint8_t touch[24];
    uint8_t connected;
    uint64_t timestamp;
    uint8_t ext[16];
    uint8_t count;
    uint8_t unknown[15];
} OrbisPadState;   /* the SDK's OrbisPadData */
int32_t scePadReadState(int32_t handle, OrbisPadState *data);

static int32_t pad_handle = -1;
static int32_t pad_open_error;
static time_t pad_retry_after;

/* state[0..7] = connected, buttons, left x, left y, right x, right y, L2, R2 (raw 0-255 axes).
 * Returns 0, or the libScePad error while the pad cannot be opened (retried every 2 s). */
JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nPadRead(JNIEnv *env, jclass klass, jintArray out) {
    (void)klass;
    jint state[8] = {0};
    if (pad_handle < 0) {
        time_t now = time(NULL);
        if (now >= pad_retry_after) {
            pad_retry_after = now + 2;
            int32_t user = -1;
            int32_t rc = scePadInit();
            if (rc >= 0) rc = sceUserServiceGetInitialUser(&user);
            if (rc >= 0) rc = scePadOpen(user, 0, 0, NULL);
            if (rc >= 0) pad_handle = rc;
            else pad_open_error = rc;
        }
    }
    if (pad_handle >= 0) {
        OrbisPadState data;
        memset(&data, 0, sizeof(data));
        if (scePadReadState(pad_handle, &data) == 0 && data.connected) {
            state[0] = 1;
            state[1] = (jint)data.buttons;
            state[2] = data.left_x; state[3] = data.left_y;
            state[4] = data.right_x; state[5] = data.right_y;
            state[6] = data.l2; state[7] = data.r2;
        }
    }
    (*env)->SetIntArrayRegion(env, out, 0, 8, state);
    return pad_handle >= 0 ? 0 : pad_open_error;
}

/* Replaces this process with a fresh start of the application (libSceSystemService LoadExec). Returns
 * only on failure, with the error. */
int32_t sceSystemServiceLoadExec(const char *path, const char *args[]);

JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nRelaunch(JNIEnv *env, jclass klass) {
    (void)env; (void)klass;
    const char *args[] = {NULL};
    return (jint)sceSystemServiceLoadExec("/app0/eboot.bin", args);
}

/* A marker file alone cannot distinguish LoadExec from an unrelated cold start.
 * Supply the credential-free preparation ID in this invocation's argv as well. */
JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nRelaunchWithIntent(JNIEnv *env, jclass klass,
        jstring intent) {
    (void)klass;
    if (!intent || (*env)->GetStringLength(env, intent) != 36) return -1;
    const char *value = (*env)->GetStringUTFChars(env, intent, NULL);
    if (!value) return -1;
    char argument[80];
    int valid = 1;
    for (int i = 0; i < 36; i++)
        if (!((value[i] >= '0' && value[i] <= '9') || (value[i] >= 'a' && value[i] <= 'f') || value[i] == '-')) valid = 0;
    snprintf(argument, sizeof(argument), "--orbis-launch-intent=%s", value);
    (*env)->ReleaseStringUTFChars(env, intent, value);
    if (!valid) return -1;
    const char *args[] = {argument, NULL};
    return (jint)sceSystemServiceLoadExec("/app0/eboot.bin", args);
}

/* libSceSystemService's status: eventNum (int32), then isSystemUiOverlaid, isInBackgroundExecution,
 * isCpuMode7CpuNormal, isGameLiveStreamingOnAir, isOutOfVrPlayArea (bools) and reserved bytes. The
 * buffer is larger than the struct, zeroed, and the leading bytes are copied out for Java to decode. */
int32_t sceSystemServiceGetStatus(void *status);

JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nSystemStatus(JNIEnv *env, jclass klass, jbyteArray out) {
    (void)klass;
    uint8_t status[256];
    memset(status, 0, sizeof(status));
    int32_t rc = sceSystemServiceGetStatus(status);
    jsize length = (*env)->GetArrayLength(env, out);
    if (length > (jsize)sizeof(status)) length = (jsize)sizeof(status);
    (*env)->SetByteArrayRegion(env, out, 0, length, (const jbyte *)status);
    return (jint)rc;
}

JNIEXPORT jstring JNICALL Java_orbis_graphics_NativePlatform_nLastError(JNIEnv *env, jclass klass) {
    (void)klass;
    return (*env)->NewStringUTF(env, last_error);
}

/* PS4 IME is a system dialog (drawn by SceCdlgApp), not an application-rendered
 * keyboard. The SDK declarations fix the parameter ABI; text is UTF-16 even
 * when the compiler's wchar_t is 32 bits. Static buffers survive failed Term. */
#include <stddef.h>
#include <wchar.h>
#include <orbis/ImeDialog.h>
#include <orbis/CommonDialog.h>
#include <orbis/Sysmodule.h>

_Static_assert(sizeof(OrbisImeDialogSetting) == 96, "IME parameter ABI");
_Static_assert(offsetof(OrbisImeDialogSetting, inputTextBuffer) == 40, "IME input offset");
_Static_assert(offsetof(OrbisImeDialogSetting, title) == 72, "IME title offset");
_Static_assert(sizeof(OrbisDialogResult) == 16, "IME result ABI");
#define IME_CAPACITY 1024
static uint16_t ime_text[IME_CAPACITY + 1];
static uint16_t ime_title[129];
static OrbisImeDialogSetting ime_parameter;
static int ime_active;
static int ime_terminal;
static int ime_modules_ready;
static int ime_closing;
static uint64_t ime_none_since;
static uint64_t ime_term_retry_at;

static uint64_t ime_now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec;
}

static int ime_finish_locked(void) {
    int32_t rc = sceImeDialogTerm();
    if (rc >= 0) {
        ime_active = 0;
        ime_closing = 0;
        return 0;
    }
    record("IME closing; termination returned 0x%08x", (unsigned)rc);
    ime_closing = 1;
    ime_term_retry_at = ime_now() + 250000000u;
    return 1;
}

int32_t ime_internal_loaded(uint32_t module) __asm__("sceSysmoduleIsLoadedInternal");

static int ime_modules(void) {
    if (ime_modules_ready) return 0;
    int32_t rc = 0;
    if (ime_internal_loaded(ORBIS_SYSMODULE_INTERNAL_COMMON_DIALOG) != 0) {
        rc = (int32_t)sceSysmoduleLoadModuleInternal(ORBIS_SYSMODULE_INTERNAL_COMMON_DIALOG);
        if (rc < 0) { record("Common dialog module load failed (0x%08x)", (unsigned)rc); return rc; }
    }
    if (sceSysmoduleIsLoaded(ORBIS_SYSMODULE_LIBIME) != 0) {
        rc = sceSysmoduleLoadModule(ORBIS_SYSMODULE_LIBIME);
        if (rc < 0) { record("IME module load failed (0x%08x)", (unsigned)rc); return rc; }
    }
    if (sceSysmoduleIsLoaded(ORBIS_SYSMODULE_IME_DIALOG) != 0) {
        rc = sceSysmoduleLoadModule(ORBIS_SYSMODULE_IME_DIALOG);
        if (rc < 0) { record("IME dialog load failed (0x%08x)", (unsigned)rc); return rc; }
    }
    rc = sceCommonDialogInitialize();
    if (rc < 0 && (uint32_t)rc != 0x80b80002u) {
        record("Common dialog initialization failed (0x%08x)", (unsigned)rc);
        return rc;
    }
    ime_modules_ready = 1;
    return 0;
}

static int ime_cancel_locked(void) {
    if (!ime_active) { ime_terminal = 3; return 0; }
    ime_terminal = 3;
    if (!ime_closing) sceImeDialogForceClose();
    ime_closing = 1;
    return ime_finish_locked();
}

JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nImeBegin(JNIEnv *env, jclass klass,
        jstring initial, jstring title, jint max_length) {
    (void)klass;
    pthread_mutex_lock(&platform_lock);
    int32_t rc = -1;
    if (ime_active) { record("IME dialog already active"); goto done; }
    if (!initial || !title || max_length < 1 || max_length > IME_CAPACITY) {
        record("Invalid IME text parameters"); goto done;
    }
    jsize length = (*env)->GetStringLength(env, initial);
    jsize title_length = (*env)->GetStringLength(env, title);
    if (length > max_length || title_length > 128) {
        record("IME text exceeds field limit"); goto done;
    }
    rc = ime_modules();
    if (rc < 0) goto done;
    int32_t user = -1;
    rc = sceUserServiceGetInitialUser(&user);
    if (rc < 0) { record("IME initial user failed (0x%08x)", (unsigned)rc); goto done; }
    memset(ime_text, 0, sizeof(ime_text));
    memset(ime_title, 0, sizeof(ime_title));
    (*env)->GetStringRegion(env, initial, 0, length, (jchar *)ime_text);
    (*env)->GetStringRegion(env, title, 0, title_length, (jchar *)ime_title);
    if ((*env)->ExceptionCheck(env)) { rc = -1; goto done; }
    memset(&ime_parameter, 0, sizeof(ime_parameter));
    ime_parameter.userId = (uint32_t)user;
    ime_parameter.type = ORBIS_TYPE_DEFAULT;
    ime_parameter.enterLabel = ORBIS_BUTTON_LABEL_DEFAULT;
    ime_parameter.maxTextLength = (uint32_t)max_length;
    ime_parameter.inputTextBuffer = (wchar_t *)(void *)ime_text;
    ime_parameter.title = (const wchar_t *)(const void *)ime_title;
    rc = sceImeDialogInit(&ime_parameter, NULL);
    if (rc < 0) record("IME dialog initialization failed (0x%08x)", (unsigned)rc);
    else { ime_active = 1; ime_terminal = 1; ime_closing = 0; ime_none_since = ime_now(); }
done:
    pthread_mutex_unlock(&platform_lock);
    return rc;
}

/* 0 idle, 1 running, 2 accepted, 3 cancelled, negative error. */
JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nImePoll(JNIEnv *env, jclass klass) {
    (void)env; (void)klass;
    pthread_mutex_lock(&platform_lock);
    int32_t rc = ime_terminal;
    if (ime_active) {
        uint64_t now = ime_now();
        if (ime_closing) {
            if (now >= ime_term_retry_at) ime_finish_locked();
            rc = ime_active ? 1 : ime_terminal;
        } else {
            OrbisDialogStatus status = sceImeDialogGetStatus();
            if (status == ORBIS_DIALOG_STATUS_RUNNING) {
                ime_none_since = 0;
                rc = 1;
            } else if (status == ORBIS_DIALOG_STATUS_NONE) {
                /* Measured on console: NONE is both startup pending and disappeared.
                 * Bound consecutive NONE by elapsed time, independent of frame rate. */
                if (!ime_none_since) ime_none_since = now;
                if (now - ime_none_since < 6000000000ull) rc = 1;
                else { ime_cancel_locked(); rc = ime_active ? 1 : ime_terminal; }
            } else if (status == ORBIS_DIALOG_STATUS_STOPPED) {
                OrbisDialogResult result;
                memset(&result, 0, sizeof(result));
                int32_t get = sceImeDialogGetResult(&result);
                ime_terminal = get < 0 ? -1 : result.endstatus == ORBIS_DIALOG_OK ? 2 : 3;
                if (get < 0) record("IME result failed (0x%08x)", (unsigned)get);
                ime_closing = 1;
                ime_finish_locked();
                rc = ime_active ? 1 : ime_terminal;
            } else {
                record("IME returned an unknown status");
                ime_terminal = -1;
                sceImeDialogForceClose();
                ime_closing = 1;
                ime_finish_locked();
                rc = ime_active ? 1 : -1;
            }
        }
    }
    pthread_mutex_unlock(&platform_lock);
    return rc;
}

JNIEXPORT jstring JNICALL Java_orbis_graphics_NativePlatform_nImeResult(JNIEnv *env, jclass klass) {
    (void)klass;
    pthread_mutex_lock(&platform_lock);
    size_t length = 0;
    if (ime_terminal == 2) while (length < IME_CAPACITY && ime_text[length]) length++;
    jstring result = (*env)->NewString(env, (const jchar *)ime_text, (jsize)length);
    pthread_mutex_unlock(&platform_lock);
    return result;
}

JNIEXPORT jint JNICALL Java_orbis_graphics_NativePlatform_nImeCancel(JNIEnv *env, jclass klass) {
    (void)env; (void)klass;
    pthread_mutex_lock(&platform_lock);
    int result = ime_cancel_locked();
    pthread_mutex_unlock(&platform_lock);
    return result;
}
