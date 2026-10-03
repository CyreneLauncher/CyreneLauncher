# STB contract fixtures

- `pixels.png`: generated here as a 2×2 RGBA image with opaque red, green,
  blue, and white pixels. Generated data; no third-party license.
- `tone.ogg`: generated here as 0.25 seconds of 440 Hz sine wave at 8 kHz,
  mono, using the local libsndfile Vorbis encoder. Generated data; no
  third-party recording or license.
- `DejaVuSans.ttf`: copy the existing project asset from
  `orbis-java-app/runtime-data/desktop/fonts/DejaVuSans.ttf` when assembling
  fixtures. Its bundled `LICENSE` and `README.md` give upstream provenance.
  This directory does not duplicate the 740 KiB font.

The image and audio files are fixture inputs. `StbChecks` verifies decoded
properties and frees every native allocation it acquires.
