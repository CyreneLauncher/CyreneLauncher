#ifndef ORBIS_SCREEN_CONSOLE_H
#define ORBIS_SCREEN_CONSOLE_H

#ifdef __cplusplus
extern "C" {
#endif

int orbis_screen_console_init(void);
void orbis_screen_console_shutdown(void);
void orbis_screen_console_write(const char *bytes, int length);
void orbis_screen_console_flush(void);
int orbis_screen_console_read_byte(void);

#ifdef __cplusplus
}
#endif

#endif
