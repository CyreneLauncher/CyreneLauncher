#!/usr/bin/env python3
"""Build the Orbis LWJGL adapter JAR for one LWJGL release and enforce its binary contract.

Contents, placed ahead of the official lwjgl-<release>.jar and instead of lwjgl-glfw-<release>.jar
(3.2.2 for Minecraft up to 1.18.2, 3.3.3 for 1.19 to 1.21.11, 3.4.1 for 26.1 and 26.2):
  * org.lwjgl.system.Platform from the release's source, accepting os.name "Orbis" as Linux;
  * every org.lwjgl.glfw class copied byte-for-byte from the official lwjgl-glfw JAR,
    except GLFW/GLFW$Functions (generated) and Callbacks (replaced), and for 3.2.2 the classes
    rebuilt with LWJGL 3.3 members newer Minecraft calls;
  * orbis.graphics.NativePlatform, the platform PRX's Java side.
GLFW.java is generated from the official GLFW.class: all constants, GLFW$Functions and
every exported method with its exact descriptor. Methods OrbisGLFW implements with the
same descriptor delegate there; the rest throw UnsupportedOperationException naming the
method. The build fails unless the JAR's exported members equal the official ones and
every required method is implemented.
"""
import argparse
import contextlib
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import classfile  # noqa: E402
import lwjgl_sources  # noqa: E402

