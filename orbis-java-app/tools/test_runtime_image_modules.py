#!/usr/bin/env python3
"""Compare the generated jlink image with the declared module selection."""

import os
from pathlib import Path
import subprocess


makefile = Path("Makefile").read_text()
selected = set(makefile.split("RUNTIME_MODULES ?=", 1)[1]
               .splitlines()[0].strip().split(","))
image = Path("build/runtime-image/lib/modules")
if not image.is_file():
    raise SystemExit(f"runtime image is missing: {image}")

jimage = Path(os.environ.get("JIMAGE", "/tmp/jdk17-orbis/bin/jimage"))
output = subprocess.run(
    [str(jimage), "list", str(image)], check=True, text=True,
    stdout=subprocess.PIPE).stdout
actual = {line.removeprefix("Module: ").strip()
          for line in output.splitlines() if line.startswith("Module: ")}

missing = sorted(selected - actual)
unexpected = sorted(actual - selected)
if missing or unexpected:
    raise SystemExit(f"runtime module mismatch: missing={missing} unexpected={unexpected}")

print(f"Runtime image module contract passed: {len(actual)} modules")
