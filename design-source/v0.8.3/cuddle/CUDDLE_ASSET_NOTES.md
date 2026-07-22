# CUDDLE Asset Notes

- Eight-frame, one-shot affectionate cheek-to-paw nuzzle animation.
- Front-facing motion is intentionally edge-independent, so it remains readable on both screen sides without mirrored asset variants.
- Transparent RGBA canvas; all frames are 512 x 512 px.
- Visible body bottom is anchored to `cat_idle_01.png` at y=451 and visible center is aligned near x=286.
- Frames 01 and 08 reuse existing idle poses to keep entry and exit transitions stable.
- Chroma-key generation sources and full-size alpha intermediates are retained in `reference/` for later art revision.
- Runtime integration belongs to roadmap checkpoint M1.2; this asset checkpoint does not modify Android resources or Kotlin behavior.
