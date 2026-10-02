#define _POSIX_C_SOURCE 200809L
#define GL_GLEXT_PROTOTYPES
#include "probe_render.h"
#include "probe_control.h"
#include "probe_log.h"
#include <GL/gl.h>
#include <GL/glext.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define GL_CALLS(X) \
 X(glCreateShader) X(glShaderSource) X(glCompileShader) X(glGetShaderiv) \
 X(glGetShaderInfoLog) X(glDeleteShader) X(glCreateProgram) X(glAttachShader) \
 X(glLinkProgram) X(glGetProgramiv) X(glGetProgramInfoLog) X(glDeleteProgram) \
 X(glUseProgram) X(glGetUniformLocation) X(glGetAttribLocation) X(glUniform1i) \
 X(glUniform1f) X(glGenVertexArrays) X(glBindVertexArray) X(glDeleteVertexArrays) \
 X(glGenBuffers) X(glBindBuffer) X(glBufferData) X(glDeleteBuffers) \
 X(glEnableVertexAttribArray) X(glVertexAttribPointer) X(glGenTextures) \
 X(glBindTexture) X(glTexParameteri) X(glTexImage2D) X(glTexSubImage2D) \
 X(glDeleteTextures) X(glActiveTexture) X(glGenFramebuffers) X(glBindFramebuffer) \
 X(glFramebufferTexture2D) X(glCheckFramebufferStatus) X(glDeleteFramebuffers) \
 X(glGenRenderbuffers) X(glBindRenderbuffer) X(glRenderbufferStorage) \
 X(glFramebufferRenderbuffer) X(glDeleteRenderbuffers) X(glViewport) \
 X(glClearColor) X(glClear) X(glEnable) X(glDisable) X(glDepthFunc) \
 X(glBlendFuncSeparate) X(glReadPixels) X(glDrawElements) X(glGetError) X(glFinish)

struct gl_api {
#define FIELD(name) __typeof__(&name) name;
 GL_CALLS(FIELD)
#undef FIELD
};

struct render_resources {
    GLuint vertex_shader, fragment_shader, program;
    GLuint vao, vbo, ebo, texture, color, depth, fbo;
};

int probe_pixel_matches(const unsigned char actual[4], const unsigned char expected[4], unsigned tolerance)
{
    unsigned i;
    if (!actual || !expected) return 0;
    for (i = 0; i < 4; ++i) {
        int delta = (int)actual[i] - (int)expected[i];
        if (delta < 0) delta = -delta;
        if ((unsigned)delta > tolerance) return 0;
    }
    return 1;
}