ROOT = lwjgl_sources.ROOT
RUNNER = ROOT.parent / 'orbis-java-app'
FACADE = ROOT / 'lwjgl/facade/java'
# GLFW calls with LWJGL 3.4-only types (OrbisGLFW34); compiled for 3.4 releases alone.
FACADE_34 = ROOT / 'lwjgl/facade/java-3.4'
# Minecraft 1.18.2's direct GLFW references (graphics-probe/results/lwjgl-abi-audit.md) plus
# the runner smoke test's. Each must reach OrbisGLFW, never an unsupported stub.
REQUIRED = {
    'glfwCreateWindow (IILjava/lang/CharSequence;JJ)J', 'glfwDefaultWindowHints ()V',
    'glfwDestroyWindow (J)V', 'glfwGetClipboardString (J)Ljava/lang/String;', 'glfwGetCurrentContext ()J',
    'glfwGetError (Lorg/lwjgl/PointerBuffer;)I', 'glfwGetFramebufferSize (J[I[I)V', 'glfwGetKey (JI)I',
    'glfwGetKeyName (II)Ljava/lang/String;', 'glfwGetMonitorPos (J[I[I)V',
    'glfwGetMonitors ()Lorg/lwjgl/PointerBuffer;', 'glfwGetPrimaryMonitor ()J', 'glfwGetTime ()D',
    'glfwGetVideoMode (J)Lorg/lwjgl/glfw/GLFWVidMode;', 'glfwGetVideoModes (J)Lorg/lwjgl/glfw/GLFWVidMode$Buffer;',
    'glfwGetWindowMonitor (J)J', 'glfwGetWindowPos (J[I[I)V', 'glfwInit ()Z', 'glfwMakeContextCurrent (J)V',
    'glfwPollEvents ()V',
    'glfwSetCharModsCallback (JLorg/lwjgl/glfw/GLFWCharModsCallbackI;)Lorg/lwjgl/glfw/GLFWCharModsCallback;',
    'glfwSetClipboardString (JLjava/nio/ByteBuffer;)V',
    'glfwSetCursorEnterCallback (JLorg/lwjgl/glfw/GLFWCursorEnterCallbackI;)Lorg/lwjgl/glfw/GLFWCursorEnterCallback;',
    'glfwSetCursorPos (JDD)V',
    'glfwSetCursorPosCallback (JLorg/lwjgl/glfw/GLFWCursorPosCallbackI;)Lorg/lwjgl/glfw/GLFWCursorPosCallback;',
    'glfwSetDropCallback (JLorg/lwjgl/glfw/GLFWDropCallbackI;)Lorg/lwjgl/glfw/GLFWDropCallback;',
    'glfwSetErrorCallback (Lorg/lwjgl/glfw/GLFWErrorCallbackI;)Lorg/lwjgl/glfw/GLFWErrorCallback;',
    'glfwSetFramebufferSizeCallback (JLorg/lwjgl/glfw/GLFWFramebufferSizeCallbackI;)Lorg/lwjgl/glfw/GLFWFramebufferSizeCallback;',
    'glfwSetInputMode (JII)V',
    'glfwSetKeyCallback (JLorg/lwjgl/glfw/GLFWKeyCallbackI;)Lorg/lwjgl/glfw/GLFWKeyCallback;',
    'glfwSetMonitorCallback (Lorg/lwjgl/glfw/GLFWMonitorCallbackI;)Lorg/lwjgl/glfw/GLFWMonitorCallback;',
    'glfwSetMouseButtonCallback (JLorg/lwjgl/glfw/GLFWMouseButtonCallbackI;)Lorg/lwjgl/glfw/GLFWMouseButtonCallback;',
    'glfwSetScrollCallback (JLorg/lwjgl/glfw/GLFWScrollCallbackI;)Lorg/lwjgl/glfw/GLFWScrollCallback;',
    'glfwSetWindowFocusCallback (JLorg/lwjgl/glfw/GLFWWindowFocusCallbackI;)Lorg/lwjgl/glfw/GLFWWindowFocusCallback;',
    'glfwSetWindowIcon (JLorg/lwjgl/glfw/GLFWImage$Buffer;)V', 'glfwSetWindowMonitor (JJIIIII)V',
    'glfwSetWindowPosCallback (JLorg/lwjgl/glfw/GLFWWindowPosCallbackI;)Lorg/lwjgl/glfw/GLFWWindowPosCallback;',
    'glfwSetWindowSizeCallback (JLorg/lwjgl/glfw/GLFWWindowSizeCallbackI;)Lorg/lwjgl/glfw/GLFWWindowSizeCallback;',
    'glfwSetWindowTitle (JLjava/lang/CharSequence;)V', 'glfwSwapBuffers (J)V', 'glfwSwapInterval (I)V',
    'glfwTerminate ()V', 'glfwWaitEventsTimeout (D)V', 'glfwWindowHint (II)V', 'glfwWindowShouldClose (J)Z',
    # Smoke test and common LWJGL application set.
    'glfwShowWindow (J)V', 'glfwSetWindowShouldClose (JZ)V', 'glfwGetWindowSize (J[I[I)V',
    'glfwGetProcAddress (Ljava/lang/CharSequence;)J', 'glfwGetVersionString ()Ljava/lang/String;',
    'glfwSetWindowCloseCallback (JLorg/lwjgl/glfw/GLFWWindowCloseCallbackI;)Lorg/lwjgl/glfw/GLFWWindowCloseCallback;',
    'glfwGetMouseButton (JI)I', 'glfwGetCursorPos (J[D[D)V',
    # Generic policy/ownership exercised by the compatibility probe and host facade fixture.
    'glfwGetInputMode (JI)I', 'glfwGetWindowAttrib (JI)I',
    'glfwSetCharCallback (JLorg/lwjgl/glfw/GLFWCharCallbackI;)Lorg/lwjgl/glfw/GLFWCharCallback;',
}
# GLFW 3.4 calls newer Minecraft makes while running on the LWJGL 3.2.2 classes (1.20.2+ asks
# glfwGetPlatform before setting the window icon). The only exported members beyond 3.2.2's GLFW;
# each must be implemented by OrbisGLFW. LWJGL 3.3.3's GLFW has it already.
ADDITIONS_3_2_2 = {('glfwGetPlatform', '()I')}
REQUIRED |= {'glfwGetPlatform ()I'}
# LWJGL 3.3 members Minecraft 1.19.3+ calls on other GLFW classes. For 3.2.2 those classes are rebuilt
# from the pinned sources with each member inserted before its anchor; nothing else in them changes.
SOURCE_ADDITIONS_3_2_2 = {
    'org/lwjgl/glfw/GLFWImage': (
        '    public static class Buffer extends StructBuffer<GLFWImage, Buffer> implements NativeResource {',
        '    /** LWJGL 3.3\'s name for {@link #mallocStack(int, MemoryStack)} (Minecraft 1.19.3+ window icons). */\n'
        '    public static GLFWImage.Buffer malloc(int capacity, MemoryStack stack) { return mallocStack(capacity, stack); }\n\n',
        {('method', 'malloc', '(ILorg/lwjgl/system/MemoryStack;)Lorg/lwjgl/glfw/GLFWImage$Buffer;', True)}),
    'org/lwjgl/glfw/GLFWNativeGLX': (
        None,
        '    /** LWJGL 3.3: where GLFW finds libGL for GLX (Minecraft 1.20.2+). Orbis contexts come from EGL: no effect. */\n'
        '    public static void setPath(FunctionProvider sharedLibrary) { }\n',
        {('method', 'setPath', '(Lorg/lwjgl/system/FunctionProvider;)V', True)}),
}

