from pathlib import Path

source = Path("src/demo/Main.java").read_text()
assert 'System.getProperty("orbis.probe.root", "/data/orbis-jdk")' in source
print("Probe root override contract passed")
