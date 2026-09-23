#!/usr/bin/env python3
from pathlib import Path

source = Path("../src/java.base/unix/native/libnio/ch/Net.c").read_text()
marker = "#if defined(ORBIS) && defined(TCP_NODELAY)"
assert marker in source, "Orbis TCP_NODELAY fallback is missing"
section = source[source.index(marker):]
assert "errno == EACCES" in section, "Orbis EACCES handling is missing"
assert "level == IPPROTO_TCP" in section, "fallback must be limited to TCP options"
assert "opt == TCP_NODELAY" in section, "fallback must be limited to TCP_NODELAY"
print("Orbis NIO TCP_NODELAY fallback source: PASS")
