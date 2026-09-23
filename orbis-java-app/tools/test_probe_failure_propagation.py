from pathlib import Path

source = Path("src/demo/Main.java").read_text()
assert "probeFailed = log.fail.get() != 0" in source
assert 'throw new IllegalStateException("probe failures recorded")' in source
print("Probe failure propagation contract passed")
