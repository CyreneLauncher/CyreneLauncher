from pathlib import Path

makefile = Path("Makefile").read_text()
assert ("RUNTIME_MODULES ?= java.base,java.logging,java.xml,java.net.http,"
        "java.transaction.xa,java.sql,java.naming,java.security.jgss,"
        "java.security.sasl,java.sql.rowset,java.xml.crypto,java.scripting,"
        "java.rmi,jdk.charsets,jdk.localedata,jdk.random,jdk.unsupported,"
        "jdk.zipfs,java.compiler,jdk.httpserver,jdk.naming.dns,jdk.naming.rmi,"
        "jdk.net,jdk.nio.mapmode,jdk.security.auth,jdk.security.jgss,jdk.xml.dom,"
        "jdk.dynalink") in makefile
assert "runtime-jmods.stamp" in makefile
assert "$(JMOD) create" in makefile
assert "module_list" in makefile
assert "$(BUILD)/runtime-jmods.stamp" in makefile
selection = makefile.split("RUNTIME_MODULES ?=", 1)[1].splitlines()[0].strip().split(",")
assert len(selection) == 28, f"expected 28 runtime modules, found {len(selection)}"
assert len(selection) == len(set(selection)), "runtime module list contains duplicates"
print("Runtime module selection contract passed")
