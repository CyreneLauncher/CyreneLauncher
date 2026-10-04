#!/usr/bin/env python3
"""Build a pinned Mesa/Orbis stack in an isolated output directory."""

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


BUILD_ROOT = Path(__file__).resolve().parents[1] / "build"


def inside_build(path):
    resolved = path.resolve()
    if resolved == BUILD_ROOT.resolve() or BUILD_ROOT.resolve() not in resolved.parents:
        raise ValueError("outside build root: " + str(path))
    return resolved


LOCK = Path(__file__).resolve().parents[1] / "deps.lock.json"


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


# Port fixes on top of the pinned Mesa revision. The checkout stays at the pin and clean; the patches are
# applied for the build only and reverted afterwards, and their hashes go into the build manifest.
MESA_PATCHES = Path(__file__).resolve().parents[1] / "patches/mesa"


def mesa_patches():
    return sorted(MESA_PATCHES.glob("*.patch"))


def keep_unchanged_mtimes(mesa, state_file):
    """Applying and reverting the patches rewrites every patched file, and ninja would recompile
    everything depending on them - a fifth of Mesa, since gl_API.xml is among them. A patched file
    whose content matches the last build gets that build's modification time back, so only real
    changes rebuild. Only the latest content is remembered: going back to older content keeps the
    new time and rebuilds."""
    try:
        recorded = json.loads(state_file.read_text())
    except (OSError, ValueError):
        recorded = {}
    patched = subprocess.check_output(["git", "-C", str(mesa), "diff", "--name-only"], text=True).split()
    state = {}
    for relative in patched:
        path = mesa / relative
        digest = sha256(path)
        previous = recorded.get(relative)
        if previous and previous["sha256"] == digest:
            os.utime(path, ns=(path.stat().st_atime_ns, previous["mtime_ns"]))
        state[relative] = {"sha256": digest, "mtime_ns": path.stat().st_mtime_ns}
    state_file.write_text(json.dumps(state, indent=2) + "\n")


def checked_source(path, revision, name=None):
    if not path.is_dir():
        raise ValueError("missing source: " + str(path))
    actual = subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"],
                                     text=True).strip()
    if actual != revision:
        raise ValueError("revision mismatch: " + str(path))
    status = subprocess.check_output(["git", "-C", str(path), "status", "--porcelain",
                                      "--untracked-files=all"], text=True)
    if status:
        raise ValueError("dirty source: " + str(path) + "\n" + status)
    ignored = subprocess.check_output(["git", "-C", str(path), "ls-files", "--others",
                                       "--ignored", "--exclude-standard"], text=True)
    for relative in ignored.splitlines():
        allowed = (name == "mesa" and
                   (relative.startswith("subprojects/zlib-1.3.1/") or
                    relative.startswith("subprojects/packagecache/") or
                    "/__pycache__/" in relative)) or (name == "orbis-compat" and
                                                         relative.startswith("build/"))
        if not allowed:
            raise ValueError("dirty source: ignored input " + str(path / relative))


def directory_digest(root):
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise ValueError("unexpected symlink in fallback: " + str(path))
        if path.is_file():
            digest.update(path.relative_to(root).as_posix().encode())
            digest.update(b"\0")
            digest.update(bytes.fromhex(sha256(path)))
    return digest.hexdigest()


def payload_digest(root):
    digest = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        digest.update(path.relative_to(root).as_posix().encode() + b"\0")
        if path.is_symlink():
            digest.update(b"L" + os.readlink(path).encode() + b"\0")
        elif path.is_file():
            digest.update(b"F" + bytes.fromhex(sha256(path)))
        elif path.is_dir():
            digest.update(b"D")
    return digest.hexdigest()


def verify_support_tree(root, expected):
    if not root.is_dir() or payload_digest(root) != expected:
        raise ValueError("support tree hash mismatch: " + str(root))


def verify_zlib(mesa, lock):
    fallback = mesa / "subprojects/zlib-1.3.1"
    if not fallback.is_dir():
        raise ValueError("missing zlib fallback")
    cache = mesa / "subprojects/packagecache"
    for key, hash_key in (("source_filename", "source_sha256"),
                          ("patch_filename", "patch_sha256")):
        path = cache / lock["zlib"][key]
        if not path.is_file() or sha256(path) != lock["zlib"][hash_key]:
            raise ValueError("zlib hash mismatch: " + str(path))
    if directory_digest(fallback) != lock["zlib"]["tree_sha256"]:
        raise ValueError("zlib tree hash mismatch")