parser = argparse.ArgumentParser()
parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
parser.add_argument('--release', choices=('3.2.2', '3.3.3', '3.4.1'), default='3.2.2')
parser.add_argument('--jars', default='/tmp/lwjgl-audit', help='official LWJGL Java JARs of the release')
parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
parser.add_argument('--fetch', action='store_true')
parser.add_argument('--output', help='default build/lwjgl-facade (3.2.2) or build/lwjgl-facade-<release>')
arguments = parser.parse_args()
release = arguments.release
legacy = release == '3.2.2'
if arguments.output is None:
    arguments.output = str(ROOT / ('build/lwjgl-facade' if legacy else f'build/lwjgl-facade-{release}'))
ADDITIONS = ADDITIONS_3_2_2 if legacy else set()
SOURCE_ADDITIONS = SOURCE_ADDITIONS_3_2_2 if legacy else {}
javac = Path(arguments.boot_jdk) / 'bin/javac'
out = Path(arguments.output).resolve()
if out.exists():
    shutil.rmtree(out)
out.mkdir(parents=True)

core_jar = lwjgl_sources.java_artifact(arguments.jars, f'lwjgl-{release}.jar')
opengl_jar = lwjgl_sources.java_artifact(arguments.jars, f'lwjgl-opengl-{release}.jar')
glfw_jar = lwjgl_sources.java_artifact(arguments.jars, f'lwjgl-glfw-{release}.jar')
source_key = 'lwjgl' if legacy else f'lwjgl-{release}'
source_root = lwjgl_sources.extract(lwjgl_sources.archive(source_key, arguments.downloads, arguments.fetch),
                                    out / 'sources') / 'modules/lwjgl'
with zipfile.ZipFile(glfw_jar) as archive:
    official = {name: classfile.parse(archive.read(name)) for name in archive.namelist()
                if name.endswith('.class')}
with zipfile.ZipFile(core_jar) as archive:
    official_platform = classfile.parse(archive.read('org/lwjgl/system/Platform.class'))
glfw = official['org/lwjgl/glfw/GLFW.class']
functions = official['org/lwjgl/glfw/GLFW$Functions.class']

# Platform: the only change is accepting os.name "Orbis" on the Linux path.
platform_source = (source_root / 'core/src/main/java/org/lwjgl/system/Platform.java').read_text()
# 3.2.2/3.3.3 test Linux and FreeBSD together; 3.4.1 has a FreeBSD platform of its own. Orbis takes the
# Linux path either way (the natives are built with LWJGL_LINUX).
anchor = next((a for a in ('osName.startsWith("Linux") || osName.startsWith("FreeBSD")',
                           'osName.startsWith("Linux") || osName.startsWith("SunOS")')
               if platform_source.count(a) == 1), None)
if anchor is None:
    raise SystemExit('unexpected Platform.java; inspect the os.name switch')
