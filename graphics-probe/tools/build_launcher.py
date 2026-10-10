#!/usr/bin/env python3
"""Build orbis-launcher.jar, the launcher screen and game handoff (graphics-probe/launcher/java).

It is a second Java JAR of the graphics bundle, on the class path after the adapter JAR. It is
compiled against the official LWJGL 3.2.2 JARs and orbis.graphics.NativePlatform, which the
adapter JAR provides at run time, so neither is packaged here.
"""
import argparse
import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import launcher_secrets  # noqa: E402
import lwjgl_sources  # noqa: E402
import launcher_deps  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'launcher/java'

parser = argparse.ArgumentParser()
parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
parser.add_argument('--jars', default='/tmp/lwjgl-audit', help='official LWJGL 3.2.2 Java JARs')
parser.add_argument('--output', default=str(ROOT / 'build/launcher'))
# Gson ships with Minecraft (one of its libraries), so it is on the class path at run time.
parser.add_argument('--gson', default=str(ROOT / 'build/minecraft-1.18.2/libraries/com/google/code/gson/gson/2.8.9/gson-2.8.9.jar'))
# The Mixin API Fabric and Quilt bundle; the compatibility mod compiles against it.
parser.add_argument('--mixin', default=str(ROOT / 'build/minecraft-1.18.2-fabric/libraries/net/fabricmc/sponge-mixin/'
                                           '0.17.4+mixin.0.8.7/sponge-mixin-0.17.4+mixin.0.8.7.jar'))
# ASM's tree API, which Fabric and Quilt also ship; the mixin config plugin's interface names ClassNode.
parser.add_argument('--asm-tree', default=str(ROOT / 'build/minecraft-1.18.2-fabric/libraries/org/ow2/asm/asm-tree/'
                                              '9.10.1/asm-tree-9.10.1.jar'))
# A JDK 22+ for the compatibility mod's Java 22 part (launcher/compat/java22: the foreign function API).
parser.add_argument('--java22-jdk', default=str(ROOT.parent.parent / 'jdk25u/.boot-jdks/jdk-25'))
arguments = parser.parse_args()
out = Path(arguments.output).resolve()
classes = out / 'classes'
if classes.exists():
    import shutil
    shutil.rmtree(classes)
classes.mkdir(parents=True)

# TwelveMonkeys (WebP for Modrinth images) is merged into the launcher JAR; see launcher/twelvemonkeys.lock.json.
third_party = launcher_deps.jars()
classpath = ':'.join(str(lwjgl_sources.java_artifact(arguments.jars, name)) for name in
                     ('lwjgl-3.2.2.jar', 'lwjgl-opengl-3.2.2.jar', 'lwjgl-glfw-3.2.2.jar')) + ':' + arguments.gson
classpath += ''.join(':' + str(path) for path in third_party)
qr_lock = json.loads((ROOT / 'launcher/qr-sources.lock.json').read_text())
font_lock = json.loads((ROOT / 'launcher/fonts/inter.lock.json').read_text())
for name, expected in font_lock['generated_files'].items():
    resource = ROOT / 'launcher/resources/orbis/launcher/fonts' / name
    if hashlib.sha256(resource.read_bytes()).hexdigest() != expected:
        raise SystemExit(f'bundled font resource differs from its lock: {name}')
for name, pinned in qr_lock['files'].items():
    path = SOURCES / 'orbis/launcher/qr' / name
    if hashlib.sha256(path.read_bytes()).hexdigest() != pinned['vendored_sha256']:
        raise SystemExit(f'vendored QR source differs from its lock: {name}')
own = sorted(SOURCES.rglob('*.java'))
subprocess.run([str(Path(arguments.boot_jdk) / 'bin/javac'), '--release', '17', '-encoding', 'UTF-8',
                '-Xlint:all', '-Werror', '-cp', classpath, '-sourcepath', str(ROOT / 'lwjgl/java'),
                '-implicit:none', '-d', str(classes), *map(str, own)], check=True)