def verify_compat_archive(path, expected):
    if not path.is_file() or sha256(path) != expected:
        raise ValueError("compat archive hash mismatch: " + str(path))


def libc16_objects(compat):
    objects = sorted((Path(compat) / "build/libc16").glob("libc16_*.o"))
    required = {"libc16_mbrtowc.o", "libc16_mbtowc.o", "libc16_regcomp.o",
                "libc16_regexec.o", "libc16_vfscanf.o"}
    if not required <= {path.name for path in objects} or not all(path.is_file() for path in objects):
        raise ValueError("missing libc16 objects from orbis-compat")
    return objects


def augment_cross_template(template, compat, objects):
    needle = "'-lorbis-compat','-lc'"
    if template.count(needle) != 2:
        raise ValueError("unexpected Mesa cross link arguments for libc16")
    archive = Path(compat) / "build/liborbis-compat.a"
    replacement = ",".join(repr(token) for token in
                           ("-Wl,--whole-archive", str(archive), "-Wl,--no-whole-archive",
                            *(str(path) for path in objects), "-lc"))
    return template.replace(needle, replacement)


def augment_probe_link(tokens, compat, objects):
    if tokens.count("-lorbis-compat") != 1 or "-lc" not in tokens:
        raise ValueError("unexpected Mesa probe link arguments for libc16")
    archive = Path(compat) / "build/liborbis-compat.a"
    index = tokens.index("-lorbis-compat")
    if tokens.index("-lc") < index:
        raise ValueError("libc16 objects must precede libc")
    return [*tokens[:index], "-Wl,--whole-archive", str(archive),
            "-Wl,--no-whole-archive", *(str(path) for path in objects),
            *tokens[index + 1:]]


def verify_dependencies(deps, lock):
    deps = inside_build(Path(deps))
    manifest_path = deps / "bootstrap-manifest.json"
    if not manifest_path.is_file():
        raise ValueError("bootstrap manifest mismatch: missing " + str(manifest_path))
    manifest = json.loads(manifest_path.read_text())
    if (manifest.get("python_wheels") != lock["python_wheels"] or
            manifest.get("flex_deb") != lock["host_dependencies"]["flex_deb"] or
            manifest.get("bison_deb") != lock["host_dependencies"]["bison_deb"]):
        raise ValueError("bootstrap manifest mismatch")
    wheels = Path(manifest.get("wheel_directory", "")).resolve()
    if BUILD_ROOT.resolve() not in wheels.parents:
        raise ValueError("bootstrap manifest mismatch: wheel directory")
    for filename, expected in lock["python_wheels"].items():
        wheel = wheels / filename
        if not wheel.is_file() or sha256(wheel) != expected:
            raise ValueError("installed dependency hash mismatch: " + str(wheel))
    for package in ("flex", "bison"):
        matches = list((deps / "debs").glob(package + "_*.deb"))
        expected = lock["host_dependencies"][package + "_deb"]["sha256"]
        if len(matches) != 1 or sha256(matches[0]) != expected:
            raise ValueError("installed dependency hash mismatch: " + package + " archive")
    for relative, expected in lock["host_dependencies"]["installed_tool_sha256"].items():
        path = deps / relative
        if not path.is_file() or sha256(path) != expected:
            raise ValueError("installed dependency hash mismatch: " + str(path))
    verify_support_tree(deps / "host",
                        lock["host_dependencies"]["extracted_payload_sha256"])
    site = deps / f"venv/lib/python{sys.version_info.major}.{sys.version_info.minor}/site-packages"
    if not site.is_dir():
        raise ValueError("installed dependency hash mismatch: site-packages")
    for filename in lock["python_wheels"]:
        with zipfile.ZipFile(wheels / filename) as wheel:
            for member in wheel.infolist():
                if member.is_dir() or member.filename.endswith(".dist-info/RECORD"):
                    continue
                wheel_path = member.filename
                if ".data/" in wheel_path:
                    category, relative = wheel_path.split(".data/", 1)[1].split("/", 1)
                    if category == "data":
                        installed = deps / "venv" / relative
                    elif category == "scripts":
                        installed = deps / "venv/bin" / relative
                    elif category in ("purelib", "platlib"):
                        installed = site / relative
                    else:
                        raise ValueError("installed dependency hash mismatch: unknown wheel data")
                else:
                    installed = site / wheel_path
                if not installed.is_file() or sha256(installed) != hashlib.sha256(
                        wheel.read(member)).hexdigest():
                    raise ValueError("installed dependency hash mismatch: " + str(installed))
    entry = deps / "venv/bin/meson"
    if not entry.is_file():
        raise ValueError("installed dependency hash mismatch: " + str(entry))
    lines = entry.read_text().splitlines(keepends=True)
    if (not lines or lines[0] != f"#!{deps}/venv/bin/python\n" or
            hashlib.sha256("".join(lines[1:]).encode()).hexdigest() !=
            lock["host_dependencies"]["meson_entry_body_sha256"]):
        raise ValueError("installed dependency hash mismatch: " + str(entry))
    return manifest