platform = out / 'generated/org/lwjgl/system/Platform.java'
platform.parent.mkdir(parents=True)
platform_source = platform_source.replace(anchor, 'osName.startsWith("Orbis") || ' + anchor)


def without_nullable(text):
    """JSR-305 (3.2.2, 3.3.3) and JSpecify (3.4.1) nullness annotations are documentation only (no effect on
    members or linkage); neither is on this class path."""
    for package in ('javax.annotation', 'org.jspecify.annotations'):
        text = text.replace(f'import {package}.*;\n', '')
    for annotation in ('@Nullable', '@NonNull', '@NullMarked', '@NullUnmarked'):
        text = text.replace(annotation + ' ', '').replace(annotation + '\n', '\n')
    return text


platform.write_text(without_nullable(platform_source))

patched_sources = []
glfw_sources = (lwjgl_sources.archive('lwjgl-glfw-sources', arguments.downloads, arguments.fetch)
                if SOURCE_ADDITIONS else None)
with (zipfile.ZipFile(glfw_sources) if glfw_sources else contextlib.nullcontext()) as archive:
    for name, (before, member, _) in SOURCE_ADDITIONS.items():
        text = archive.read(name + '.java').decode()
        if before is None:
            end = text.rstrip().rfind('}')
            text = text[:end] + '\n' + member + text[end:]
        else:
            if text.count(before) != 1:
                raise SystemExit(f'unexpected {name}.java; inspect the insertion anchor')
            text = text.replace(before, member + before)
        text = without_nullable(text)
        target = out / 'generated' / (name + '.java')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)
        patched_sources.append(target)


def literal(member):
    value = member.constant
    if member.descriptor == 'J':
        return f'{value}L'
    if member.descriptor == 'F':
        return f'{value!r}f'
    if member.descriptor == 'D':
        return repr(value)
    if member.descriptor == 'Ljava/lang/String;':
        return json.dumps(value)
    if member.descriptor == 'Z':
        return 'true' if value else 'false'
    return str(value)


def generate(implemented):
    lines = ['package org.lwjgl.glfw;', '',
             f'/** Generated from LWJGL {release} GLFW.class by build_lwjgl_facade.py; implementation: OrbisGLFW. */',
             '@SuppressWarnings("all")', 'public class GLFW {']
    for field in glfw.fields:
        if not field.exported:
            continue
        if field.constant is None:
            raise SystemExit(f'GLFW.{field.name} is not a compile-time constant; extend the generator')
        lines.append(f'    public static final {classfile.java_type(field.descriptor)} {field.name} = {literal(field)};')
    lines += ['', '    /** Native addresses are not used by the Orbis facade. */',
              '    public static final class Functions {', '        private Functions() {}']
    lines += [f'        public static final long {f.name} = 0L;' for f in functions.fields if f.exported]
    lines += ['    }', '', '    protected GLFW() {', '        throw new UnsupportedOperationException();', '    }', '',
              '    private static UnsupportedOperationException unsupported(String method) {',
              '        return new UnsupportedOperationException(method + " is not supported by the Orbis GLFW facade");',
              '    }']
    for method in glfw.methods:
        if not method.exported or method.name == '<init>':
            continue
        if not method.access & classfile.ACC_STATIC:
            raise SystemExit(f'GLFW.{method.name} is not static; extend the generator')
        parameters, returned = classfile.split_method(method.descriptor)
        names = [f'p{i}' for i in range(len(parameters))]
        signature = ', '.join(f'{classfile.java_type(t)} {n}' for t, n in zip(parameters, names))
        if (method.name, method.descriptor) in implemented:
            call = f'{implemented[(method.name, method.descriptor)]}.{method.name}({", ".join(names)});'
            body = call if returned == 'V' else 'return ' + call
        else:
            body = f'throw unsupported("{method.name}{method.descriptor}");'
        lines.append(f'    public static {classfile.java_type(returned)} {method.name}({signature}) {{ {body} }}')
    for name, descriptor in sorted(ADDITIONS):
        parameters, returned = classfile.split_method(descriptor)
        names = [f'p{i}' for i in range(len(parameters))]
        signature = ', '.join(f'{classfile.java_type(t)} {n}' for t, n in zip(parameters, names))
        call = f'OrbisGLFW.{name}({", ".join(names)});'
        if (name, descriptor) not in implemented:
            call = f'throw unsupported("{name}{descriptor}");'
        elif returned != 'V':
            call = 'return ' + call
        lines.append(f'    public static {classfile.java_type(returned)} {name}({signature}) {{ {call} }}')
    lines.append('}')
    target = out / 'generated/org/lwjgl/glfw/GLFW.java'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('\n'.join(lines) + '\n')
    return target