# The compatibility mod (launcher/compat): Java 8 classes for every Fabric and Quilt version offered.
compat_classes = out / 'compat-classes'
if compat_classes.exists():
    import shutil
    shutil.rmtree(compat_classes)
compat_classes.mkdir(parents=True)
subprocess.run([str(Path(arguments.boot_jdk) / 'bin/javac'), '--release', '8', '-encoding', 'UTF-8',
                '-Xlint:all,-options', '-Werror', '-proc:none', '-cp', arguments.mixin + ':' + arguments.asm_tree, '-d', str(compat_classes),
                *map(str, sorted((ROOT / 'launcher/compat/java').rglob('*.java')))], check=True)
# Loaded only on Java 22+ (orbis.compat.GamepadFeed checks the runtime first): Minecraft 26.1 runs on 25.
java22 = Path(arguments.java22_jdk) / 'bin/javac'
if not java22.is_file():
    raise SystemExit(f'no JDK 22+ for launcher/compat/java22 at {arguments.java22_jdk}; pass --java22-jdk')
subprocess.run([str(java22), '--release', '22', '-encoding', 'UTF-8', '-Xlint:all,-options', '-Werror', '-proc:none',
                '-d', str(compat_classes), *map(str, sorted((ROOT / 'launcher/compat/java22').rglob('*.java')))], check=True)
compat_jar = out / 'orbis-compat.jar'
with zipfile.ZipFile(compat_jar, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted((ROOT / 'launcher/compat/resources').iterdir()):
        archive.write(path, path.name)
    for path in sorted(compat_classes.rglob('*.class')):
        archive.write(path, path.relative_to(compat_classes).as_posix())

jar = out / 'orbis-launcher.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
    for resource in sorted((ROOT / 'launcher/resources').rglob('*')):
        if resource.is_file():
            archive.write(resource, resource.relative_to(ROOT / 'launcher/resources').as_posix())
    archive.write(compat_jar, 'orbis/launcher/orbis-compat.jar')
    for path in sorted(classes.rglob('*.class')):
        name = path.relative_to(classes).as_posix()
        if not name.startswith('orbis/launcher/'):
            raise SystemExit(f'unexpected class in the launcher: {name}')
        archive.write(path, name)
    archive.write(ROOT / 'launcher/legal/nayuki-qr-LICENSE.txt', 'META-INF/licenses/nayuki-qr-LICENSE.txt')
    archive.write(ROOT / 'launcher/qr-sources.lock.json', 'META-INF/qr-sources.lock.json')
    # Only their classes: no META-INF/services, so the game's ImageIO does not gain these plugins.
    merged = 0
    for path in third_party:
        with zipfile.ZipFile(path) as source:
            for entry in source.infolist():
                if entry.is_dir() or not entry.filename.endswith('.class'):
                    continue
                if not entry.filename.startswith('com/twelvemonkeys/'):
                    raise SystemExit(f'unexpected class in {path.name}: {entry.filename}')
                archive.writestr(entry.filename, source.read(entry))
                merged += 1
    archive.write(ROOT / 'launcher/legal/twelvemonkeys-LICENSE.txt', 'META-INF/licenses/twelvemonkeys-LICENSE.txt')
    archive.write(ROOT / 'launcher/twelvemonkeys.lock.json', 'META-INF/twelvemonkeys.lock.json')
    # The Microsoft application ID: ORBIS_MSA_CLIENT_ID or the untracked secrets.env, never the sources.
    secret = launcher_secrets.resource()
    if secret:
        archive.writestr(*secret)
    else:
        print('launcher: no ORBIS_MSA_CLIENT_ID; sign-in reads microsoft-auth.properties (development build)')
print(f'launcher: {jar.name}, {sum(1 for _ in classes.rglob("*.class"))} classes, {merged} TwelveMonkeys classes')
