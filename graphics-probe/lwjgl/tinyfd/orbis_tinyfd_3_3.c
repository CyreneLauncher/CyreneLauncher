/* SPDX-License-Identifier: Zlib
 * Orbis unavailable-dialog implementation against the tinyfiledialogs interface LWJGL 3.3.3 binds
 * (globals reached through tinyfd_getGlobalChar/getGlobalInt/setGlobalInt; dialogs return char *).
 * Original stub like orbis_tinyfd.c, which keeps LWJGL 3.2.2's 3.3.9 interface; the upstream
 * header is in the pinned LWJGL 3.3.3 source archive.
 */
#include "tinyfiledialogs.h"
#include <stddef.h>
#include <stdio.h>
#include <string.h>
char tinyfd_version[8] = "3.13.3";
char tinyfd_needs[] = "Orbis dialogs unavailable";
int tinyfd_verbose = 0;
int tinyfd_silent = 1;
int tinyfd_allowCursesDialogs = 0;
int tinyfd_forceConsole = 1;
int tinyfd_assumeGraphicDisplay = 0;
int tinyfd_winUtf8 = 1;
char tinyfd_response[1024] = "no_solution";
static int warned;
static void unavailable(void) {
    if (!warned) { warned = 1; fputs("[orbis-tinyfd] dialogs unavailable; returning cancel\n", stderr); }
}
static int *global_int(const char *name) {
    if (!name) return NULL;
    if (!strcmp(name, "tinyfd_verbose")) return &tinyfd_verbose;
    if (!strcmp(name, "tinyfd_silent")) return &tinyfd_silent;
    if (!strcmp(name, "tinyfd_allowCursesDialogs")) return &tinyfd_allowCursesDialogs;
    if (!strcmp(name, "tinyfd_forceConsole")) return &tinyfd_forceConsole;
    if (!strcmp(name, "tinyfd_assumeGraphicDisplay")) return &tinyfd_assumeGraphicDisplay;
    if (!strcmp(name, "tinyfd_winUtf8")) return &tinyfd_winUtf8;
    return NULL;
}
char const *tinyfd_getGlobalChar(char const *name) {
    if (!name) return NULL;
    if (!strcmp(name, "tinyfd_version")) return tinyfd_version;
    if (!strcmp(name, "tinyfd_needs")) return tinyfd_needs;
    if (!strcmp(name, "tinyfd_response")) return tinyfd_response;
    return NULL;
}
int tinyfd_getGlobalInt(char const *name) {
    int *value = global_int(name);
    return value ? *value : -1;
}
int tinyfd_setGlobalInt(char const *name, int value) {
    int *target = global_int(name);
    if (!target) return -1;
    *target = value;
    return value;
}
void tinyfd_beep(void) { unavailable(); }
int tinyfd_notifyPopup(char const *title, char const *message, char const *icon) {
    (void)title; (void)message; (void)icon; unavailable(); return 0;
}
int tinyfd_messageBox(char const *title, char const *message, char const *type, char const *icon, int button) {
    (void)title; (void)message; (void)type; (void)icon; (void)button; unavailable(); return 0;
}
char *tinyfd_inputBox(char const *title, char const *message, char const *input) {
    (void)title; (void)message; (void)input; unavailable(); return NULL;
}
char *tinyfd_saveFileDialog(char const *title, char const *path, int count, char const *const *patterns, char const *description) {
    (void)title; (void)path; (void)count; (void)patterns; (void)description; unavailable(); return NULL;
}
char *tinyfd_openFileDialog(char const *title, char const *path, int count, char const *const *patterns, char const *description, int multiple) {
    (void)title; (void)path; (void)count; (void)patterns; (void)description; (void)multiple; unavailable(); return NULL;
}
char *tinyfd_selectFolderDialog(char const *title, char const *path) {
    (void)title; (void)path; unavailable(); return NULL;
}
char *tinyfd_colorChooser(char const *title, char const *hex, unsigned char const rgb[3], unsigned char out[3]) {
    (void)title; (void)hex; (void)rgb; (void)out; unavailable(); return NULL;
}
