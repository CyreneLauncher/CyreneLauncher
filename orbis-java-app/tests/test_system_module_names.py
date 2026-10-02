#!/usr/bin/env python3
"""Compile the actual metadata selection method on a host and exercise fallbacks."""
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'src/java.base/share/classes/jdk/internal/module/SystemModuleFinders.java').read_text()
start = source.index('    private static String[] orbisModuleNames(')
end = source.index('\n    /**', start)
method = source[start:end]
assert 'orbis.module.names.list' not in source
assert 'inline names' not in source
assert '"java.desktop"' not in method
java = '''import java.io.*; import java.nio.file.*; import java.util.*;
class ModuleNamesCheck {
 static class VM { static void orbisStartupProbePoint(String s) {} }
 static class ImageReader { String[] getModuleNames() { return new String[]{"image.only"}; } }
''' + method + '''
 static void expect(String... expected) {
  if (!Arrays.equals(expected, orbisModuleNames(new ImageReader()))) throw new AssertionError();
 }
 public static void main(String[] args) throws Exception {
  System.setProperty("orbis.module.names.list", "legacy gate");
  expect("image.only");
  Path names=Paths.get(args[0]);
  System.setProperty("orbis.module.names.file", names.toString());
  Files.write(names,"custom.one\\r\\n\\ncustom.two".getBytes("UTF-8"));
  expect("custom.one","custom.two");
  Files.write(names,"reduced.only\\n".getBytes("UTF-8")); expect("reduced.only");
  Files.delete(names); expect("image.only");
 }
}
'''
with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary)
    fixture = directory / 'ModuleNamesCheck.java'
    fixture.write_text(java)
    javac = shutil.which('javac') or '/tmp/jdk17-orbis/bin/javac'
    java_command = str(Path(javac).with_name('java'))
    subprocess.run([javac, '-d', temporary, str(fixture)], check=True)
    subprocess.run([java_command, '-cp', temporary, 'ModuleNamesCheck', str(directory / 'modules.list')], check=True)
print('Selected module metadata host contract passed')
