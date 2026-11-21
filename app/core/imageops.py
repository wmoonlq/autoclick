from __future__ import annotations

import struct
from dataclasses import dataclass

from PIL import Image, ImageChops, ImageStat

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def is_png(data: bytes) -> bool:
    return data[:8] == PNG_MAGIC


def png_size(data: bytes) -> tuple[int, int]:
    """Read width/height from the PNG IHDR chunk without decoding pixels."""
    if not is_png(data) or len(data) < 24:
        raise ValueError("not a PNG payload")
    if data[12:16] != b"IHDR":
        raise ValueError("PNG missing IHDR")
    width, height = struct.unpack(">II", data[16:24])
    return int(width), int(height)


def load_image(data: bytes) -> Image.Image:
    from io import BytesIO

    img = Image.open(BytesIO(data))
    img.load()
    return img.convert("RGB")


def hex_to_rgb(value: str) -> tuple[int, int, int]:
    text = value.strip().lstrip("#")
    if len(text) == 3:
        text = "".join(c * 2 for c in text)
    if len(text) != 6:
        raise ValueError(f"invalid color: {value!r}")
    return tuple(int(text[i : i + 2], 16) for i in (0, 2, 4))  # type: ignore[return-value]


def rgb_to_hex(rgb: tuple[int, int, int]) -> str:
    return "#{:02X}{:02X}{:02X}".format(*rgb)


def sample_pixel(img: Image.Image, x: int, y: int) -> tuple[int, int, int]:
    if not (0 <= x < img.width and 0 <= y < img.height):
        raise ValueError(f"pixel out of image: ({x},{y})")
    px = img.getpixel((int(x), int(y)))
    if isinstance(px, int):
        return (px, px, px)
    return (int(px[0]), int(px[1]), int(px[2]))


def color_distance(a: tuple[int, int, int], b: tuple[int, int, int]) -> int:
    """Max per-channel difference; matches the tolerance semantics in the spec."""
    return max(abs(int(x) - int(y)) for x, y in zip(a, b))


@dataclass
class RegionDiff:
    mean: float
    changed_ratio: float


def region(img: Image.Image, x: int, y: int, w: int, h: int) -> Image.Image:
    x0 = max(0, int(x - w / 2))
    y0 = max(0, int(y - h / 2))
    x1 = min(img.width, x0 + int(w))
    y1 = min(img.height, y0 + int(h))
    return img.crop((x0, y0, x1, y1))


def diff_region(a: Image.Image, b: Image.Image, threshold: int = 12) -> RegionDiff:
    """Compare two same-size crops.

    changed_ratio is the share of pixels whose max channel delta exceeds threshold.
    """
    if a.size != b.size:
        raise ValueError(f"size mismatch: {a.size} vs {b.size}")
    d = ImageChops.difference(a, b)
    stat = ImageStat.Stat(d)
    mean = sum(stat.mean) / len(stat.mean) if stat.mean else 0.0
    gray = d.convert("L")
    hist = gray.histogram()
    total = sum(hist) or 1
    changed = sum(hist[threshold + 1 :])
    return RegionDiff(mean=mean, changed_ratio=changed / total)
