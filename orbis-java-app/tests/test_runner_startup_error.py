#!/usr/bin/env python3
"""A startup failure must publish buffered screen text before returning."""
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
source = (root/'launcher.c').read_text()
start = source.index('static void startup_error(const char *path) {')
end = source.index('\nstatic int require_readable_file', start)
with tempfile.TemporaryDirectory() as directory:
    td = Path(directory)
    unit = td/'error.c'
    unit.write_text('''#include <stdio.h>
#include <string.h>
#include <errno.h>
#define ORBIS_PROFILE_SCREEN_CONSOLE 1
static char buffered[1024], rendered[1024];
static int flushes;
static void debug_text(const char *text) { (void)text; }
static void orbis_log_line(const char *a, const char *b, const char *c, ...) { (void)a; (void)b; (void)c; }
static void orbis_screen_console_write(const char *text, size_t size) { strncat(buffered, text, size); }
void orbis_screen_console_flush(void) { strcpy(rendered, buffered); ++flushes; }
'''+source[start:end]+'''
int main(void) {
    startup_error("/external/jdk/lib/modules");
    if (strcmp(rendered, "Startup failed: /external/jdk/lib/modules\\n") || flushes != 1) {
        fprintf(stderr, "startup error was not rendered before returning\\n");
        return 1;
    }
    return 0;
}
''')
    executable=td/'error'
    subprocess.run(['cc','-std=c11','-Wall','-Wextra','-Werror',str(unit),'-o',str(executable)],check=True)
    subprocess.run([str(executable)],check=True)
print('Runner startup error rendering contract passed')