# runner.gpu.NativeInput is compiled for linking only; the runner's app.jar provides it.
runner_classes = out / 'runner-classes'
subprocess.run([str(javac), '--release', '17', '-d', str(runner_classes),
                str(RUNNER / 'src/runner/gpu/NativeInput.java')], check=True)
REPLACED = {'org/lwjgl/glfw/GLFW.class', 'org/lwjgl/glfw/GLFW$Functions.class', 'org/lwjgl/glfw/Callbacks.class'}
if legacy:
    REPLACED |= {'org/lwjgl/glfw/GLFWImage.class', 'org/lwjgl/glfw/GLFWImage$Buffer.class',
                 'org/lwjgl/glfw/GLFWNativeGLX.class', 'org/lwjgl/glfw/GLFWNativeGLX$Functions.class'}
modern34 = release.startswith('3.4')
own = sorted(FACADE.rglob('*.java')) + (sorted(FACADE_34.rglob('*.java')) if modern34 else []) + [
    ROOT / 'lwjgl/java/orbis/graphics/NativePlatform.java',
                                      ROOT / 'lwjgl/java/orbis/graphics/TextInput.java',
                                      ROOT / 'lwjgl/java/orbis/graphics/Gamepad.java']
# The official GLFW jar supplies the unchanged classes; the generated GLFW source overrides its GLFW.
classpath = ':'.join(map(str, (core_jar, opengl_jar, glfw_jar, runner_classes)))


def compile_into(directory, glfw_java):
    subprocess.run([str(javac), '--release', '17', '-nowarn', '-encoding', 'UTF-8', '-cp', classpath,
                    '-d', str(directory), str(glfw_java), str(platform), *map(str, patched_sources), *map(str, own)],
                   check=True)


first = out / 'classes-first'
compile_into(first, generate({}))
# Each implemented (name, descriptor) maps to the class that implements it.
implemented = {}
for owner in ('OrbisGLFW',) + (('OrbisGLFW34',) if modern34 else ()):
    implementation = classfile.parse((first / f'org/lwjgl/glfw/{owner}.class').read_bytes())
    for m in implementation.methods:
        if m.access & classfile.ACC_STATIC and m.name.startswith(('glfw', 'nglfw')):
            if (m.name, m.descriptor) in implemented:
                raise SystemExit(f'{m.name}{m.descriptor} is implemented twice')
            implemented[(m.name, m.descriptor)] = owner
classes = out / 'classes'
compile_into(classes, generate(implemented))

jar = out / f'orbis-lwjgl-{release}-adapter.jar'
with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive, zipfile.ZipFile(glfw_jar) as source:
    for name in sorted(official):
        if name not in REPLACED:
            archive.writestr(name, source.read(name))
    for path in sorted(classes.rglob('*.class')):
        name = path.relative_to(classes).as_posix()
        if name.startswith('runner/') or (name.startswith('org/lwjgl/glfw/') and name in official
                                              and name not in REPLACED):
            continue
        archive.write(path, name)

# Binary contract.
def expected_extra(name):
    if name == 'org/lwjgl/glfw/GLFW.class':
        return {('method', n, d, True) for n, d in ADDITIONS}
    for source, (_, _, members) in SOURCE_ADDITIONS.items():
        if name == source + '.class':
            return members
    return set()


