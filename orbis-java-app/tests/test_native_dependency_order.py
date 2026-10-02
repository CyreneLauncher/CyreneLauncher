#!/usr/bin/env python3
"""Keep PRX dependency scanning ordered from leaf provider to base provider."""

from pathlib import Path


source = Path("tools/build_native_modules.py").read_text()
expected = "('nio', ['net', 'java'])"
if expected not in source:
    raise SystemExit("libnio dependencies must be ordered net, java for PRX symbol ownership")

expected = "('net', ['java', 'jvm'])"
if expected not in source:
    raise SystemExit("libnet dependencies must be ordered java, jvm for JVM_* symbol ownership")

for expected, message in (
    ("('management', ['jvm'])", "libmanagement must keep JNI utility imports local"),
    ("('management_ext', ['jvm'])", "libmanagement_ext must keep JNI utility imports local"),
):
    if expected not in source:
        raise SystemExit(message)

for module in ("management", "management_ext", "jaas"):
    assert f"'{module}'" in source
assert '--profile' not in source
assert "audit_root/'launcher.elf'" not in source
print("Generic native dependency ownership contract passed")