static int load_api(struct gl_api *g)
{
#define LOAD(name) do { \
    g->name = (__typeof__(g->name))eglGetProcAddress(#name); \
    if (!g->name) { probe_log("GL_ERROR", "missing desktop entry point " #name); return -1; } \
} while (0);
 GL_CALLS(LOAD)
#undef LOAD
 return 0;
}

static int gl_check(struct gl_api *g, const char *stage)
{
    GLenum error = g->glGetError();
    if (error == GL_NO_ERROR) return 0;
    char message[128];
    snprintf(message, sizeof message, "%s: GL error 0x%x", stage, (unsigned)error);
    probe_log("GL_ERROR", message);
    return -1;
}

static GLuint compile_shader(struct gl_api *g, GLenum type, const char *source, int expect_failure)
{
    GLuint shader = g->glCreateShader(type);
    GLint success = 0;
    char log[512];
    GLsizei size = 0;
    if (!shader) { probe_log("SHADER_ERROR", "glCreateShader returned zero"); return 0; }
    g->glShaderSource(shader, 1, &source, NULL);
    g->glCompileShader(shader);
    g->glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    if (!success || expect_failure) {
        g->glGetShaderInfoLog(shader, sizeof log - 1, &size, log);
        if (size < 0) size = 0;
        if (size >= (GLsizei)sizeof log) size = sizeof log - 1;
        log[size] = 0;
        probe_log(expect_failure && !success ? "SHADER_NEGATIVE" : "SHADER_ERROR", log);
    }
    if (expect_failure) {
        g->glDeleteShader(shader);
        return success ? shader : 0;
    }
    if (!success) { g->glDeleteShader(shader); return 0; }
    return shader;
}

static int pixel_check(struct gl_api *g, const char *name, int x, int y,
                       const unsigned char expected[4], unsigned tolerance)
{
    unsigned char actual[4] = {0};
    char message[160];
    g->glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, actual);
    if (gl_check(g, "glReadPixels") != 0) return -1;
    if (probe_pixel_matches(actual, expected, tolerance)) return 0;
    snprintf(message, sizeof message,
             "%s (%d,%d) expected=%u,%u,%u,%u actual=%u,%u,%u,%u tolerance=%u",
             name, x, y, expected[0], expected[1], expected[2], expected[3],
             actual[0], actual[1], actual[2], actual[3], tolerance);
    probe_log("PIXEL_ERROR", message);
    return -1;
}

static void texture_color(struct gl_api *g, const unsigned char rgba[4])
{
    unsigned char pixels[16];
    for (unsigned i = 0; i < 4; ++i) memcpy(pixels + 4*i, rgba, 4);
    g->glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
}

static void draw(struct gl_api *g, GLint depth_location, float depth)
{
    g->glUniform1f(depth_location, depth);
    g->glDrawElements(GL_TRIANGLES, 3, GL_UNSIGNED_SHORT, (const void *)0);
}

static double elapsed_ms(const struct timespec *start, const struct timespec *end)
{
    return (double)(end->tv_sec - start->tv_sec) * 1000.0 +
           (double)(end->tv_nsec - start->tv_nsec) / 1000000.0;
}

int probe_render_present_frames(struct probe_egl *ctx, unsigned frames,
                                int (*draw_frame)(void *, unsigned), void *opaque,
                                double *cpu_mean_ms, double *cpu_max_ms)
{
    double sum = 0.0, maximum = 0.0;
    if (!ctx || !draw_frame || !cpu_mean_ms || !cpu_max_ms) return -1;
    if (frames < 600) frames = 600;
    if (!eglSwapInterval(ctx->display, 1)) {
        probe_log("PRESENT_ERROR", "eglSwapInterval(1) failed"); return -1;
    }
    for (unsigned i = 0; i < frames; ++i) {
        struct timespec start, end;
        if (clock_gettime(CLOCK_MONOTONIC, &start) != 0) {
            probe_log("TIMING_ERROR", "clock_gettime start failed"); return -1;
        }
        if (draw_frame(opaque, i) != 0 || probe_egl_present(ctx) != 0) return -1;
        if (clock_gettime(CLOCK_MONOTONIC, &end) != 0) {
            probe_log("TIMING_ERROR", "clock_gettime end failed"); return -1;
        }
        double milliseconds = elapsed_ms(&start, &end);
        sum += milliseconds;
        if (milliseconds > maximum) maximum = milliseconds;
    }
    *cpu_mean_ms = sum / frames;
    *cpu_max_ms = maximum;
    return 0;
}

struct frame_draw_state { struct gl_api *g; GLint depth_location; };

static int render_frame(void *opaque, unsigned frame)
{
    struct frame_draw_state *state = opaque;
    static const unsigned char red[] = {255,0,0,255};
    static const unsigned char green[] = {0,255,0,255};
    texture_color(state->g, (frame & 1) ? red : green);
    state->g->glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    draw(state->g, state->depth_location, 0.0f);
    return gl_check(state->g, "frame draw");
}

int probe_render_run(struct probe_egl *ctx, unsigned frames)
{
    struct gl_api g = {0};
    struct render_resources r = {0};
    static const char *vertex_source =
        "#version 150 core\n"
        "in vec2 aPosition; in vec2 aUV; out vec2 vUV; uniform float uDepth;\n"
        "void main() { vUV=aUV; gl_Position=vec4(aPosition,uDepth,1.0); }\n";
    static const char *fragment_source =
        "#version 150 core\n"
        "in vec2 vUV; out vec4 fragColor; uniform sampler2D uTexture;\n"
        "void main() { fragColor=texture(uTexture,vUV); }\n";
    static const char *bad_shader = "#version 150 core\nvoid main() { this is invalid; }\n";
    static const GLfloat vertices[] = {
        -0.9f,-0.8f, 0.0f,0.0f,
         0.9f,-0.8f, 1.0f,0.0f,
        -0.3f, 0.9f, 0.5f,1.0f
    };
    static const GLushort indices[] = {0,1,2};
    static const unsigned char red[] = {255,0,0,255};
    static const unsigned char green[] = {0,255,0,255};
    static const unsigned char blue[] = {0,0,255,255};
    static const unsigned char texture_pixels[] = {
        0,255,0,255, 255,0,0,255,
        0,0,255,255, 255,255,0,255
    };
    static const unsigned char half_blue[] = {0,0,255,128};
    static const unsigned char black[] = {0,0,0,255};
    static const unsigned char blended[] = {127,0,128,255};
    GLint linked = 0, position, uv, depth_location, sampler;
    GLuint incomplete = 0;
    double cpu_mean = 0.0, cpu_max = 0.0;
    struct frame_draw_state frame_state;
    char message[180];
    int result = -1;

    if (!ctx || ctx->display == EGL_NO_DISPLAY || ctx->context == EGL_NO_CONTEXT ||
        ctx->surface == EGL_NO_SURFACE) { probe_log("RENDER_ERROR", "no current EGL context"); return -1; }
    if (load_api(&g) != 0) return -1;
    r.vertex_shader = compile_shader(&g, GL_VERTEX_SHADER, vertex_source, 0);
    r.fragment_shader = compile_shader(&g, GL_FRAGMENT_SHADER, fragment_source, 0);
    if (!r.vertex_shader || !r.fragment_shader) goto cleanup;
    if (compile_shader(&g, GL_VERTEX_SHADER, bad_shader, 1) != 0) {
        probe_log("SHADER_ERROR", "invalid GLSL unexpectedly compiled"); goto cleanup;
    }
    if (probe_control_requested("PROBE_FAIL_SHADER",
                                "/data/orbis-jdk/graphics-probe/fail-shader")) {
        probe_log("SHADER_NEGATIVE", "controlled shader failure requested"); goto cleanup;
    }
    r.program = g.glCreateProgram();
    if (!r.program) { probe_log("SHADER_ERROR", "glCreateProgram returned zero"); goto cleanup; }
    g.glAttachShader(r.program, r.vertex_shader);
    g.glAttachShader(r.program, r.fragment_shader);
    g.glLinkProgram(r.program);
    g.glGetProgramiv(r.program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512]; GLsizei size = 0;
        g.glGetProgramInfoLog(r.program, sizeof log - 1, &size, log);
        if (size < 0) size = 0;
        if (size >= (GLsizei)sizeof log) size = sizeof log - 1;
        log[size] = 0;
        probe_log("SHADER_ERROR", log); goto cleanup;
    }
    position = g.glGetAttribLocation(r.program, "aPosition");
    uv = g.glGetAttribLocation(r.program, "aUV");
    depth_location = g.glGetUniformLocation(r.program, "uDepth");
    sampler = g.glGetUniformLocation(r.program, "uTexture");
    if (position < 0 || uv < 0 || depth_location < 0 || sampler < 0) {
        probe_log("SHADER_ERROR", "required shader input missing"); goto cleanup;
    }
    g.glUseProgram(r.program);
    g.glUniform1i(sampler, 0);
    g.glGenVertexArrays(1, &r.vao); g.glBindVertexArray(r.vao);
    g.glGenBuffers(1, &r.vbo); g.glBindBuffer(GL_ARRAY_BUFFER, r.vbo);
    g.glBufferData(GL_ARRAY_BUFFER, sizeof vertices, vertices, GL_STATIC_DRAW);
    g.glGenBuffers(1, &r.ebo); g.glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, r.ebo);
    g.glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof indices, indices, GL_STATIC_DRAW);
    g.glEnableVertexAttribArray((GLuint)position);
    g.glVertexAttribPointer((GLuint)position, 2, GL_FLOAT, GL_FALSE, 4*sizeof(GLfloat), (const void *)0);
    g.glEnableVertexAttribArray((GLuint)uv);
    g.glVertexAttribPointer((GLuint)uv, 2, GL_FLOAT, GL_FALSE, 4*sizeof(GLfloat), (const void *)(2*sizeof(GLfloat)));
    g.glActiveTexture(GL_TEXTURE0);
    g.glGenTextures(1, &r.texture); g.glBindTexture(GL_TEXTURE_2D, r.texture);
    g.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    g.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    g.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 2, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, texture_pixels);

    g.glGenFramebuffers(1, &incomplete); g.glBindFramebuffer(GL_FRAMEBUFFER, incomplete);
    if (g.glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
        probe_log("FBO_ERROR", "attachment-free framebuffer unexpectedly complete"); goto cleanup;
    }
    probe_log("FBO_NEGATIVE", "attachment-free framebuffer correctly incomplete");
    g.glDeleteFramebuffers(1, &incomplete); incomplete = 0;
    g.glGenTextures(1, &r.color); g.glBindTexture(GL_TEXTURE_2D, r.color);
    g.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    g.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    g.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    g.glGenRenderbuffers(1, &r.depth); g.glBindRenderbuffer(GL_RENDERBUFFER, r.depth);
    g.glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, 64, 64);
    g.glGenFramebuffers(1, &r.fbo); g.glBindFramebuffer(GL_FRAMEBUFFER, r.fbo);
    g.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, r.color, 0);
    g.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, r.depth);
    if (g.glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        probe_log("FBO_ERROR", "64x64 framebuffer incomplete"); goto cleanup;
    }
    g.glActiveTexture(GL_TEXTURE0); g.glBindTexture(GL_TEXTURE_2D, r.texture);
    g.glViewport(0,0,64,64); g.glClearColor(0,0,0,1);
    g.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    draw(&g, depth_location, 0.0f);
    if (pixel_check(&g, "triangle red texel", 32, 24, red, 0) ||
        pixel_check(&g, "triangle green texel", 16, 24, green, 0) ||
        pixel_check(&g, "triangle blue texel", 22, 48, blue, 0) ||
        pixel_check(&g, "triangle outside", 2, 2, black, 0)) goto cleanup;
    g.glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    g.glEnable(GL_DEPTH_TEST); g.glDepthFunc(GL_LESS);
    texture_color(&g, green); draw(&g, depth_location, -0.5f);
    texture_color(&g, blue); draw(&g, depth_location, 0.5f);
    if (pixel_check(&g, "depth ordering", 32, 24, green, 0)) goto cleanup;
    g.glDisable(GL_DEPTH_TEST);
    g.glClear(GL_COLOR_BUFFER_BIT);
    texture_color(&g, red); draw(&g, depth_location, 0.0f);
    g.glEnable(GL_BLEND);
    g.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,
                          GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    texture_color(&g, half_blue); draw(&g, depth_location, 0.0f);
    if (pixel_check(&g, "alpha blend", 32, 24, blended, 2)) goto cleanup;
    g.glDisable(GL_BLEND);
    if (gl_check(&g, "offscreen resource checks") != 0) goto cleanup;
    probe_log("PIXELS", "offscreen triangle/depth/blend/FBO checks passed");

    g.glBindFramebuffer(GL_FRAMEBUFFER, 0);
    g.glViewport(0,0,(GLsizei)ctx->width,(GLsizei)ctx->height);
    frame_state.g = &g; frame_state.depth_location = depth_location;
    if (probe_render_present_frames(ctx, frames, render_frame, &frame_state,
                                    &cpu_mean, &cpu_max) != 0) goto cleanup;
    g.glFinish();
    if (gl_check(&g, "finish after presentation") != 0) goto cleanup;
    snprintf(message, sizeof message, "frames=%u CPU frame mean=%.3f ms max=%.3f ms; GPU timing unavailable",
             frames < 600 ? 600 : frames, cpu_mean, cpu_max);
    probe_log("PRESENT", message);
    result = 0;
cleanup:
    if (g.glFinish) g.glFinish();
    if (incomplete) g.glDeleteFramebuffers(1, &incomplete);
    if (r.fbo) g.glDeleteFramebuffers(1, &r.fbo);
    if (r.depth) g.glDeleteRenderbuffers(1, &r.depth);
    if (r.color) g.glDeleteTextures(1, &r.color);
    if (r.texture) g.glDeleteTextures(1, &r.texture);
    if (r.ebo) g.glDeleteBuffers(1, &r.ebo);
    if (r.vbo) g.glDeleteBuffers(1, &r.vbo);
    if (r.vao) g.glDeleteVertexArrays(1, &r.vao);
    if (r.program) g.glDeleteProgram(r.program);
    if (r.vertex_shader) g.glDeleteShader(r.vertex_shader);
    if (r.fragment_shader) g.glDeleteShader(r.fragment_shader);
    g.glBindFramebuffer(GL_FRAMEBUFFER, 0);
    g.glUseProgram(0);
    g.glFinish();
    if (gl_check(&g, "cleanup") != 0) result = -1;
    return result;
}
