#include "orbis_screen_console.h"

#include <orbis/Keyboard.h>

#include <pthread.h>
#include <stdint.h>
#include <string.h>

#include "orbis_display_service.h"
#include "orbis_input_service.h"
#include "orbis_screen_font.h"

namespace {
constexpr int kWidth = ORBIS_DISPLAY_WIDTH;
constexpr int kHeight = ORBIS_DISPLAY_HEIGHT;
constexpr int kCellSize = 8;
constexpr int kColumns = kWidth / kCellSize;
constexpr int kRows = kHeight / kCellSize;
constexpr int kLineBytes = kColumns;
constexpr int kInputLineBytes = 1024;

pthread_mutex_t console_lock = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t reader_lock = PTHREAD_MUTEX_INITIALIZER; // one active line reader
pthread_mutex_t input_lifecycle_lock = PTHREAD_MUTEX_INITIALIZER;
char lines[kRows][kLineBytes];
int cursor_column;
int cursor_row;
int top_row;
bool initialized;
bool input_started; // accessed under input_lifecycle_lock
bool shutdown_requested; // accessed under input_lifecycle_lock
unsigned char input_line[kInputLineBytes];
int input_line_length;
int committed_line_length;
int committed_line_position;

void next_line() {
    cursor_column = 0;
    cursor_row = (cursor_row + 1) % kRows;
    if (cursor_row == top_row) top_row = (top_row + 1) % kRows;
    memset(lines[cursor_row], ' ', sizeof(lines[cursor_row]));
}

void put_char(unsigned char ch) {
    if (ch == '\r') return;
    if (ch == '\n') { next_line(); return; }
    if (ch == '\t') {
        int spaces = 4 - (cursor_column & 3);
        while (spaces-- > 0) put_char(' ');
        return;
    }
    if (ch == '\b') {
        if (cursor_column > 0) {
            --cursor_column;
            lines[cursor_row][cursor_column] = ' ';
        }
        return;
    }
    if (ch < 32 || ch > 126) ch = '?';
    lines[cursor_row][cursor_column++] = static_cast<char>(ch);
    if (cursor_column >= kColumns) next_line();
}

int32_t render_console(uint32_t *pixels, uint32_t width, uint32_t height,
                       uint32_t stride, void *) {
    if (pixels == nullptr || width != kWidth || height != kHeight || stride < width)
        return -1;
    char snapshot[kRows][kLineBytes];
    int snapshot_top;
    pthread_mutex_lock(&console_lock);
    memcpy(snapshot, lines, sizeof(snapshot));
    snapshot_top = top_row;
    pthread_mutex_unlock(&console_lock);

    for (int y = 0; y < kHeight; ++y)
        for (int x = 0; x < kWidth; ++x)
            pixels[static_cast<size_t>(y) * stride + x] = 0xff080c12;
    for (int row = 0; row < kRows; ++row) {
        int line_index = (snapshot_top + row) % kRows;
        for (int col = 0; col < kColumns; ++col) {
            unsigned char ch = static_cast<unsigned char>(snapshot[line_index][col]);
            if (ch < 32 || ch > 126) continue;
            const uint8_t *glyph = orbis_screen_font[ch - 32];
            for (int y = 0; y < 8; ++y) {
                for (int x = 0; x < 8; ++x) {
                    if ((glyph[y] & (0x80 >> x)) != 0) {
                        size_t offset = static_cast<size_t>(row * 8 + y) * stride + col * 8 + x;
                        pixels[offset] = 0xffd8e4f0;
                    }
                }
            }
        }
    }
    return 0;
}

void reset_pending_input() {
    input_line_length = committed_line_length = committed_line_position = 0;
}

int read_byte_locked() {
    pthread_mutex_lock(&input_lifecycle_lock);
    bool stopped = shutdown_requested;
    pthread_mutex_unlock(&input_lifecycle_lock);
    if (stopped) return -1;
    if (committed_line_position < committed_line_length)
        return input_line[committed_line_position++];
    if (committed_line_length != 0) {
        committed_line_length = committed_line_position = input_line_length = 0;
    }
    pthread_mutex_lock(&input_lifecycle_lock);
    if (shutdown_requested) {
        pthread_mutex_unlock(&input_lifecycle_lock);
        return -1;
    }
    if (!input_started) {
        if (orbis_input_start() < 0) {
            pthread_mutex_unlock(&input_lifecycle_lock);
            return -1;
        }
        input_started = true;
        pthread_mutex_lock(&console_lock);
        put_char('>'); put_char(' ');
        pthread_mutex_unlock(&console_lock);
        orbis_display_mark_console_dirty();
    }
    pthread_mutex_unlock(&input_lifecycle_lock);
    for (;;) {
        orbis_input_event event{};
        int32_t result = orbis_input_read_event(ORBIS_INPUT_FOCUS_CONSOLE,
                                                &event, UINT32_MAX);
        if (result != 0) return -1;
        if (event.type == ORBIS_INPUT_EVENT_RESET ||
            event.type == ORBIS_INPUT_EVENT_FOCUS) {
            reset_pending_input();
            continue;
        }
        if (event.type != ORBIS_INPUT_EVENT_TEXT &&
            event.type != ORBIS_INPUT_EVENT_KEY_DOWN) continue;
        if (event.type == ORBIS_INPUT_EVENT_KEY_DOWN) {
            if (event.raw_key == ORBIS_KEYBOARD_KEY_BACKSPACE) {
                if (input_line_length > 0) {
                    --input_line_length;
                    pthread_mutex_lock(&console_lock);
                    put_char('\b');
                    pthread_mutex_unlock(&console_lock);
                    orbis_display_mark_console_dirty();
                }
                continue;
            }
            if (event.raw_key == ORBIS_KEYBOARD_KEY_RETURN) {
                input_line[input_line_length] = '\n';
                committed_line_length = input_line_length + 1;
                committed_line_position = 1;
                pthread_mutex_lock(&console_lock);
                put_char('\n');
                pthread_mutex_unlock(&console_lock);
                orbis_display_mark_console_dirty();
                return input_line[0];
            }
            continue;
        }
        uint32_t value = event.unicode_scalar;
        if (event.raw_key == ORBIS_KEYBOARD_KEY_BACKSPACE) {
            if (input_line_length > 0) {
                --input_line_length;
                pthread_mutex_lock(&console_lock);
                put_char('\b');
                pthread_mutex_unlock(&console_lock);
                orbis_display_mark_console_dirty();
            }
            continue;
        }
        if (value < 32 || value > 126 || input_line_length >= kInputLineBytes - 1)
            continue;
        input_line[input_line_length++] = static_cast<unsigned char>(value);
        pthread_mutex_lock(&console_lock);
        put_char(static_cast<unsigned char>(value));
        pthread_mutex_unlock(&console_lock);
        orbis_display_mark_console_dirty();
    }
}
}

