from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import shutil

from PIL import Image


@dataclass(frozen=True)
class FrameSpec:
    scale_x: float = 1.0
    scale_y: float = 1.0
    offset_x: int = 0
    offset_y: int = 0


@dataclass(frozen=True)
class StateSpec:
    source_name: str
    frame_specs: tuple[FrameSpec, ...]


STATE_SPECS = {
    "idle": StateSpec(
        "cat_idle.png",
        (
            FrameSpec(1.000, 1.000),
            FrameSpec(1.001, 1.004),
            FrameSpec(1.002, 1.008),
            FrameSpec(1.001, 1.004),
            FrameSpec(1.000, 1.000),
            FrameSpec(1.001, 0.997),
        ),
    ),
    "happy": StateSpec(
        "cat_happy.png",
        (
            FrameSpec(1.000, 1.000),
            FrameSpec(1.010, 0.990),
            FrameSpec(0.995, 1.015, offset_y=-4),
            FrameSpec(1.000, 1.020, offset_y=-6),
            FrameSpec(1.008, 0.995, offset_y=-2),
            FrameSpec(1.000, 1.000),
        ),
    ),
    "eating": StateSpec(
        "cat_eating.png",
        (
            FrameSpec(1.000, 1.000),
            FrameSpec(1.002, 0.990),
            FrameSpec(1.000, 1.000),
            FrameSpec(0.998, 1.002, offset_y=-1),
            FrameSpec(1.000, 1.000),
            FrameSpec(1.002, 0.990),
            FrameSpec(1.000, 1.000),
            FrameSpec(1.000, 1.000),
        ),
    ),
    "drinking": StateSpec(
        "cat_drinking.png",
        (
            FrameSpec(1.000, 1.000),
            FrameSpec(1.001, 1.002),
            FrameSpec(0.999, 0.998),
            FrameSpec(1.000, 1.002, offset_y=-1),
            FrameSpec(1.000, 1.000),
            FrameSpec(0.999, 0.998),
            FrameSpec(1.001, 1.002),
            FrameSpec(1.000, 1.000),
        ),
    ),
    "sleeping": StateSpec(
        "cat_sleeping.png",
        (
            FrameSpec(1.000, 1.000),
            FrameSpec(1.000, 1.003),
            FrameSpec(1.000, 1.006),
            FrameSpec(1.000, 1.003),
            FrameSpec(1.000, 1.000),
            FrameSpec(1.000, 0.997),
        ),
    ),
    "stretching": StateSpec(
        "cat_stretching.png",
        (
            FrameSpec(1.005, 0.985),
            FrameSpec(1.002, 0.995),
            FrameSpec(1.000, 1.000),
            FrameSpec(0.998, 1.010),
            FrameSpec(0.997, 1.015),
            FrameSpec(1.000, 1.000),
        ),
    ),
    "dragging": StateSpec(
        "cat_dragging.png",
        (
            FrameSpec(1.000, 1.000),
            FrameSpec(1.000, 1.000, offset_x=-1),
            FrameSpec(1.000, 1.000),
            FrameSpec(1.000, 1.000, offset_x=1),
        ),
    ),
}


def render_frame(source: Image.Image, bbox: tuple[int, int, int, int], spec: FrameSpec) -> Image.Image:
    left, top, right, bottom = bbox
    subject = source.crop(bbox)
    target_width = max(1, round(subject.width * spec.scale_x))
    target_height = max(1, round(subject.height * spec.scale_y))
    resized = subject.resize((target_width, target_height), Image.Resampling.LANCZOS)
    anchor_x = (left + right) / 2.0
    paste_x = round(anchor_x - target_width / 2.0) + spec.offset_x
    paste_y = bottom - target_height + spec.offset_y
    frame = Image.new("RGBA", source.size, (0, 0, 0, 0))
    frame.alpha_composite(resized, (paste_x, paste_y))
    return frame


def validate_frame(path: Path) -> None:
    with Image.open(path) as image:
        image.load()
        if image.size != (512, 512) or image.mode != "RGBA":
            raise ValueError(f"{path.name}: expected 512x512 RGBA, got {image.size} {image.mode}")
        corners = tuple(image.getpixel(point)[3] for point in ((0, 0), (511, 0), (0, 511), (511, 511)))
        if corners != (0, 0, 0, 0):
            raise ValueError(f"{path.name}: non-transparent corners {corners}")


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    drawable_dir = project_root / "app" / "src" / "main" / "res" / "drawable-nodpi"
    backup_dir = project_root / "design-source" / "animation-backup"
    backup_dir.mkdir(parents=True, exist_ok=True)

    for state_name, state_spec in STATE_SPECS.items():
        source_path = drawable_dir / state_spec.source_name
        with Image.open(source_path) as loaded:
            source = loaded.convert("RGBA")
        bbox = source.getchannel("A").getbbox()
        if bbox is None:
            raise ValueError(f"{source_path.name} has no visible pixels")

        for index, frame_spec in enumerate(state_spec.frame_specs, start=1):
            output_path = drawable_dir / f"cat_{state_name}_{index:02d}.png"
            if output_path.exists() and not (backup_dir / output_path.name).exists():
                shutil.copy2(output_path, backup_dir / output_path.name)

            if frame_spec == FrameSpec():
                frame = source.copy()
            else:
                frame = render_frame(source, bbox, frame_spec)
            frame.save(output_path, format="PNG", optimize=True)
            validate_frame(output_path)
            print(
                f"{output_path.name}: bbox={bbox}, scale=({frame_spec.scale_x:.3f}, {frame_spec.scale_y:.3f}), "
                f"offset=({frame_spec.offset_x},{frame_spec.offset_y}), bytes={output_path.stat().st_size}"
            )


if __name__ == "__main__":
    main()
