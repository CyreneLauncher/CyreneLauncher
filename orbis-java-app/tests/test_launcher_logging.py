from pathlib import Path

source = Path("launcher.c").read_text()
for token in ("ORBIS_PROFILE_LOG_PATH", "orbis_log_open", "orbis_log_line", "orbis_log_close"):
    assert token in source, token
assert "before DestroyJavaVM" in source
assert "after DestroyJavaVM" in source
assert 'orbis_log_line("INFO", "launcher"' in source
assert 'orbis_log_line("ERROR", "launcher"' in source
makefile = Path("Makefile").read_text()
assert "--log-path /data/orbis-jdk/logs/orbis-runner.log" in makefile
assert "--log-path /data/orbis-jdk/runner/runner-output.log" not in makefile
assert "runner_config.output_log" in source
print("Launcher logging source contract passed")