extern "C" int orbis_screen_console_init(void) {
    pthread_mutex_lock(&console_lock);
    if (initialized) { pthread_mutex_unlock(&console_lock); return 0; }
    memset(lines, ' ', sizeof(lines));
    cursor_column = cursor_row = top_row = 0;
    pthread_mutex_unlock(&console_lock);
    if (orbis_display_set_console_renderer(render_console, nullptr) < 0 ||
        orbis_display_start() < 0) return -1;
    pthread_mutex_lock(&console_lock);
    initialized = true;
    pthread_mutex_unlock(&console_lock);
    return 0;
}

extern "C" void orbis_screen_console_shutdown(void) {
    pthread_mutex_lock(&input_lifecycle_lock);
    shutdown_requested = true;
    if (input_started) orbis_input_stop();
    input_started = false;
    pthread_mutex_unlock(&input_lifecycle_lock);
    pthread_mutex_lock(&reader_lock);
    reset_pending_input();
    pthread_mutex_unlock(&reader_lock);
    orbis_display_stop();
    pthread_mutex_lock(&console_lock);
    initialized = false;
    pthread_mutex_unlock(&console_lock);
}

extern "C" void orbis_screen_console_write(const char *bytes, int length) {
    if (bytes == nullptr || length <= 0) return;
    pthread_mutex_lock(&console_lock);
    for (int i = 0; i < length; ++i) put_char(static_cast<unsigned char>(bytes[i]));
    pthread_mutex_unlock(&console_lock);
    orbis_display_mark_console_dirty();
}

extern "C" void orbis_screen_console_flush(void) {
    orbis_display_mark_console_dirty();
}

extern "C" int orbis_screen_console_read_byte(void) {
    pthread_mutex_lock(&reader_lock);
    int result = read_byte_locked();
    pthread_mutex_unlock(&reader_lock);
    return result;
}
