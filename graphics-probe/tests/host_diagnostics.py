"""Expected stderr from host probes using custom LWJGL native libraries."""

# LWJGL 3.2.2 prints this fixed block when a custom provider has different
# bytes from its bundled Linux native. Keep the full text so other diagnostics
# cannot hide behind the familiar first line.
LWJGL_CUSTOM_NATIVE = '''[LWJGL] [ERROR] Incompatible Java and native library versions detected.
Possible reasons:
\ta) -Djava.library.path is set to a folder containing shared libraries of an older LWJGL version.
\tb) The classpath contains jar files of an older LWJGL version.
Possible solutions:
\ta) Make sure to not set -Djava.library.path (it is not needed for developing with LWJGL 3) or make
\t   sure the folder it points to contains the shared libraries of the correct LWJGL version.
\tb) Check the classpath and make sure to only have jar files of the same LWJGL version in it.
'''
OPENAL_NO_DEVICE = '[orbis-openal] audio device unavailable; sound disabled\n'
TINYFD_CANCEL = '[orbis-tinyfd] dialogs unavailable; returning cancel\n'
PROBE_FAILURE = ('Exception in thread "main" java.lang.IllegalStateException: '
                 'compatibility probe failed; inspect COMPAT lines\n'
                 '\tat orbis.compat.CompatibilityProbe.main(CompatibilityProbe.java:64)\n')


def unexpected_host_stderr(stderr: str, *, expected_probe_failure=False) -> str:
    """Return diagnostics not explained by custom providers and the injected failure."""
    for expected in (LWJGL_CUSTOM_NATIVE, OPENAL_NO_DEVICE, TINYFD_CANCEL):
        stderr = stderr.replace(expected, '')
    if expected_probe_failure:
        stderr = stderr.replace(PROBE_FAILURE, '')
    return stderr
