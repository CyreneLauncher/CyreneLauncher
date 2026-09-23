from pathlib import Path

source = Path("launcher.c").read_text()
for token in ("--main=", "--no-park", "--allocator-probe",
              "--fatal-allocation-probe", "orbis_runtime_allocator_probe",
              "FindClass", "park_after_error"):
    assert token in source, token
assert "default_class = ORBIS_PROFILE_MAIN_CLASS" in source
assert "Java main completed" in source
print("Launcher mode contract passed")
