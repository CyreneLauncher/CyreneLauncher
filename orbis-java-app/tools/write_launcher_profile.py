#!/usr/bin/env python3
"""Generate the compile-time launcher profile without shell quoting in C flags."""

import argparse
from pathlib import Path


def c_string(value):
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--main-class", required=True)
parser.add_argument("--user-dir", required=True)
parser.add_argument("--log-path", required=True)
parser.add_argument("--tmp-dir", required=True)
parser.add_argument("--java-option", action="append", default=[])
args = parser.parse_args()

base_options = [
    "-Djava.home=/app0/java",
    "-Djava.class.path=/app0/app.jar",
    "-Djava.library.path=/app0:/app0/java/lib/server:/app0/java/lib",
    f"-Djava.io.tmpdir={args.tmp_dir}",
    f"-Duser.dir={args.user_dir}",
    "-Xms32m",
    "-Xmx256m",
    "-XX:CompressedClassSpaceSize=32m",
]
options = base_options + args.java_option
rotated_path = args.log_path + ".1"

lines = [
    "#ifndef ORBIS_LAUNCHER_PROFILE_H",
    "#define ORBIS_LAUNCHER_PROFILE_H",
    f"#define ORBIS_PROFILE_MAIN_CLASS {c_string(args.main_class)}",
    f"#define ORBIS_PROFILE_USER_DIR {c_string(args.user_dir)}",
    f"#define ORBIS_PROFILE_LOG_PATH {c_string(args.log_path)}",
    f"#define ORBIS_PROFILE_ROTATED_LOG_PATH {c_string(rotated_path)}",
    f"#define ORBIS_PROFILE_TMP_DIR {c_string(args.tmp_dir)}",
    "static const char *const orbis_profile_vm_options[] = {",
]
lines.extend(f"    {c_string(option)}," for option in options)
lines.extend([
    "};",
    f"#define ORBIS_PROFILE_VM_OPTION_COUNT {len(options)}",
    "#endif",
    "",
])

args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text("\n".join(lines), encoding="utf-8")
