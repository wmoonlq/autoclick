from __future__ import annotations

from dataclasses import dataclass

from .errors import OutOfBounds, ResolutionMismatch

ASPECT_TOLERANCE = 0.02


@dataclass(frozen=True)
class Size:
    width: int
    height: int

    def contains(self, x: int, y: int) -> bool:
        return 0 <= x < self.width and 0 <= y < self.height


def check_aspect(ref_w: int, ref_h: int, cur_w: int, cur_h: int) -> float:
    if ref_w <= 0 or ref_h <= 0 or cur_w <= 0 or cur_h <= 0:
        raise ResolutionMismatch("resolution must be positive")
    sx = cur_w / ref_w
    sy = cur_h / ref_h
    drift = abs(sx - sy) / max(sx, sy)
    if drift > ASPECT_TOLERANCE:
        raise ResolutionMismatch(
            f"aspect ratio drift {drift:.1%} exceeds {ASPECT_TOLERANCE:.0%} "
            f"(ref {ref_w}x{ref_h} -> current {cur_w}x{cur_h})"
        )
    return drift


def to_device(
    x_ref: int,
    y_ref: int,
    ref_w: int,
    ref_h: int,
    cur_w: int,
    cur_h: int,
) -> tuple[int, int]:
    """Scale a reference-resolution point to the live device resolution.

    Never clamps: out-of-bounds is a hard error (SPEC 9.3).
    """
    check_aspect(ref_w, ref_h, cur_w, cur_h)
    sx = cur_w / ref_w
    sy = cur_h / ref_h
    x = round(x_ref * sx)
    y = round(y_ref * sy)
    if not (0 <= x < cur_w and 0 <= y < cur_h):
        raise OutOfBounds(f"({x_ref},{y_ref}) -> ({x},{y}) outside {cur_w}x{cur_h}")
    return x, y


def clamp_pick(x: int, y: int, width: int, height: int) -> tuple[int, int]:
    """Clamp a *picked* point to the image, used only by the picker UI."""
    return min(max(0, int(x)), width - 1), min(max(0, int(y)), height - 1)


def to_percent(x: int, y: int, width: int, height: int) -> tuple[float, float]:
    if width <= 0 or height <= 0:
        raise ValueError("width/height must be positive")
    return round(x / width, 6), round(y / height, 6)


def from_percent(rx: float, ry: float, width: int, height: int) -> tuple[int, int]:
    if not (0.0 <= rx <= 1.0 and 0.0 <= ry <= 1.0):
        raise OutOfBounds(f"percent coordinate out of range: ({rx},{ry})")
    return (
        min(width - 1, max(0, round(rx * width))),
        min(height - 1, max(0, round(ry * height))),
    )
