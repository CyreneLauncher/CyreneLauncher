from pathlib import Path

source = Path("tools/native_dlfcn.c").read_text()
builder = Path("tools/build_native_modules.py").read_text()
assert "__wrap_sceKernelDebugOutText" in source
assert "/data/orbis-jdk/logs/orbis-native.log" in source
assert "/data/orbis-jdk/logs/orbis-java.log" not in source
assert "orbis-native.log.1" in source
assert "'sceKernelDebugOutText'" in builder
assert "orbis-native: module load entered" in source
assert "orbis-native: module load returned handle=" in source
assert "orbis-native: module initializers entered" in source
assert "orbis-native: module initializers returned" in source
assert "orbis-native: symbol lookup entered name=" in source
assert "orbis-native: symbol lookup returned name=" in source
print("PRX trace redirection contract passed")
