from __future__ import annotations

import argparse
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageChops


ALPHA_THRESHOLD = 8


@dataclass(frozen=True)
class AnimationSpec:
    folder: str
    resource_prefix: str
    minimum_frames: int
    maximum_frames: int


@dataclass(frozen=True)
class FrameMetrics:
    left: int
    top: int
    right: int
    bottom: int
    center_x: float


SPECS = {
    "licking": AnimationSpec("licking", "cat_licking", 8, 12),
    "yawning": AnimationSpec("yawning", "cat_yawning", 8, 10),
    "cuddle": AnimationSpec("cuddle", "cat_cuddle", 8, 12),
    "curious": AnimationSpec("curious", "cat_curious", 6, 10),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def visible_metrics(image: Image.Image) -> FrameMetrics | None:
    alpha = image.convert("RGBA").getchannel("A")
    visible = alpha.point(lambda value: 255 if value > ALPHA_THRESHOLD else 0)
    bbox = visible.getbbox()
    if bbox is None:
        return None
    left, top, right, bottom = bbox
    return FrameMetrics(left, top, right, bottom, (left + right) / 2.0)


def inspect_frame(
    path: Path, idle_metrics: FrameMetrics | None
) -> tuple[list[str], list[str], str, FrameMetrics | None]:
    errors: list[str] = []
    warnings: list[str] = []
    digest = sha256(path)
    metrics: FrameMetrics | None = None
    try:
        with Image.open(path) as image:
            image.load()
            if image.format != "PNG":
                errors.append(f"{path.name}: format={image.format}, expected PNG")
            if image.size != (512, 512):
                errors.append(f"{path.name}: size={image.size}, expected 512x512")
            if image.mode != "RGBA":
                errors.append(f"{path.name}: mode={image.mode}, expected RGBA")
                image = image.convert("RGBA")

            alpha = image.getchannel("A")
            alpha_min, alpha_max = alpha.getextrema()
            if alpha_max == 0:
                errors.append(f"{path.name}: frame is fully transparent")
            if alpha_min == 255:
                errors.append(f"{path.name}: no transparent pixels")

            corners = [alpha.getpixel(point) for point in ((0, 0), (511, 0), (0, 511), (511, 511))]
            if any(value != 0 for value in corners):
                errors.append(f"{path.name}: non-transparent canvas corners={corners}")

            metrics = visible_metrics(image)
            if metrics is not None:
                if metrics.left <= 1 or metrics.top <= 1 or metrics.right >= 511 or metrics.bottom >= 511:
                    warnings.append(f"{path.name}: subject approaches canvas edge")
                if idle_metrics is not None:
                    bottom_delta = metrics.bottom - idle_metrics.bottom
                    center_delta = metrics.center_x - idle_metrics.center_x
                    if abs(bottom_delta) > 4:
                        warnings.append(f"{path.name}: bottom anchor delta={bottom_delta}px")
                    if abs(center_delta) > 20:
                        warnings.append(f"{path.name}: visible centerX delta={center_delta:.1f}px")

            red, green, blue, alpha = image.split()
            dark = ImageChops.multiply(
                ImageChops.multiply(
                    red.point(lambda value: 255 if value < 8 else 0),
                    green.point(lambda value: 255 if value < 8 else 0),
                ),
                blue.point(lambda value: 255 if value < 8 else 0),
            )
            opaque = alpha.point(lambda value: 255 if value >= 250 else 0)
            opaque_dark = ImageChops.multiply(dark, opaque).histogram()[255]
            if opaque_dark > image.width * image.height * 0.50:
                errors.append(f"{path.name}: probable opaque black background")
    except Exception as error:
        errors.append(f"{path.name}: unreadable PNG ({error})")
    return errors, warnings, digest, metrics


def frame_files(folder: Path, prefix: str) -> tuple[list[Path], list[str]]:
    errors: list[str] = []
    candidates = sorted(folder.glob("*.png")) if folder.exists() else []
    pattern = re.compile(rf"^{re.escape(prefix)}_(\d{{2}})\.png$")
    numbered: list[tuple[int, Path]] = []
    for path in candidates:
        match = pattern.match(path.name)
        if not match:
            errors.append(f"unexpected filename: {path.name}")
            continue
        numbered.append((int(match.group(1)), path))
    numbered.sort(key=lambda item: item[0])
    if numbered:
        expected = list(range(1, numbered[-1][0] + 1))
        actual = [number for number, _ in numbered]
        if actual != expected:
            errors.append(f"missing frame numbers: {sorted(set(expected) - set(actual))}")
    return [path for _, path in numbered], errors


def candidate_folder(source_root: Path, name: str, stage: str) -> Path:
    animation_root = source_root / SPECS[name].folder
    staged = animation_root / stage
    return staged if staged.exists() else animation_root


def validate_manifest(name: str, animation_root: Path, frames: list[Path]) -> list[str]:
    manifest_name = f"{name}_manifest.json"
    manifest_path = animation_root / manifest_name
    if not manifest_path.exists():
        return [f"{manifest_name} is missing"]
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as error:
        return [f"{manifest_name} is invalid: {error}"]

    errors: list[str] = []
    expected_state = name.upper()
    if manifest.get("animationName") != expected_state:
        errors.append(f"manifest animationName must be {expected_state}")
    if manifest.get("state") != expected_state or manifest.get("mode") != "ONCE":
        errors.append(f"manifest state/mode must be {expected_state}/ONCE")
    if manifest.get("canvasSize") != [512, 512]:
        errors.append("manifest canvasSize must be [512, 512]")
    manifest_frames = manifest.get("frames", [])
    actual_names = [path.name for path in frames]
    if manifest.get("assetStatus") == "READY" and manifest_frames != actual_names:
        errors.append("READY manifest frame list does not match candidate files")
    durations = manifest.get("durationsMs", [])
    if manifest.get("assetStatus") == "READY" and len(durations) != len(frames):
        errors.append("READY manifest duration count does not match frame count")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate CatLifePet v0.8.3 frame assets.")
    parser.add_argument("--project-root", type=Path)
    parser.add_argument("--animation", choices=tuple(SPECS), help="Validate only one animation.")
    parser.add_argument("--source-stage", choices=("incoming", "approved"), default="incoming")
    parser.add_argument("--include-legacy-yawn", action="store_true")
    parser.add_argument("--strict", action="store_true", help="Fail when an animation is unavailable or invalid.")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--anchor-output", type=Path)
    args = parser.parse_args()

    project_root = (args.project_root or Path(__file__).resolve().parents[2]).resolve()
    source_root = project_root / "design-source" / "v0.8.3"
    idle_path = project_root / "app" / "src" / "main" / "res" / "drawable-nodpi" / "cat_idle_01.png"
    with Image.open(idle_path) as idle:
        idle_metrics = visible_metrics(idle)

    lines = [
        "CatLifePet v0.8.3 animation asset validation",
        f"PROJECT_ROOT={project_root}",
        f"ALPHA_THRESHOLD={ALPHA_THRESHOLD}",
        f"IDLE_BBOX={idle_metrics}",
        "",
    ]
    anchor_lines = [
        "CatLifePet animation anchor report",
        f"REFERENCE={idle_path}",
        f"ALPHA_THRESHOLD={ALPHA_THRESHOLD}",
        f"IDLE_BBOX={idle_metrics}",
        "",
    ]
    has_failure = False
    names = [args.animation] if args.animation else list(SPECS)

    for name in names:
        spec = SPECS[name]
        folder = candidate_folder(source_root, name, args.source_stage)
        source_kind = args.source_stage.upper()
        frames, naming_errors = frame_files(folder, spec.resource_prefix)
        if not frames and name == "yawning" and args.include_legacy_yawn:
            folder = source_root / "legacy-yawn"
            frames, naming_errors = frame_files(folder, spec.resource_prefix)
            source_kind = "LEGACY_FALLBACK"

        lines.extend((f"[{name.upper()}]", f"SOURCE={folder}"))
        if not frames:
            lines.extend(("STATUS=NOT_AVAILABLE", "FRAME_COUNT=0", "RESULT=BLOCKED", ""))
            if name == "licking":
                anchor_lines.extend((f"SOURCE={folder}", "FRAME_COUNT=0", "RESULT=BLOCKED", "WAITING_FOR_REAL_ART_ASSETS"))
            has_failure = has_failure or args.strict
            continue

        errors = list(naming_errors)
        if source_kind != "LEGACY_FALLBACK":
            errors.extend(validate_manifest(name, source_root / spec.folder, frames))
        warnings: list[str] = []
        hashes: dict[str, list[str]] = {}
        metrics_by_name: list[tuple[str, FrameMetrics]] = []
        previous_hash: str | None = None
        for frame in frames:
            frame_errors, frame_warnings, digest, metrics = inspect_frame(frame, idle_metrics)
            errors.extend(frame_errors)
            warnings.extend(frame_warnings)
            hashes.setdefault(digest, []).append(frame.name)
            if previous_hash == digest:
                warnings.append(f"consecutive duplicate hash: {frame.name}")
            previous_hash = digest
            if metrics is not None:
                metrics_by_name.append((frame.name, metrics))

        for duplicate_names in hashes.values():
            if len(duplicate_names) > 1:
                warnings.append(f"duplicate hash group: {', '.join(duplicate_names)}")
        if not spec.minimum_frames <= len(frames) <= spec.maximum_frames:
            warnings.append(f"frame count {len(frames)} outside recommended {spec.minimum_frames}..{spec.maximum_frames}")

        status = "FALLBACK" if source_kind == "LEGACY_FALLBACK" else ("READY" if not errors else "INVALID")
        lines.extend((f"STATUS={source_kind}", f"FRAME_COUNT={len(frames)}", f"RESULT={status}"))
        for frame_name, metrics in metrics_by_name:
            bottom_delta = metrics.bottom - idle_metrics.bottom if idle_metrics else 0
            center_delta = metrics.center_x - idle_metrics.center_x if idle_metrics else 0.0
            lines.append(
                f"FRAME={frame_name} BBOX=({metrics.left},{metrics.top},{metrics.right},{metrics.bottom}) "
                f"CENTER_X={metrics.center_x:.1f} BOTTOM_Y={metrics.bottom} "
                f"CENTER_DELTA={center_delta:.1f} BOTTOM_DELTA={bottom_delta}"
            )
        lines.extend(f"ERROR={message}" for message in errors)
        lines.extend(f"WARNING={message}" for message in warnings)
        lines.append("")

        if args.animation == name:
            bottom_deltas = [abs(metrics.bottom - idle_metrics.bottom) for _, metrics in metrics_by_name] if idle_metrics else []
            center_deltas = [abs(metrics.center_x - idle_metrics.center_x) for _, metrics in metrics_by_name] if idle_metrics else []
            anchor_lines.extend((f"SOURCE={folder}", f"FRAME_COUNT={len(frames)}"))
            anchor_lines.extend(line for line in lines if line.startswith("FRAME="))
            anchor_lines.extend((
                f"MAX_ABS_BOTTOM_DELTA={max(bottom_deltas, default=0)}",
                f"MAX_ABS_CENTER_DELTA={max(center_deltas, default=0.0):.1f}",
                f"RESULT={status}",
            ))
        has_failure = has_failure or bool(errors) or (args.strict and status != "READY")

    report = "\n".join(lines).rstrip() + "\n"
    print(report, end="")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding="utf-8")
    if args.anchor_output:
        args.anchor_output.parent.mkdir(parents=True, exist_ok=True)
        args.anchor_output.write_text("\n".join(anchor_lines).rstrip() + "\n", encoding="utf-8")
    return 1 if has_failure else 0


if __name__ == "__main__":
    raise SystemExit(main())
