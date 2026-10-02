int probe_prohibited(void) {
    return fstatat64(-1, "missing", 0, 0) + dl_iterate_phdr(0, 0);
}
