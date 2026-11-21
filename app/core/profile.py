from __future__ import annotations

import time
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .coords import ASPECT_TOLERANCE, check_aspect
from .errors import ScriptValidationError
from .imageops import diff_region, png_size, region
from .paths import atomic_write_json, read_json
from .transport import BaseTransport, DeviceInfo


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


@dataclass
class DeviceProfile:
    id: str
    serial: str
    model: str = ""
    android_version: str = ""
    ref_width: int = 0
    ref_height: int = 0
    density: int = 0
    orientation: int = 0
    display_id: int = 0
    nav_mode: str = "unknown"
    wm_size_raw: str = ""
    screencap_size: str = ""
    calibrated: bool = False
    created_at: str = field(default_factory=lambda: time.strftime("%Y-%m-%dT%H:%M:%S%z"))

    @property
    def ref_size(self) -> tuple[int, int]:
        return self.ref_width, self.ref_height

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "DeviceProfile":
        allowed = {f for f in cls.__dataclass_fields__}
        return cls(**{k: v for k, v in data.items() if k in allowed})


@dataclass
class ConsistencyReport:
    ok: bool
    issues: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    wm_size: tuple[int, int] | None = None
    shot_size: tuple[int, int] | None = None
    orientation: int = 0

    def raise_if_bad(self) -> None:
        if not self.ok:
            raise ScriptValidationError(self.issues)


def build_profile(transport: BaseTransport, info: DeviceInfo | None = None) -> tuple[DeviceProfile, ConsistencyReport]:
    info = info or transport.device_info()
    wm_w, wm_h, wm_raw = transport.wm_size()
    shot = transport.screencap()
    shot_w, shot_h = png_size(shot)
    orientation = transport.rotation()
    density = transport.wm_density()

    issues: list[str] = []
    warnings: list[str] = []
    if (wm_w, wm_h) != (shot_w, shot_h):
        issues.append(
            f"wm size 与截图尺寸不一致：wm {wm_w}x{wm_h} vs screencap {shot_w}x{shot_h}。"
            "可能是 wm size Override 或 ROM 行为差异，禁止运行。"
        )
    try:
        check_aspect(wm_w, wm_h, shot_w, shot_h)
    except Exception as exc:  # noqa: BLE001
        issues.append(f"宽高比不一致：{exc}")
    if orientation != 0:
        issues.append(f"当前屏幕方向为 {orientation}，MVP 仅支持竖屏（0）。")
    if shot_w <= 0 or shot_h <= 0:
        issues.append("截图尺寸无效。")
    if density <= 0:
        warnings.append("未读取到屏幕密度，展示比例可能不准。")

    profile = DeviceProfile(
        id=new_id("dp"),
        serial=info.serial,
        model=info.model,
        android_version=info.android,
        ref_width=shot_w,
        ref_height=shot_h,
        density=density,
        orientation=orientation,
        wm_size_raw=wm_raw,
        screencap_size=f"{shot_w}x{shot_h}",
        calibrated=False,
    )
    return profile, ConsistencyReport(
        ok=not issues,
        issues=issues,
        warnings=warnings,
        wm_size=(wm_w, wm_h),
        shot_size=(shot_w, shot_h),
        orientation=orientation,
    )


def validate_for_run(profile: DeviceProfile, transport: BaseTransport) -> ConsistencyReport:
    issues: list[str] = []
    warnings: list[str] = []
    info = transport.device_info()
    if profile.serial and info.serial != profile.serial:
        issues.append(f"档案绑定设备 {profile.serial}，当前设备 {info.serial}。")
    wm_w, wm_h, _ = transport.wm_size()
    shot_w, shot_h = png_size(transport.screencap())
    if (wm_w, wm_h) != (shot_w, shot_h):
        issues.append(f"本次运行 wm size {wm_w}x{wm_h} 与截图 {shot_w}x{shot_h} 不一致。")
    if (shot_w, shot_h) != profile.ref_size:
        try:
            check_aspect(profile.ref_width, profile.ref_height, shot_w, shot_h)
            warnings.append(
                f"分辨率已变化（档案 {profile.ref_width}x{profile.ref_height} → "
                f"当前 {shot_w}x{shot_h}），将按比例换算。"
            )
        except Exception as exc:  # noqa: BLE001
            issues.append(f"分辨率不兼容：{exc}")
    if abs((shot_w / max(1, shot_h)) - (profile.ref_width / max(1, profile.ref_height))) > ASPECT_TOLERANCE:
        issues.append("当前宽高比与档案差异超过 2%。")
    if transport.rotation() != 0:
        issues.append("屏幕旋转不为 0。")
    if not profile.calibrated:
        warnings.append("该档案尚未完成落点校准。")
    return ConsistencyReport(
        ok=not issues,
        issues=issues,
        warnings=warnings,
        wm_size=(wm_w, wm_h),
        shot_size=(shot_w, shot_h),
        orientation=transport.rotation(),
    )


@dataclass
class CalibrationResult:
    ok: bool
    detail: str
    mean_diff: float = 0.0
    changed_ratio: float = 0.0


def calibrate_point(
    transport: BaseTransport,
    x: int,
    y: int,
    wait_ms: int = 800,
    region_w: int = 160,
    region_h: int = 120,
    min_changed_ratio: float = 0.02,
) -> CalibrationResult:
    """Tap a known reversible control and verify that the pixels changed.

    This replaces the invalid `show_touches` method from SPEC v0.2: injected
    taps do not produce /dev/input events, so the on-screen touch marker never
    appears. Comparing before/after crops proves the coordinate actually landed.
    """
    before = region(_load(transport.screencap()), x, y, region_w, region_h)
    result = transport.tap(x, y)
    if not result.ok:
        return CalibrationResult(False, f"点击失败：{result.text[:200]}")
    time.sleep(max(0, wait_ms) / 1000.0)
    after = region(_load(transport.screencap()), x, y, region_w, region_h)
    diff = diff_region(before, after)
    ok = diff.changed_ratio >= min_changed_ratio
    detail = (
        f"区域变化 {diff.changed_ratio:.1%}（阈值 {min_changed_ratio:.0%}），"
        + ("落点有效" if ok else "未观察到变化，请换一个可逆开关或检查坐标")
    )
    return CalibrationResult(ok, detail, diff.mean, diff.changed_ratio)


def _load(data: bytes):
    from .imageops import load_image

    return load_image(data)


class ProfileStore:
    def __init__(self, path: Path | None = None):
        from .paths import data_dir

        self.path = path or (data_dir() / "profiles.json")

    def load(self) -> list[DeviceProfile]:
        if not self.path.exists():
            return []
        try:
            raw = read_json(self.path)
        except Exception:  # noqa: BLE001
            return []
        items = raw.get("profiles", []) if isinstance(raw, dict) else []
        return [DeviceProfile.from_dict(x) for x in items if isinstance(x, dict)]

    def save(self, profiles: list[DeviceProfile]) -> None:
        atomic_write_json(self.path, {"profiles": [p.to_dict() for p in profiles]})

    def upsert(self, profile: DeviceProfile) -> None:
        items = self.load()
        for i, p in enumerate(items):
            if p.id == profile.id:
                items[i] = profile
                break
        else:
            items.append(profile)
        self.save(items)

    def delete(self, profile_id: str) -> None:
        self.save([p for p in self.load() if p.id != profile_id])
