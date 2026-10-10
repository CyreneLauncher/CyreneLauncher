Bundled Figtree font
====================

Figtree (https://github.com/erikdkennedy/figtree) is supplied under the included SIL Open Font License 1.1.
Figtree-Medium.ttf is the variable font's Medium (wght 500) instance; font.lock.json records the source,
the instance and the hashes of the generated files. The atlas is a rasterized subset of the font.

Regenerate from the graphics-probe directory:

    java -Djava.awt.headless=true tools/GenerateFontAtlas.java launcher/fonts/Figtree-Medium.ttf launcher/resources/orbis/launcher/fonts

The console loads the checked-in PNG and big-endian float advances. Font rendering happens only on the build host. Atlas cells are 40 pixels, rasterized at 32 pixels, with four atlas pixels per launcher text unit.
