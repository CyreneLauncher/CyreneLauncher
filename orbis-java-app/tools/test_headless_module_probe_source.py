from pathlib import Path

makefile = Path("Makefile").read_text()
source = Path("src/demo/Main.java").read_text()

selected = [
    "java.naming",
    "java.security.jgss",
    "java.security.sasl",
    "java.sql.rowset",
    "java.xml.crypto",
    "java.scripting",
    "java.rmi",
    "jdk.charsets",
    "jdk.localedata",
    "jdk.random",
    "jdk.unsupported",
    "jdk.zipfs",
    "java.compiler",
    "jdk.httpserver",
    "jdk.naming.dns",
    "jdk.naming.rmi",
    "jdk.net",
    "jdk.nio.mapmode",
    "jdk.security.auth",
    "jdk.security.jgss",
    "jdk.xml.dom",
    "jdk.dynalink",
]
for module in selected:
    assert module in makefile, f"selected module missing: {module}"

for probe in [
    "probe.naming",
    "probe.gss",
    "probe.sasl",
    "probe.xmlcrypto",
    "probe.rowset",
    "probe.scripting",
    "probe.rmi",
    "probe.charsets",
    "probe.localedata",
    "probe.random",
    "probe.unsupported",
    "probe.zipfs",
    "probe.compilerApi",
    "probe.httpserver",
    "probe.namingDns",
    "probe.namingRmi",
    "probe.extendedNetwork",
    "probe.mapmode",
    "probe.securityAuth",
    "probe.extendedGss",
    "probe.xmlDom",
    "probe.dynalink",
]:
    assert probe in source, f"runtime probe missing: {probe}"

for excluded in [
    "java.desktop",
    "java.management",
    "jdk.compiler",
    "jdk.jdi",
    "jdk.jdwp.agent",
    "jdk.jfr",
]:
    assert excluded not in makefile.split("RUNTIME_MODULES ?=", 1)[1].split("\n", 1)[0]

print("Headless module and probe source contract passed")
