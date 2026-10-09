Bundled Inter font
==================

Source and generated hashes are recorded in inter.lock.json. Inter is supplied under the included SIL Open Font License 1.1. The atlas is a rasterized subset of the original font.

Regenerate from the graphics-probe directory using JDK 17:

    java -Djava.awt.headless=true tools/GenerateFontAtlas.java launcher/fonts/InterVariable.ttf launcher/resources/orbis/launcher/fonts

The console loads the checked-in PNG and big-endian float advances. Font rendering happens only on the build host. Atlas cells are 40 pixels, rasterized at 32 pixels, with four atlas pixels per launcher text unit.
