from pathlib import Path

java = Path("src/demo/Main.java").read_text()
launcher = Path("launcher.c").read_text()
assert 'LOG_LIMIT = 256 * 1024' in java
assert 'orbis-java.log.1' in java
assert 'LOG_LIMIT = 256 * 1024' in launcher or 'ORBis_LOG_LIMIT = 256 * 1024' in launcher
assert 'orbis-java.log.1' in launcher
print("Orbis probe logging bounds contract passed")
