# Bundled baseline font

DejaVu Sans from the official DejaVu fonts 2.37 release:
https://github.com/dejavu-fonts/dejavu-fonts/releases/tag/version_2_37

Source archive:
https://github.com/dejavu-fonts/dejavu-fonts/releases/download/version_2_37/dejavu-fonts-ttf-2.37.tar.bz2

SHA256:
- Archive: `fa9ca4d13871dd122f61258a80d01751d603b4d3ee14095d65453b4e846e17d7`
- `DejaVuSans.ttf`: `7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954`
- `LICENSE`: `7a083b136e64d064794c3419751e5c7dd10d2f64c108fe5ba161eae5e5958a93`

Only the named regular archive members `dejavu-fonts-ttf-2.37/ttf/DejaVuSans.ttf`
and `dejavu-fonts-ttf-2.37/LICENSE` were extracted, after verifying the archive
and member hashes. LICENSE is the complete, unmodified upstream license;
DejaVu changes are public domain and the original Bitstream Vera terms apply.

Desktop exports install the font at `lib/fonts/DejaVuSans.ttf` and LICENSE at
`legal/orbis-desktop-fonts/LICENSE`. OrbisFontManager resolves this exact font
relative to java.home; font discovery does not depend on the working directory,
host font directories, fontconfig, or runtime downloads.

This single regular face supplies the baseline logical-family fallback. It
cannot provide distinct serif/monospaced faces, dedicated bold/italic faces,
or universal glyph coverage. Applications needing those must wait for an
explicitly supported font configuration. Packaging does not establish PS4
rendering correctness; console execution remains pending.
