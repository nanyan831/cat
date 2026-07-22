# CatLifePet YAWNING Asset Notes

- Eight independent 512 x 512 RGBA PNG frames.
- Transparent background; alpha bottom anchored to `cat_idle_01.png` at y=451.
- Sequence: IDLE, sleepy eyes, early mouth opening, half-open mouth, maximum yawn,
  mouth closing, late recovery, IDLE recovery.
- The maximum-yawn frame has a redrawn vertical open mouth and visible tongue.
- Frames were generated with the built-in image tool using CatLifePet IDLE as the
  identity reference, then chroma-keyed and mechanically normalized to the shared canvas.
- Runtime target: ONCE, 1690ms frame duration plus a small state-completion margin.
