import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / "tools/testdata/orbis_missing_errno_test.c"
include = root.parent / "src/java.base/unix/native/libnio/fs"
binary = Path(tempfile.mkdtemp(prefix="orbis-missing-errno-")) / "test"

result = subprocess.run(
    ["cc", "-std=c11", "-Wall", "-Werror", "-I", str(include),
     str(source), "-o", str(binary)],
    capture_output=True, text=True)
if result.returncode != 0:
    print(result.stdout, end="")
    print(result.stderr, end="")
    raise SystemExit(result.returncode)

subprocess.run([str(binary)], check=True)
print("Orbis missing-path errno normalization: PASS")
