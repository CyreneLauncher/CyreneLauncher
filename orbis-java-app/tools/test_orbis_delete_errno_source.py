from pathlib import Path


source = Path("../src/java.base/unix/native/libnio/fs/UnixNativeDispatcher.c").read_text()
start = source.index("Java_sun_nio_fs_UnixNativeDispatcher_unlink0")
end = source.index("Java_sun_nio_fs_UnixNativeDispatcher_unlinkat0", start)
block = source[start:end]
assert "orbis_normalize_missing_errno(errno)" in block
rename_start = source.index("Java_sun_nio_fs_UnixNativeDispatcher_rename0")
rename_block = source[rename_start:]
assert "orbis_normalize_missing_errno(errno)" in rename_block
print("Orbis delete missing-path errno source contract: PASS")
