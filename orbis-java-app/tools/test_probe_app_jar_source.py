from pathlib import Path

source = Path("src/demo/Main.java").read_text()
assert 'System.getProperty("orbis.app.jar", "/app0/app.jar")' in source
assert "new JarFile(APP_JAR.toString())" in source
print("Probe application JAR override contract passed")
