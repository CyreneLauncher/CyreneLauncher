from pathlib import Path

source = Path("src/demo/Main.java").read_text()
for token in (
    "probe.vm", "probe.file", "probe.thread", "probe.network",
    "probe.args", "probe.reflection", "probe.concurrent", "probe.jar",
    "probe.memory", "probe.digest", "probe.endurance", "probe.logging",
    "java.util.logging", "Logger", "JarFile", "ExecutorService",
    "MessageDigest", "DocumentBuilderFactory", "HttpClient", "DriverManager",
    "XAException", "Selector.open", "SocketChannel", "probe.selector",
    "probe.nio", "probe.xml", "probe.http", "probe.sql", "probe.xa",
    "channel.size()", "channel.read(positioned, 0)",
    "joinWallElapsed", "joinElapsed * 3 < joinWallElapsed",
    "PASS", "SKIP", "FAIL",
):
    assert token in source, token
assert "catch (Throwable" in source
print("Orbis probe source contract passed")
