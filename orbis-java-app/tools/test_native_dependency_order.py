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

print("Native dependency order: libnet resolves JNU from java and JVM_* from jvm; libnio resolves NET from net and JNU from java")
