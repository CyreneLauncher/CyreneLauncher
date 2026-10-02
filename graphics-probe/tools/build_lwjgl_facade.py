#!/usr/bin/env python3
"""Build the Orbis LWJGL 3.2.2 adapter JAR and enforce its binary contract.

Contents, placed ahead of the official lwjgl-3.2.2.jar and instead of lwjgl-glfw-3.2.2.jar:
  * org.lwjgl.system.Platform from the 3.2.2 source, accepting os.name "Orbis" as Linux;
  * every org.lwjgl.glfw class copied byte-for-byte from the official lwjgl-glfw-3.2.2.jar,
    except GLFW/GLFW$Functions (generated) and Callbacks (replaced);
  * orbis.graphics.NativePlatform, the platform PRX's Java side.
GLFW.java is generated from the official GLFW.class: all constants, GLFW$Functions and
every exported method with its exact descriptor. Methods OrbisGLFW implements with the
same descriptor delegate there; the rest throw UnsupportedOperationException naming the
method. The build fails unless the JAR's exported members equal the official ones and
every required method is implemented.
"""
import argparse
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
}

parser = argparse.ArgumentParser()
parser.add_argument('--boot-jdk', default='/usr/lib/jvm/java-17-openjdk-amd64')
parser.add_argument('--jars', default='/tmp/lwjgl-audit', help='official LWJGL 3.2.2 Java JARs')
parser.add_argument('--downloads', default=str(ROOT / 'build/downloads'))
parser.add_argument('--fetch', action='store_true')
parser.add_argument('--output', default=str(ROOT / 'build/lwjgl-facade'))
arguments = parser.parse_args()
javac = Path(arguments.boot_jdk) / 'bin/javac'
out = Path(arguments.output).resolve()
if out.exists():
    shutil.rmtree(out)
out.mkdir(parents=True)

core_jar = lwjgl_sources.java_artifact(arguments.jars, 'lwjgl-3.2.2.jar')
opengl_jar = lwjgl_sources.java_artifact(arguments.jars, 'lwjgl-opengl-3.2.2.jar')
glfw_jar = lwjgl_sources.java_artifact(arguments.jars, 'lwjgl-glfw-3.2.2.jar')
source_root = lwjgl_sources.extract(lwjgl_sources.archive('lwjgl', arguments.downloads, arguments.fetch),
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
anchor = 'osName.startsWith("Linux") || osName.startsWith("FreeBSD")'
if platform_source.count(anchor) != 1:
    raise SystemExit('unexpected Platform.java; inspect the os.name switch')
platform = out / 'generated/org/lwjgl/system/Platform.java'
platform.parent.mkdir(parents=True)
platform.write_text(platform_source.replace(anchor, 'osName.startsWith("Orbis") || ' + anchor))


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
             '/** Generated from LWJGL 3.2.2 GLFW.class by build_lwjgl_facade.py; implementation: OrbisGLFW. */',
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
            call = f'OrbisGLFW.{method.name}({", ".join(names)});'
            body = call if returned == 'V' else 'return ' + call
        else:
            body = f'throw unsupported("{method.name}{method.descriptor}");'
        lines.append(f'    public static {classfile.java_type(returned)} {method.name}({signature}) {{ {body} }}')
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
own = sorted(FACADE.rglob('*.java')) + [ROOT / 'lwjgl/java/orbis/graphics/NativePlatform.java']
# The official GLFW jar supplies the unchanged classes; the generated GLFW source overrides its GLFW.
classpath = ':'.join(map(str, (core_jar, opengl_jar, glfw_jar, runner_classes)))


def compile_into(directory, glfw_java):
    subprocess.run([str(javac), '--release', '17', '-nowarn', '-encoding', 'UTF-8', '-cp', classpath,
                    '-d', str(directory), str(glfw_java), str(platform), *map(str, own)],
                   check=True)


first = out / 'classes-first'
compile_into(first, generate(set()))
implementation = classfile.parse((first / 'org/lwjgl/glfw/OrbisGLFW.class').read_bytes())
implemented = {(m.name, m.descriptor) for m in implementation.methods if m.access & classfile.ACC_STATIC}
classes = out / 'classes'
compile_into(classes, generate(implemented))

jar = out / 'orbis-lwjgl-3.2.2-adapter.jar'
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
    elif mine.exported_members() != cls.exported_members():
        missing = sorted(cls.exported_members() - mine.exported_members())[:5]
        extra = sorted(mine.exported_members() - cls.exported_members())[:5]
        problems.append(f'{name}: exported members differ; missing {missing} extra {extra}')
for field in glfw.fields:
    mine = next((f for f in built['org/lwjgl/glfw/GLFW.class'].fields if f.name == field.name), None)
    if field.exported and (mine is None or mine.constant != field.constant):
        problems.append(f'GLFW.{field.name} constant differs')
platform_built = built.get('org/lwjgl/system/Platform.class')
if platform_built is None or platform_built.exported_members() != official_platform.exported_members():
    problems.append('Platform shim exported members differ from lwjgl-3.2.2.jar')
allowed = set(official) | {'org/lwjgl/system/Platform.class', 'orbis/graphics/NativePlatform.class',
                           'org/lwjgl/glfw/OrbisGLFW.class', 'org/lwjgl/glfw/OrbisGLFW$EglFunctionProvider.class',
                           'org/lwjgl/glfw/OrbisKeys.class'}
allowed |= {n for n in built if n.startswith('org/lwjgl/system/Platform$')}
problems += [f'unexpected class {name}' for name in sorted(set(built) - allowed)]
exported = {f'{m.name} {m.descriptor}' for m in glfw.methods if m.exported and m.name != '<init>'}
required_missing = sorted(REQUIRED - exported)
problems += [f'required method absent from official GLFW: {m}' for m in required_missing]
not_implemented = sorted(m for m in REQUIRED & exported if tuple(m.split(' ')) not in implemented)
problems += [f'required method not implemented: {m}' for m in not_implemented]
stray = sorted(f'{n} {d}' for n, d in implemented if f'{n} {d}' not in exported and n.startswith(('glfw', 'nglfw')))
problems += [f'OrbisGLFW method matches no official descriptor: {m}' for m in stray]
if problems:
    raise SystemExit('facade contract failed:\n  ' + '\n  '.join(problems))

delegated = sorted(f'{n} {d}' for n, d in implemented if f'{n} {d}' in exported)
audit = {'jar': str(jar), 'classes': len(built), 'official_classes': len(official),
         'glfw_methods': len(exported), 'implemented': delegated,
         'unsupported': sorted(exported - set(delegated))}
(out / 'facade-audit.json').write_text(json.dumps(audit, indent=2) + '\n')
print(f'facade: {jar.name}, {len(official)} GLFW-jar classes match exactly, '
      f'{len(delegated)}/{len(exported)} GLFW methods implemented, {len(audit["unsupported"])} report unsupported')