with zipfile.ZipFile(jar) as archive:
    built = {name: classfile.parse(archive.read(name)) for name in archive.namelist()}
problems = []
for name, cls in official.items():
    mine = built.get(name)
    if mine is None:
        problems.append(f'missing class {name}')
    elif (mine.access & 0x0611) != (cls.access & 0x0611) or mine.super_name != cls.super_name \
            or set(mine.interfaces) != set(cls.interfaces):
        problems.append(f'{name}: class flags, superclass or interfaces differ')
    elif mine.exported_members() - expected_extra(name) != cls.exported_members():
        missing = sorted(cls.exported_members() - mine.exported_members())[:5]
        extra = sorted(mine.exported_members() - cls.exported_members() - expected_extra(name))[:5]
        problems.append(f'{name}: exported members differ; missing {missing} extra {extra}')
for field in glfw.fields:
    mine = next((f for f in built['org/lwjgl/glfw/GLFW.class'].fields if f.name == field.name), None)
    if field.exported and (mine is None or mine.constant != field.constant):
        problems.append(f'GLFW.{field.name} constant differs')
platform_built = built.get('org/lwjgl/system/Platform.class')
if platform_built is None or platform_built.exported_members() != official_platform.exported_members():
    problems.append(f'Platform shim exported members differ from lwjgl-{release}.jar')
allowed = set(official) | {'org/lwjgl/system/Platform.class', 'orbis/graphics/NativePlatform.class',
                           'orbis/graphics/TextInput.class', 'orbis/graphics/Gamepad.class',
                           'orbis/graphics/Gamepad$Source.class',
                           'org/lwjgl/glfw/OrbisGLFW.class', 'org/lwjgl/glfw/OrbisGLFW$EglFunctionProvider.class',
                           'org/lwjgl/glfw/OrbisKeys.class', 'org/lwjgl/glfw/OrbisGLFW$JavaErrorCallback.class',
                           'org/lwjgl/glfw/OrbisGLFW$JavaMonitorCallback.class'}
allowed |= {n for n in built if n.startswith('org/lwjgl/system/Platform$')}
# OrbisGLFW's nested classes: the EGL function provider and the Java-only callback wrappers.
allowed |= {n for n in built if n.startswith('org/lwjgl/glfw/OrbisGLFW$')}
if modern34:
    allowed |= {n for n in built if n.startswith(('org/lwjgl/glfw/OrbisGLFW34.', 'org/lwjgl/glfw/OrbisGLFW34$'))}
problems += [f'unexpected class {name}' for name in sorted(set(built) - allowed)]
exported = {f'{m.name} {m.descriptor}' for m in glfw.methods if m.exported and m.name != '<init>'}
exported |= {f'{name} {descriptor}' for name, descriptor in ADDITIONS}
required_missing = sorted(REQUIRED - exported)
problems += [f'required method absent from official GLFW: {m}' for m in required_missing]
not_implemented = sorted(m for m in REQUIRED & exported if tuple(m.split(' ')) not in implemented)
problems += [f'required method not implemented: {m}' for m in not_implemented]
stray = sorted(f'{n} {d}' for n, d in implemented if f'{n} {d}' not in exported and n.startswith(('glfw', 'nglfw')))
problems += [f'OrbisGLFW method matches no official descriptor: {m}' for m in stray]
if problems:
    raise SystemExit('facade contract failed:\n  ' + '\n  '.join(problems))

delegated = sorted(f'{n} {d}' for n, d in implemented if f'{n} {d}' in exported)
audit = {'release': release, 'jar': str(jar), 'classes': len(built), 'official_classes': len(official),
         'glfw_methods': len(exported), 'implemented': delegated,
         'unsupported': sorted(exported - set(delegated))}
(out / 'facade-audit.json').write_text(json.dumps(audit, indent=2) + '\n')
print(f'facade: {jar.name}, {len(official)} GLFW-jar classes match exactly, '
      f'{len(delegated)}/{len(exported)} GLFW methods implemented, {len(audit["unsupported"])} report unsupported')