def run(command, log, *, cwd=None, env=None):
    with Path(log).open("w") as output:
        output.write("COMMAND: " + " ".join(map(str, command)) + "\n")
        output.flush()
        result = subprocess.run(list(map(str, command)), cwd=cwd, env=env,
                                stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"command failed ({result.returncode}); see {log}")


def build(sources, sdk, output):
    sources = inside_build(Path(sources))
    output = inside_build(Path(output))
    sdk = Path(sdk).resolve()
    if not (sdk / "link.x").is_file():
        raise ValueError("missing SDK link.x: " + str(sdk))
    lock = json.loads(LOCK.read_text())
    for relative, expected in lock["sdk"]["sha256"].items():
        sdk_file = sdk / relative
        if not sdk_file.is_file() or sha256(sdk_file) != expected:
            raise ValueError("SDK hash mismatch: " + relative)
    for name, item in lock["sources"].items():
        checked_source(sources / name, item["revision"], name)
    mesa = sources / "mesa"
    compat = sources / "orbis-compat"
    kit = sources / "orbis-porting-kit"
    compat_archive = compat / "build/liborbis-compat.a"
    verify_compat_archive(compat_archive, lock["compat_archive_sha256"])
    libc16 = libc16_objects(compat)
    deps = BUILD_ROOT / "deps/clean"
    dependency_manifest = verify_dependencies(deps, lock)
    venv = deps / "venv/bin"
    hostbin = deps / "host/usr/bin"
    for executable in (venv / "meson", hostbin / "bison", hostbin / "flex"):
        if not executable.is_file():
            raise ValueError("missing task-local dependency: " + str(executable))
    output.mkdir(parents=True, exist_ok=True)
    cross_dir = output / "cross"
    (cross_dir / "lib/pkgconfig").mkdir(parents=True, exist_ok=True)
    template = augment_cross_template((kit / "cmake/orbis.ini.in").read_text(), compat, libc16)
    replacements = {
        "@OO_PS4_TOOLCHAIN@": str(sdk), "@ORBIS_CROSS@": str(cross_dir),
        "@ORBIS_COMPAT@": str(compat), "@ORBIS_KIT@": str(kit),
        "@ORBIS_CC@": shutil.which("clang") or "clang",
        "@ORBIS_CXX@": shutil.which("clang++") or "clang++",
        "@ORBIS_AR@": shutil.which("llvm-ar") or "llvm-ar",
        "@ORBIS_STRIP@": shutil.which("llvm-strip") or "strip",
    }
    for marker, value in replacements.items():
        template = template.replace(marker, value)
    if re.search(r"@[A-Z_]+@", template):
        raise ValueError("unresolved cross file substitution")
    cross = cross_dir / "orbis.ini"
    # Rewriting an unchanged cross file would make ninja regenerate the whole build.
    if not cross.is_file() or cross.read_text() != template:
        cross.write_text(template)
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join((str(venv), str(hostbin), env.get("PATH", "")))
    env.update({"PKG_CONFIG_PATH": "", "PKG_CONFIG_LIBDIR": str(cross_dir / "lib/pkgconfig"),
                "PKG_CONFIG_SYSROOT_DIR": str(sdk), "CMAKE_PREFIX_PATH": "",
                "BISON_PKGDATADIR": str(deps / "host/usr/share/bison"),
                "FLEX_SKELETON": str(deps / "host/usr/share/flex/flex.skl"),
                "PIP_CACHE_DIR": str(deps / "pip-cache")})
    env.pop("MESON_PACKAGE_CACHE_DIR", None)
    manifest = {"sources": {name: item["revision"] for name, item in lock["sources"].items()},
                "sdk": str(sdk), "sdk_hashes": {}, "tools": {}, "archives": {},
                "dependency_bootstrap": dependency_manifest,
                "compat_archive_sha256": sha256(compat_archive),
                "libc16_objects": {str(path.relative_to(compat)): sha256(path) for path in libc16},
                "status": "started"}
    for relative in ("link.x", "include/pthread.h", "lib/libc.a", "lib/libc++.a", "lib/crt1.o"):
        path = sdk / relative
        if path.is_file():
            manifest["sdk_hashes"][relative] = sha256(path)
    for name in ("clang", "ld.lld", "llvm-ar", "llvm-nm", "llvm-readelf", "ninja", "meson", "bison", "flex"):
        executable = shutil.which(name, path=env["PATH"])
        if executable:
            version = subprocess.run([executable, "--version"], capture_output=True,
                                     text=True).stdout.splitlines()
            manifest["tools"][name] = {"path": executable,
                                       "version": version[0] if version else "unknown"}
    applied = []
    try:
        for patch in mesa_patches():
            subprocess.run(["git", "-C", str(mesa), "apply", "--check", str(patch)], check=True)
            subprocess.run(["git", "-C", str(mesa), "apply", str(patch)], check=True)
            applied.append(patch)
            manifest.setdefault("mesa_patches", {})[patch.name] = sha256(patch)
        keep_unchanged_mtimes(mesa, output / "patched-files.json")
        if not (mesa / "subprojects/zlib-1.3.1").is_dir():
            run([venv / "meson", "subprojects", "download", "zlib"],
                output / "zlib-download.log", cwd=mesa, env=env)
        verify_zlib(mesa, lock)
        build_dir = output / "mesa"
        build_id = hashlib.sha256(lock["sources"]["mesa"]["revision"].encode()).hexdigest()[:40]
        options = ["-Ddefault_library=static", "-Dwrap_mode=nodownload", "-Dzstd=disabled",
                   "-Dvalgrind=disabled", "-Dlmsensors=disabled", "-Dlibunwind=disabled",
                   "-Dvulkan-drivers=amd", "-Dgallium-drivers=zink", "-Dopengl=true",
                   "-Dgles1=enabled", "-Dgles2=enabled", "-Degl=enabled", "-Dglx=disabled",
                   "-Dgbm=disabled", "-Dglvnd=disabled", "-Dvideo-codecs=", "-Dllvm=disabled",
                   "-Dxmlconfig=disabled", "-Dbuildtype=release", "-Dplatforms=orbis",
                   "-Dzlib=enabled", "-Dshader-cache=enabled", "-Dforce_fallback_for=zlib",
                   "-Dradv-build-id=" + build_id]
        setup = [venv / "meson", "setup", build_dir, mesa]
        configured = (build_dir / "meson-info/meson-info.json").is_file()
        if configured:
            setup.append("--reconfigure")
        # ninja reruns Meson by itself when a meson.build file changes; an explicit reconfigure is only
        # needed when the options, cross file or Meson change, and costs a minute every build otherwise.
        configuration = hashlib.sha256(json.dumps(
            [[str(x) for x in setup[:2]], options, template, manifest["tools"].get("meson")]).encode()).hexdigest()
        configuration_stamp = output / "meson-configuration.sha256"
        if not (configured and (build_dir / "build.ninja").is_file() and configuration_stamp.is_file()
                and configuration_stamp.read_text().strip() == configuration):
            configuration_stamp.unlink(missing_ok=True)
            run([*setup, "--cross-file", cross, *options],
                output / "meson-setup.log", env=env)
            configuration_stamp.write_text(configuration + "\n")
        meson_log = (build_dir / "meson-logs/meson-log.txt").read_text()
        if 'Checking for function "sysconf" : YES' not in meson_log:
            raise RuntimeError("sysconf configure probe failed; see meson-setup.log")
        if re.search(r"(?:libdrm|libelf|libstdc\+\+) found: YES", meson_log, re.I):
            raise RuntimeError("host dependency found in Meson configuration")
        run(["ninja", "-C", build_dir, "-j", str(os.cpu_count() or 2)], output / "ninja-build.log", env=env)
        probe = output / "gllinkprobe.elf"
        initial_probe = output / "gllinkprobe-before-libc16.elf"
        env["ORBIS_COMPAT_DIR"] = str(compat)
        env["ORBIS_KIT_DIR"] = str(kit)
        run(["bash", "-x", mesa / "build-support/orbis/tools/gllinkprobe.sh",
             build_dir, sdk, initial_probe],
            output / "gllinkprobe.log", cwd=mesa, env=env)
        traced = (output / "gllinkprobe.log").read_text().splitlines()
        links = [shlex.split(line[2:]) for line in traced
                 if line.startswith("+ clang ") and "--whole-archive" in line]
        if len(links) != 1:
            raise RuntimeError("could not capture exactly one target link command")
        link_tokens = augment_probe_link(links[0][1:], compat, libc16)
        if link_tokens.count(str(initial_probe)) != 1:
            raise RuntimeError("Mesa probe output path missing from traced link")
        link_tokens[link_tokens.index(str(initial_probe))] = str(probe)
        why_extract = output / "gllinkprobe-why-extract.txt"
        link_tokens.insert(0, f"-Wl,--why-extract={why_extract}")
        run(["clang", *link_tokens], output / "gllinkprobe-corrected.log", cwd=mesa, env=env)
        initial_probe.unlink()
        run(["sh", kit / "scripts/ps4/check-wchar-link.sh", why_extract],
            output / "gllinkprobe-wchar-audit.log", env=env)
        link_inputs = output / "link-inputs.json"
        link_inputs.write_text(json.dumps(link_tokens, indent=2) + "\n")
        run([sys.executable, Path(__file__).with_name("audit_link.py"),
             "--check-link-inputs", link_inputs, "--cwd", mesa,
             "--sdk", sdk, "--build-root", BUILD_ROOT],
            output / "link-input-audit.log", env=env)
        archives = [compat_archive] + [Path(token) for token in link_tokens if token.endswith(".a") and
                    Path(token).is_file() and BUILD_ROOT.resolve() in Path(token).resolve().parents]
        for path in (build_dir / "src/egl/libEGL.a",
                     build_dir / "src/mesa/glapi/es2api/libGLESv2.a",
                     build_dir / "src/amd/vulkan/libvulkan_radeon.a", probe):
            if not path.is_file():
                raise RuntimeError("missing build artifact: " + str(path))
            manifest["archives"][str(path.relative_to(output))] = {
                "sha256": sha256(path), "bytes": path.stat().st_size}
        run([sys.executable, Path(__file__).with_name("audit_link.py"),
             *[arg for archive in archives for arg in ("--archive", archive)],
             "--elf", probe], output / "symbol-audit.log", env=env)
        manifest["audits"] = {"link_inputs": str(link_inputs.relative_to(output)),
                              "symbols": "symbol-audit.log",
                              "wchar": "gllinkprobe-wchar-audit.log"}
        manifest["status"] = "success"
    except Exception as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        raise
    finally:
        for patch in reversed(applied):
            subprocess.run(["git", "-C", str(mesa), "apply", "-R", str(patch)], check=True)
        (output / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources")
    parser.add_argument("--sdk")
    parser.add_argument("--output")
    parser.add_argument("--check-compat")
    parser.add_argument("--verify-deps")
    parser.add_argument("--verify-support-tree")
    args = parser.parse_args()
    try:
        lock = json.loads(LOCK.read_text())
        if args.check_compat:
            verify_compat_archive(Path(args.check_compat), lock["compat_archive_sha256"])
            return 0
        if args.verify_deps:
            verify_dependencies(Path(args.verify_deps), lock)
            return 0
        if args.verify_support_tree:
            verify_support_tree(Path(args.verify_support_tree),
                                lock["host_dependencies"]["extracted_payload_sha256"])
            return 0
        if not all((args.sources, args.sdk, args.output)):
            parser.error("--sources, --sdk, and --output are required")
        build(args.sources, args.sdk, args.output)
    except (ValueError, OSError, RuntimeError, subprocess.CalledProcessError) as error:
        if args.output:
            try:
                destination = inside_build(Path(args.output))
                destination.mkdir(parents=True, exist_ok=True)
                manifest_path = destination / "build-manifest.json"
                old_status = None
                if manifest_path.is_file():
                    try:
                        old_status = json.loads(manifest_path.read_text()).get("status")
                    except (ValueError, OSError):
                        pass
                if old_status != "failed":
                    manifest_path.write_text(json.dumps({"status": "failed",
                                                         "error": str(error)}, indent=2) + "\n")
            except (OSError, ValueError):
                pass
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
