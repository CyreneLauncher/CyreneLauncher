#!/usr/bin/env python3
"""Package the isolated Mesa probe; no installation or network side effects."""

import argparse
import hashlib
import json
import shutil
import subprocess
from datetime import datetime
from pathlib import Path
from xml.sax.saxutils import quoteattr

ROOT = Path(__file__).resolve().parents[1]
TITLE_ID = "OGFX00001"
CONTENT_ID = "IV0000-OGFX00001_00-ORBISMESAPROBE01"
TITLE = "Orbis Mesa Graphics Probe"
VERSION = "01.00"
PAID = "0x3800000000000011"
PAYLOAD_FILES = frozenset({
    "eboot.bin", "sce_sys/param.sfo", "sce_sys/icon0.png",
    "sce_sys/about/right.sprx", "sce_module/libc.prx",
    "sce_module/libSceFios2.prx",
})
PFS_FILES = frozenset({
    "/uroot/eboot.bin", "/uroot/sce_sys/keystone",
    "/uroot/sce_sys/about/right.sprx", "/uroot/sce_module/libc.prx",
    "/uroot/sce_module/libSceFios2.prx",
})
OUTPUT_FILES = frozenset({
    f"{CONTENT_ID}.pkg", "build-manifest.json", "pkg-build.log",
    "pkg-validation.log", "pkg-files.log",
})


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def check_stage(stage, *, gp4=False):
    stage = Path(stage)
    expected = PAYLOAD_FILES | ({"package.gp4"} if gp4 else set())
    actual = {p.relative_to(stage).as_posix() for p in stage.rglob("*") if p.is_file() or p.is_symlink()}
    if actual != expected:
        raise ValueError(f"stage inventory mismatch: missing={sorted(expected - actual)} extra={sorted(actual - expected)}")
    if any(p.is_symlink() for p in stage.rglob("*")):
        raise ValueError("stage contains symlink")
    if (stage / "eboot.bin").read_bytes()[:4] != b"\x4f\x15\x3d\x1d":
        raise ValueError("eboot.bin is not SELF")
    return sorted(actual)


def check_pkg_listing(listing):
    actual = {line.strip() for line in listing.splitlines() if line.strip()}
    if actual != PFS_FILES:
        raise ValueError(f"PKG runtime inventory mismatch: missing={sorted(PFS_FILES - actual)} extra={sorted(actual - PFS_FILES)}")
    return sorted(actual)


def check_output(output):
    output = Path(output)
    actual = {p.relative_to(output).as_posix() for p in output.rglob("*") if p.is_file() or p.is_symlink()}
    if actual != OUTPUT_FILES:
        raise ValueError(f"output inventory mismatch: missing={sorted(OUTPUT_FILES - actual)} extra={sorted(actual - OUTPUT_FILES)}")
    if any(p.is_symlink() for p in output.rglob("*")):
        raise ValueError("output contains symlink")
    return sorted(actual)


def check_sdk_identity(mesa_manifest, sdk):
    recorded = mesa_manifest.get("sdk")
    if not recorded or Path(recorded).resolve() != Path(sdk).resolve():
        raise ValueError(f"Mesa SDK {recorded!r} differs from package SDK {str(sdk)!r}")


def wchar_audit_provenance(target):
    target = Path(target)
    evidence = ("probe-why-extract.txt", "probe-wchar-audit.log")
    if any(not (target / name).is_file() for name in evidence):
        raise ValueError("missing standalone wchar audit evidence")
    if "check-wchar-link: no 16-bit wchar_t libc.a member extracted" not in (
        target / "probe-wchar-audit.log").read_text():
        raise ValueError("standalone wchar audit did not pass")
    return {name: digest(target / name) for name in evidence}


