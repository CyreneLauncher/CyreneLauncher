#ifndef ORBIS_RUNNER_STAT_H
#define ORBIS_RUNNER_STAT_H

#include <stddef.h>
#include <stdint.h>
#include <time.h>

/* libkernel's stat layout, matching the port's OrbisNativeStat in orbis_io.h.
 * The toolchain's struct stat uses different field widths and offsets. */
typedef struct OrbisRunnerStat {
    uint32_t dev;
    uint32_t ino;
    uint16_t mode;
    uint16_t nlink;
    uint32_t uid;
    uint32_t gid;
    uint32_t rdev;
    struct timespec atim;
    struct timespec mtim;
    struct timespec ctim;
    int64_t size;
    int64_t blocks;
    uint32_t blksize;
    uint32_t flags;
    uint32_t gen;
    int32_t lspare;
    struct timespec birthtim;
} OrbisRunnerStat;

#ifdef __cplusplus
static_assert(sizeof(OrbisRunnerStat) == 120, "Orbis stat ABI size");
static_assert(offsetof(OrbisRunnerStat, mode) == 8, "Orbis stat mode offset");
static_assert(offsetof(OrbisRunnerStat, size) == 72, "Orbis stat size offset");
static_assert(offsetof(OrbisRunnerStat, blocks) == 80, "Orbis stat blocks offset");
#endif

#endif
