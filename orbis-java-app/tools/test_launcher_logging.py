from pathlib import Path

source = Path("launcher.c").read_text()
for token in ("/data/orbis-jdk/logs/orbis-java.log", "orbis_log_open", "orbis_log_line", "orbis_log_close"):
    assert token in source, token
assert "before DestroyJavaVM" in source
assert "after DestroyJavaVM" in source
assert 'orbis_log_line("INFO", "launcher"' in source
assert 'orbis_log_line("ERROR", "launcher"' in source
print("Launcher logging source contract passed")