def run(*argv, cwd=None):
    return subprocess.run([str(arg) for arg in argv], cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout


def make_gp4(stage):
    files = sorted(PAYLOAD_FILES)
    folders = {str(Path(name).parent).replace("\\", "/") for name in files if "/" in name}
    folders |= {"sce_sys", "sce_sys/about", "sce_module"}
    tree = {}
    for folder in sorted(folders):
        branch = tree
        for part in folder.split("/"):
            branch = branch.setdefault(part, {})

    def directory(name, children, indent):
        if not children:
            return [f'{indent}<dir targ_name={quoteattr(name)} />']
        lines = [f'{indent}<dir targ_name={quoteattr(name)}>']
        for child, descendants in sorted(children.items()):
            lines += directory(child, descendants, indent + "\t")
        return lines + [f"{indent}</dir>"]

    dirs = []
    for folder, children in sorted(tree.items()):
        dirs += directory(folder, children, "\t\t")
    xml = [
        '<?xml version="1.0"?>',
        '<psproject xmlns:xsd="http://www.w3.org/2001/XMLSchema" '
        'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" fmt="gp4" version="1000">',
        '\t<volume>',
        '\t\t<volume_type>pkg_ps4_app</volume_type>',
        '\t\t<volume_id>PS4VOLUME</volume_id>',
        f'\t\t<volume_ts>{datetime.now():%Y-%m-%d %H:%M:%S}</volume_ts>',
        f'\t\t<package content_id={quoteattr(CONTENT_ID)} '
        'passcode="00000000000000000000000000000000" '
        'storage_type="digital50" app_type="full" />',
        '\t\t<chunk_info chunk_count="1" scenario_count="1">',
        '\t\t\t<chunks><chunk id="0" layer_no="0" label="Chunk #0" /></chunks>',
        '\t\t\t<scenarios default_id="0"><scenario id="0" type="sp" '
        'initial_chunk_count="1" label="Scenario #0">0</scenario></scenarios>',
        '\t\t</chunk_info>',
        '\t</volume>',
        '\t<files img_no="0">',
        *(f'\t\t<file targ_path={quoteattr(name)} orig_path={quoteattr(name)} />' for name in files),
        '\t</files>',
        '\t<rootdir>', *dirs, '\t</rootdir>', '</psproject>', '',
    ]
    (stage / "package.gp4").write_text("\n".join(xml), encoding="utf-8")


def set_sfo(dotnet, pkgtool, path, key, kind, size, value):
    run(dotnet, pkgtool, "sfo_setentry", path, key, "--type", kind,
        "--maxsize", size, "--value", value)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk", type=Path, default=Path("/home/wonder/PS4Toolchain"))
    parser.add_argument("--pkgtool", type=Path, default=Path("/home/wonder/pkgtool-net8/PkgTool.Core.dll"))
    parser.add_argument("--dotnet", default="dotnet")
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    build = ROOT / "build"
    target = build / "target"
    stage = build / "package-stage"
    output = build / "output"
    elf = target / "graphics-probe.elf"
    mesa_manifest = target / "build-manifest.json"
    if TITLE_ID == "OJDK00003" or "OJDK00003" in CONTENT_ID:
        raise ValueError("graphics probe identity collides with runner")
    if not elf.is_file() or not mesa_manifest.is_file():
        raise ValueError("build target ELF and Mesa manifest before packaging")
    provenance = json.loads(mesa_manifest.read_text())
    if provenance.get("status") != "success":
        raise ValueError("Mesa build manifest is not successful")
    check_sdk_identity(provenance, sdk)
    wchar_audit = wchar_audit_provenance(target)
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir(parents=True)
    output.mkdir(parents=True, exist_ok=True)
    fself = sdk / "bin/linux/create-fself"
    run(fself, f"-in={elf}", f"-out={target / 'graphics-probe.oelf'}",
        "--eboot", stage / "eboot.bin", "--paid", PAID)
    sample = sdk / "samples/hello_world"
    copies = {
        "sce_sys/icon0.png": sample / "sce_sys/icon0.png",
        "sce_sys/about/right.sprx": sample / "sce_sys/about/right.sprx",
        "sce_module/libc.prx": sample / "sce_module/libc.prx",
        "sce_module/libSceFios2.prx": sample / "sce_module/libSceFios2.prx",
    }
    for relative, source in copies.items():
        dest = stage / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
    sfo = stage / "sce_sys/param.sfo"
    run(args.dotnet, args.pkgtool, "sfo_new", sfo)
    entries = [
        ("APP_TYPE", "Integer", "4", "1"),
        ("APP_VER", "Utf8", "8", VERSION),
        ("ATTRIBUTE", "Integer", "4", "0"),
        ("CATEGORY", "Utf8", "4", "gd"),
        ("CONTENT_ID", "Utf8", "48", CONTENT_ID),
        ("DOWNLOAD_DATA_SIZE", "Integer", "4", "0"),
        ("SYSTEM_VER", "Integer", "4", "0"),
        ("TITLE", "Utf8", "128", TITLE),
        ("TITLE_ID", "Utf8", "12", TITLE_ID),
        ("VERSION", "Utf8", "8", VERSION),
    ]
    for entry in entries:
        set_sfo(args.dotnet, args.pkgtool, sfo, *entry)
    inventory = check_stage(stage)
    make_gp4(stage)
    check_stage(stage, gp4=True)
    pkg = output / f"{CONTENT_ID}.pkg"
    if pkg.exists():
        pkg.unlink()
    build_log = run(args.dotnet, args.pkgtool, "pkg_build", stage / "package.gp4", output, cwd=stage)
    validation = run(args.dotnet, args.pkgtool, "pkg_validate", "--verbose", pkg)
    listing = run(args.dotnet, args.pkgtool, "pkg_listfiles", pkg)
    pkg_inventory = check_pkg_listing(listing)
    (output / "pkg-build.log").write_text(build_log)
    (output / "pkg-validation.log").write_text(validation)
    (output / "pkg-files.log").write_text(listing)
    source_files = sorted((*((ROOT / "src").glob("*.c")), *((ROOT / "src").glob("*.h"))))
    manifest = {
        "title_id": TITLE_ID,
        "content_id": CONTENT_ID,
        "version": VERSION,
        "stage_inventory": inventory,
        "pkg_runtime_inventory": pkg_inventory,
        "output_inventory": sorted(OUTPUT_FILES),
        "stage_sha256": {name: digest(stage / name) for name in inventory},
        "package": {"path": str(pkg), "bytes": pkg.stat().st_size, "sha256": digest(pkg)},
        "target_elf_sha256": digest(elf),
        "source_sha256": {path.relative_to(ROOT).as_posix(): digest(path) for path in source_files},
        "mesa_sources": provenance["sources"],
        "mesa_sdk_hashes": provenance["sdk_hashes"],
        "mesa_libc16_sha256": provenance["libc16_objects"],
        "standalone_wchar_audit_sha256": wchar_audit,
        "sdk": str(sdk),
        "pkgtool_sha256": digest(args.pkgtool),
        "fself_sha256": digest(fself),
        "validation": "pkg_validate passed",
    }
    (output / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    check_output(output)
    print(f"package: {pkg} ({pkg.stat().st_size} bytes, sha256 {manifest['package']['sha256']})")


if __name__ == "__main__":
    main()
