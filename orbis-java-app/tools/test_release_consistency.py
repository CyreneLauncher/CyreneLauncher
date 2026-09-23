import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
makefile = (root / "Makefile").read_text()
launcher = (root / "launcher.c").read_text()
readme = (root / "README.md").read_text()

version = re.search(r"^VERSION\s*\?=\s*(\S+)$", makefile, re.MULTILINE)
assert version, "Makefile VERSION missing"
version = version.group(1)
assert "ORBIS_JAVA_BUILD_VERSION" in makefile
assert "ORBIS_JAVA_BUILD_VERSION" in launcher
assert "loader build 1." not in launcher
assert f"Build {version}" in readme
for stale in ("Build 1.67", "Build 1.18", "Build 1.17"):
    assert stale not in readme, stale
print(f"Release consistency contract passed: {version}")
