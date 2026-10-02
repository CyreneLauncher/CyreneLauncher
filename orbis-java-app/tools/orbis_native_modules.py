"""Reviewed Orbis runtime native profiles, in provider-first build order."""
CAPABILITY_SCHEMA = 1
DESKTOP_CAPABILITY = 'software-desktop'
SERVER_MODULES = ('java', 'net', 'nio', 'zip', 'verify', 'jimage',
                  'management', 'management_ext', 'jaas')
DESKTOP_MODULES = ('freetype', 'awt', 'awt_headless', 'fontmanager',
                   'javajpeg', 'lcms', 'mlib_image')


def native_modules(desktop: bool = False) -> tuple[str, ...]:
    """Return selected modules, excluding the separately placed JVM."""
    return SERVER_MODULES + (DESKTOP_MODULES if desktop else ())
