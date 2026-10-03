/* SPDX-License-Identifier: Zlib
 * Orbis unavailable-dialog implementation against tinyfiledialogs 3.3.9 interface.
 * Original stub; upstream interface/header in pinned LWJGL 3.2.2 source archive.
 */
#include "tinyfiledialogs.h"
#include <stddef.h>
#include <stdio.h>
const char tinyfd_version[8] = "3.3.9";
const char tinyfd_needs[] = "Orbis dialogs unavailable";
int tinyfd_verbose = 0;
int tinyfd_silent = 1;
int tinyfd_forceConsole = 1;
char tinyfd_response[1024] = "no_solution";
static int warned;
static void unavailable(void) {
    if (!warned) { warned = 1; fputs("[orbis-tinyfd] dialogs unavailable; returning cancel\n", stderr); }
}
void tinyfd_beep(void) { unavailable(); }
int tinyfd_notifyPopup(const char *title, const char *message, const char *icon) {
    (void)title; (void)message; (void)icon; unavailable(); return 0;
}
int tinyfd_messageBox(const char *title, const char *message, const char *type, const char *icon, int button) {
    (void)title; (void)message; (void)type; (void)icon; (void)button; unavailable(); return 0;
}
const char *tinyfd_inputBox(const char *title, const char *message, const char *input) {
    (void)title; (void)message; (void)input; unavailable(); return NULL;
}
const char *tinyfd_saveFileDialog(const char *title, const char *path, int count, const char *const *patterns, const char *description) {
    (void)title; (void)path; (void)count; (void)patterns; (void)description; unavailable(); return NULL;
}
const char *tinyfd_openFileDialog(const char *title, const char *path, int count, const char *const *patterns, const char *description, int multiple) {
    (void)title; (void)path; (void)count; (void)patterns; (void)description; (void)multiple; unavailable(); return NULL;
}
const char *tinyfd_selectFolderDialog(const char *title, const char *path) {
    (void)title; (void)path; unavailable(); return NULL;
}
const char *tinyfd_colorChooser(const char *title, const char *hex, const unsigned char rgb[3], unsigned char out[3]) {
    (void)title; (void)hex; (void)rgb; (void)out; unavailable(); return NULL;
}
