from __future__ import annotations

import argparse
import json
import math
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ANIMATIONS = {
    "licking": ("licking", "cat_licking"),
    "yawning": ("yawning", "cat_yawning"),
    "cuddle": ("cuddle", "cat_cuddle"),
    "curious": ("curious", "cat_curious"),
}


def load_font(size: int) -> ImageFont.ImageFont:
    for candidate in ("C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/segoeui.ttf"):
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def checkerboard(size: int, tile: int = 16) -> Image.Image:
    image = Image.new("RGBA", (size, size), "white")
    draw = ImageDraw.Draw(image)
    colors = ((240, 240, 240, 255), (214, 214, 214, 255))
    for y in range(0, size, tile):
        for x in range(0, size, tile):
            draw.rectangle((x, y, x + tile - 1, y + tile - 1), fill=colors[(x // tile + y // tile) % 2])
    return image


def find_frames(folder: Path, prefix: str) -> list[Path]:
    pattern = re.compile(rf"^{re.escape(prefix)}_(\d{{2}})\.png$")
    numbered = []
    for path in folder.glob("*.png") if folder.exists() else []:
        match = pattern.match(path.name)
        if match:
            numbered.append((int(match.group(1)), path))
    return [path for _, path in sorted(numbered)]


def load_durations(name: str, animation_root: Path, frame_count: int) -> list[int | None]:
    manifest_path = animation_root / f"{name}_manifest.json"
    if not manifest_path.exists():
        return [None] * frame_count
    try:
        values = json.loads(manifest_path.read_text(encoding="utf-8")).get("durationsMs", [])
    except (OSError, json.JSONDecodeError):
        return [None] * frame_count
    return [values[index] if index < len(values) else None for index in range(frame_count)]


def build_sheet(
    name: str,
    frames: list[Path],
    durations: list[int | None],
    label: str,
    output: Path,
) -> None:
    columns = 4
    preview = 240
    label_height = 34
    gap = 18
    header_height = 72
    rows = math.ceil(len(frames) / columns)
    width = gap + columns * (preview + gap)
    height = header_height + rows * (preview + label_height + gap)
    sheet = Image.new("RGB", (width, height), (250, 250, 250))
    draw = ImageDraw.Draw(sheet)
    title_font = load_font(28)
    frame_font = load_font(16)
    draw.text((gap, 18), f"{name.upper()} - {label} - {len(frames)} frames", fill=(25, 25, 25), font=title_font)

    for index, path in enumerate(frames):
        row, column = divmod(index, columns)
        x = gap + column * (preview + gap)
        y = header_height + row * (preview + label_height + gap)
        background = checkerboard(preview)
        with Image.open(path) as loaded:
            frame = loaded.convert("RGBA")
            frame.thumbnail((preview, preview), Image.Resampling.LANCZOS)
        px = (preview - frame.width) // 2
        py = (preview - frame.height) // 2
        background.alpha_composite(frame, (px, py))
        sheet.paste(background.convert("RGB"), (x, y))
        draw.rectangle((x, y, x + preview - 1, y + preview - 1), outline=(150, 150, 150), width=1)
        duration = durations[index] if index < len(durations) else None
        timing = f"{duration}ms" if duration is not None else "timing N/A"
        draw.text(
            (x, y + preview + 7),
            f"F{index + 1:02d}  {timing}  {path.name}",
            fill=(40, 40, 40),
            font=frame_font,
        )

    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output, "PNG", optimize=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build CatLifePet v0.8.3 animation contact sheets.")
    parser.add_argument("--project-root", type=Path)
    parser.add_argument("--animation", choices=tuple(ANIMATIONS))
    parser.add_argument("--source-stage", choices=("incoming", "approved"), default="incoming")
    parser.add_argument("--qa-version", default="v0.8.3")
    parser.add_argument("--include-legacy-yawn", action="store_true")
    args = parser.parse_args()

    project_root = (args.project_root or Path(__file__).resolve().parents[2]).resolve()
    source_root = project_root / "design-source" / "v0.8.3"
    animations = {args.animation: ANIMATIONS[args.animation]} if args.animation else ANIMATIONS

    generated = 0
    for name, (folder_name, prefix) in animations.items():
        animation_root = source_root / folder_name
        staged_folder = animation_root / args.source_stage
        folder = staged_folder if staged_folder.exists() else animation_root
        frames = find_frames(folder, prefix)
        label = args.source_stage.upper()
        if not frames and name == "yawning" and args.include_legacy_yawn:
            folder = source_root / "legacy-yawn"
            frames = find_frames(folder, prefix)
            label = "LEGACY_FALLBACK"
        if not frames:
            print(f"{name}: NOT AVAILABLE; contact sheet not generated")
            continue
        durations = load_durations(name, animation_root, len(frames))
        outputs = (
            animation_root / "contact-sheets" if name == "licking" else source_root / "contact-sheets",
            project_root / "qa" / args.qa_version,
        )
        for output_dir in outputs:
            filename = "licking-contact-sheet.png" if name == "licking" else f"{name}-contact-sheet.png"
            output = output_dir / filename
            build_sheet(name, frames, durations, label, output)
            print(f"{name}: wrote {output}")
        generated += 1
    return 0 if generated else 1


if __name__ == "__main__":
    raise SystemExit(main())
