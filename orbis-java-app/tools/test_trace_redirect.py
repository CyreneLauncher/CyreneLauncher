from pathlib import Path

source = Path("tools/native_dlfcn.c").read_text()
builder = Path("tools/build_native_modules.py").read_text()
assert "__wrap_sceKernelDebugOutText" in source
assert "/data/orbis-jdk/logs/orbis-native.log" in source
assert "/data/orbis-jdk/logs/orbis-java.log" not in source
assert "orbis-native.log.1" in source
assert "'sceKernelDebugOutText'" in builder
print("PRX trace redirection contract passed")
