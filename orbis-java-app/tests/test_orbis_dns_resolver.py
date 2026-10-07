#!/usr/bin/env python3
"""Keep the Orbis SceNet DNS adapter initialized and diagnosable."""

from pathlib import Path
import re


SOURCE = (Path(__file__).resolve().parents[2] /
          "src/java.base/unix/native/libnet/net_util_md.c").read_text()
start = SOURCE.index("static OrbisNetOnce orbis_net_init_once")
end = SOURCE.index("\n#endif", start)
resolver = SOURCE[start:end]

assert "sceNetPoolCreate" in resolver, "Orbis resolver needs a SceNet memory pool"
assert re.search(r"sceNetResolverCreate\(\s*\"openjdk\"\s*,\s*orbis_net_pool_id", resolver), \
    "resolver must receive its created pool ID"
assert "5000000" in resolver, "resolver timeout must allow a multi-second DNS query"
assert "sceNetResolverDestroy" in resolver, "resolver must be destroyed after lookup"
assert "orbis-net: DNS" in resolver, "native resolver failure stage/code must be logged"
inet4 = (Path(__file__).resolve().parents[2] /
         "src/java.base/unix/native/libnet/Inet4AddressImpl.c").read_text()
inet6 = (Path(__file__).resolve().parents[2] /
         "src/java.base/unix/native/libnet/Inet6AddressImpl.c").read_text()
for implementation in (inet4, inet6):
    assert "resolver_error" in implementation, \
        "native resolver detail must reach IPv4 and IPv6 Java exceptions"
policy = (Path(__file__).resolve().parents[1] / "tools/orbis_native_policy.json").read_text()
assert '"symbol": "sceNetPoolCreate"' in policy, \
    "native symbol audit policy must classify SceNet pool initialization"
print("Orbis DNS resolver initialization and diagnostics contract passed")
