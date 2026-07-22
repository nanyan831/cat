from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import shutil

from PIL import Image


@dataclass(frozen=True)
class FrameSpec:
    name: str
    scale_x: float
    scale_y: float
    vertical_offset: int


FRAME_SPECS = (
    FrameSpec("cat_idle_01.png", 1.000, 1.000, 0),
    FrameSpec("cat_idle_02.png", 1.001, 1.004, 0),
    FrameSpec("cat_idle_03.png", 1.002, 1.008, 0),
    FrameSpec("cat_idle_04.png", 1.001, 1.004, 0),
    FrameSpec("cat_idle_05.png", 1.000, 1.000, 0),
    FrameSpec("cat_idle_06.png", 1.001, 0.997, 0),
)


def render_frame(source: Image.Image, bbox: tuple[int, int, int, int], spec: FrameSpec) -> Image.Image:
    left, top, right, bottom = bbox
    subject = source.crop(bbox)
    target_width = max(1, round(subject.width * spec.scale_x))
    target_height = max(1, round(subject.height * spec.scale_y))
    resized = subject.resize((target_width, target_height), Image.Resampling.LANCZOS)

    # Keep the original bottom-center as the visual anchor so the paws do not float.
    anchor_x = (left + right) / 2.0
    paste_x = round(anchor_x - target_width / 2.0)
    paste_y = bottom - target_height + spec.vertical_offset

    frame = Image.new("RGBA", source.size, (0, 0, 0, 0))
    frame.alpha_composite(resized, (paste_x, paste_y))
    return frame


def validate_frame(path: Path) -> None:
    with Image.open(path) as image:
        image.load()
        if image.size != (512, 512):
            raise ValueError(f"{path.name}: expected 512x512, got {image.size}")
        if image.mode != "RGBA":
            raise ValueError(f"{path.name}: expected RGBA, got {image.mode}")
        corners = (
            image.getpixel((0, 0))[3],
            image.getpixel((511, 0))[3],
            image.getpixel((0, 511))[3],
            image.getpixel((511, 511))[3],
        )
        if corners != (0, 0, 0, 0):
            raise ValueError(f"{path.name}: non-transparent corners {corners}")


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    drawable_dir = project_root / "app" / "src" / "main" / "res" / "drawable-nodpi"
    source_path = drawable_dir / "cat_idle.png"
    backup_dir = project_root / "design-source" / "idle-animation-backup"
    frame_paths = [drawable_dir / spec.name for spec in FRAME_SPECS]

    existing_frames = [path for path in frame_paths if path.exists()]
    if existing_frames:
        backup_dir.mkdir(parents=True, exist_ok=True)
        for path in existing_frames:
            shutil.copy2(path, backup_dir / path.name)

    with Image.open(source_path) as loaded:
        source = loaded.convert("RGBA")
    bbox = source.getchannel("A").getbbox()
    if bbox is None:
        raise ValueError("cat_idle.png has no visible pixels")

    for spec, output_path in zip(FRAME_SPECS, frame_paths):
        if spec.scale_x == 1.0 and spec.scale_y == 1.0 and spec.vertical_offset == 0:
            frame = source.copy()
        else:
            frame = render_frame(source, bbox, spec)
        frame.save(output_path, format="PNG", optimize=True)
        validate_frame(output_path)
        print(
            f"{output_path.name}: bbox={bbox}, scale=({spec.scale_x:.3f}, {spec.scale_y:.3f}), "
            f"offsetY={spec.vertical_offset}, bytes={output_path.stat().st_size}"
        )


if __name__ == "__main__":
    main()
