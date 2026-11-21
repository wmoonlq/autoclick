from __future__ import annotations

import os
import re
import shlex
import shutil
import subprocess
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path

from .errors import CapabilityError, DeviceError
from .imageops import is_png, png_size

_CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


@dataclass
class CmdResult:
    code: int
    out: str = ""
    err: str = ""

    @property
    def ok(self) -> bool:
        return self.code == 0

    @property
    def text(self) -> str:
        return f"{self.out}\n{self.err}".strip()


@dataclass
class DeviceInfo:
    serial: str
    state: str
    model: str = ""
    product: str = ""
    transport: str = ""
    android: str = ""

    @property
    def usable(self) -> bool:
        return self.state == "device"

    @property
    def label(self) -> str:
        name = self.model or self.product or self.serial
        link = self.transport or ("USB" if ":" not in self.serial else "WiFi")
        return f"{name}  [{self.serial} · {link}]"


@dataclass
class CapabilityReport:
    ok: bool
    detail: str
    sdk: int = 0
    has_input: bool = False
    has_tap: bool = False
    warnings: list[str] = field(default_factory=list)


def find_adb(configured: str | None = None) -> str | None:
    candidates: list[str] = []
    if configured:
        candidates.append(configured)
    which = shutil.which("adb")
    if which:
        candidates.append(which)
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            candidates.append(str(Path(root) / "platform-tools" / "adb.exe"))
            candidates.append(str(Path(root) / "platform-tools" / "adb"))
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates.append(str(Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe"))
    candidates += [
        str(Path.home() / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe"),
        r"C:\platform-tools\adb.exe",
        r"D:\platform-tools\adb.exe",
        r"E:\platform-tools\adb.exe",
    ]
    for c in candidates:
        p = Path(c)
        if p.is_file():
            return str(p)
    return None


class BaseTransport(ABC):
    kind = "base"

    def __init__(self, serial: str):
        self.serial = serial

    # ---- primitives -------------------------------------------------
    @abstractmethod
    def shell(self, cmd: str, timeout: float = 8.0) -> CmdResult:
        raise NotImplementedError

    @abstractmethod
    def screencap(self) -> bytes:
        raise NotImplementedError

    # ---- input ------------------------------------------------------
    def tap(self, x: int, y: int) -> CmdResult:
        return self.shell(f"input tap {int(x)} {int(y)}")

    def long_press(self, x: int, y: int, duration_ms: int = 800) -> CmdResult:
        return self.shell(f"input swipe {int(x)} {int(y)} {int(x)} {int(y)} {int(duration_ms)}")

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> CmdResult:
        return self.shell(
            f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(duration_ms)}"
        )

    def key(self, keycode: str) -> CmdResult:
        code = str(keycode).strip().upper()
        if not re.fullmatch(r"(KEYCODE_[A-Z0-9_]+|\d{1,4})", code):
            raise ValueError(f"invalid keycode: {keycode!r}")
        return self.shell(f"input keyevent {code}")

    def text(self, content: str) -> CmdResult:
        escaped = content.replace(" ", "%s")
        return self.shell(f"input text {shlex.quote(escaped)}")

    def launch_app(self, package: str) -> CmdResult:
        if not re.fullmatch(r"[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+", package):
            raise ValueError(f"invalid package name: {package!r}")
        resolved = self.shell(f"cmd package resolve-activity --brief {package}", timeout=10)
        activity = ""
        for line in reversed(resolved.text.splitlines()):
            line = line.strip()
            if "/" in line and package in line:
                activity = line
                break
        if activity:
            result = self.shell(f"am start -n {shlex.quote(activity)}", timeout=15)
            if result.ok:
                return result
        return self.shell(
            f"monkey -p {package} -c android.intent.category.LAUNCHER 1", timeout=15
        )

    def stop_app(self, package: str) -> CmdResult:
        if not re.fullmatch(r"[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+", package):
            raise ValueError(f"invalid package name: {package!r}")
        return self.shell(f"am force-stop {package}")

    # ---- probes -----------------------------------------------------
    def wm_size(self) -> tuple[int, int, str]:
        r = self.shell("wm size")
        physical = re.search(r"Physical size:\s*(\d+)x(\d+)", r.text)
        override = re.search(r"Override size:\s*(\d+)x(\d+)", r.text)
        chosen = override or physical
        if not chosen:
            raise DeviceError(f"cannot parse wm size: {r.text!r}")
        return int(chosen.group(1)), int(chosen.group(2)), r.text.strip()

    def wm_density(self) -> int:
        r = self.shell("wm density")
        m = re.search(r"(\d+)", r.text)
        return int(m.group(1)) if m else 0

    def rotation(self) -> int:
        candidates = [
            ("dumpsys input", r"SurfaceOrientation:\s*(\d)"),
            ("dumpsys input", r"orientation=(\d)"),
            ("dumpsys window", r"mRotation=(\d)"),
            ("dumpsys window", r"rotation\s+(\d)"),
        ]
        for cmd, pattern in candidates:
            r = self.shell(cmd)
            m = re.search(pattern, r.text)
            if m:
                return int(m.group(1))
        return 0

    def awake(self) -> bool | None:
        r = self.shell("dumpsys power")
        m = re.search(r"mWakefulness=(\w+)", r.text)
        if not m:
            return None
        return m.group(1).lower() == "awake"

    def locked(self) -> bool | None:
        r = self.shell("dumpsys window")
        if "mDreamingLockscreen=true" in r.text or "mShowingLockscreen=true" in r.text:
            return True
        if "mDreamingLockscreen=false" in r.text:
            return False
        return None

    def wake(self) -> None:
        self.key("KEYCODE_WAKEUP")
        self.shell("wm dismiss-keyguard")

    def device_info(self) -> DeviceInfo:
        def prop(name: str) -> str:
            return self.shell(f"getprop {name}", timeout=5).out.strip()

        return DeviceInfo(
            serial=self.serial,
            state="device",
            model=prop("ro.product.model"),
            product=prop("ro.product.device"),
            android=prop("ro.build.version.release"),
        )

    def injection_check(self) -> CapabilityReport:
        r = self.shell("input", timeout=8)
        text = r.text
        sdk_r = self.shell("getprop ro.build.version.sdk", timeout=5)
        try:
            sdk = int(sdk_r.out.strip() or 0)
        except ValueError:
            sdk = 0
        if "INJECT_EVENTS" in text or "SecurityException" in text:
            return CapabilityReport(
                ok=False,
                detail="设备拒绝注入输入事件（INJECT_EVENTS）。小米/红米请开启「USB 调试（安全设置）」。",
                sdk=sdk,
            )
        has_input = "usage" in text.lower() and "input" in text.lower()
        has_tap = "tap" in text.lower()
        if not has_input:
            return CapabilityReport(ok=False, detail=f"无法确认 input 命令可用：{text[:200]!r}", sdk=sdk)
        if not has_tap:
            return CapabilityReport(ok=False, detail="input 命令缺少 tap 子命令", sdk=sdk)
        return CapabilityReport(ok=True, detail="input tap/swipe/keyevent 可用", sdk=sdk, has_input=True, has_tap=True)


class AdbTransport(BaseTransport):
    kind = "adb"

    def __init__(self, serial: str, adb_path: str):
        super().__init__(serial)
        self.adb_path = adb_path

    def _run(self, args: list[str], timeout: float = 10.0, binary: bool = False):
        cmd = [self.adb_path]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += args
        try:
            return subprocess.run(
                cmd,
                capture_output=True,
                timeout=timeout,
                creationflags=_CREATE_NO_WINDOW,
            )
        except subprocess.TimeoutExpired as exc:
            raise DeviceError(f"adb timeout after {timeout}s: {' '.join(args)}") from exc
        except OSError as exc:
            raise DeviceError(f"cannot run adb: {exc}") from exc

    def shell(self, cmd: str, timeout: float = 8.0) -> CmdResult:
        proc = self._run(["shell", cmd], timeout=timeout)
        return CmdResult(
            code=proc.returncode,
            out=proc.stdout.decode("utf-8", "replace").strip(),
            err=proc.stderr.decode("utf-8", "replace").strip(),
        )

    def screencap(self) -> bytes:
        proc = self._run(["exec-out", "screencap", "-p"], timeout=25, binary=True)
        data = proc.stdout or b""
        if is_png(data):
            return data
        # Fallback for ROMs where exec-out is broken.
        remote = "/data/local/tmp/outoclick_shot.png"
        self.shell(f"screencap -p {remote}", timeout=20)
        proc2 = self._run(["exec-out", "cat", remote], timeout=20, binary=True)
        self.shell(f"rm -f {remote}", timeout=5)
        if is_png(proc2.stdout or b""):
            return proc2.stdout
        raise DeviceError(
            "screencap failed: " + (proc.stderr.decode("utf-8", "replace")[:200] or "empty PNG")
        )

    @staticmethod
    def list_devices(adb_path: str) -> list[DeviceInfo]:
        try:
            proc = subprocess.run(
                [adb_path, "devices", "-l"],
                capture_output=True,
                timeout=12,
                creationflags=_CREATE_NO_WINDOW,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise DeviceError(f"adb devices failed: {exc}") from exc
        out = proc.stdout.decode("utf-8", "replace")
        devices: list[DeviceInfo] = []
        for line in out.splitlines():
            line = line.strip()
            if not line or line.startswith("List of devices") or line.startswith("*"):
                continue
            parts = line.split()
            if len(parts) < 2:
                continue
            serial, state = parts[0], parts[1]
            attrs: dict[str, str] = {}
            for token in parts[2:]:
                if ":" in token:
                    k, v = token.split(":", 1)
                    attrs[k] = v
            devices.append(
                DeviceInfo(
                    serial=serial,
                    state=state,
                    model=attrs.get("model", "").replace("_", " "),
                    product=attrs.get("product", ""),
                    transport="USB" if ":" not in serial else "WiFi",
                )
            )
        return devices


# ---------------------------------------------------------------------------
# Mock device: lets the whole MVP run without adb or a phone.
# ---------------------------------------------------------------------------

MOCK_W, MOCK_H = 1080, 2400
SWITCH_ON = (56, 161, 105)
SWITCH_OFF = (74, 85, 104)
ROW_HIT = (56, 161, 105)
ROW_IDLE = (45, 55, 72)
SWITCH_RECT = (860, 700, 980, 760)
ROW_RECTS = [(80, 900 + i * 180, 1000, 1020 + i * 180) for i in range(3)]
DONE_RECT = (340, 2100, 740, 2240)
MOCK_SWITCH_CENTER = (920, 730)
MOCK_ROW_CENTER = (540, 960)
MOCK_DONE_CENTER = (540, 2170)


class MockTransport(BaseTransport):
    """In-memory fake device with a simple deterministic UI.

    The UI exposes one toggle (920,730), three rows (540, 960/1140/1320)
    and a done button (540,2170), which makes tapping and assertPixel testable.
    """

    kind = "mock"
    SERIAL = "mock-1080x2400"

    def __init__(self) -> None:
        super().__init__(self.SERIAL)
        self.switch_on = False
        self.rows_hit: set[int] = set()
        self.done = False
        self.tap_count = 0
        self.last_tap: tuple[int, int] | None = None
        self.last_key = ""
        self.last_text = ""
        self.launched = ""
        self._cache: tuple[tuple, bytes] | None = None

    # -- rendering ----------------------------------------------------
    def _render(self) -> bytes:
        from io import BytesIO

        from PIL import Image, ImageDraw

        key = (self.switch_on, tuple(sorted(self.rows_hit)), self.done, self.tap_count)
        if self._cache and self._cache[0] == key:
            return self._cache[1]

        img = Image.new("RGB", (MOCK_W, MOCK_H), (16, 20, 24))
        d = ImageDraw.Draw(img)
        d.rectangle((0, 0, MOCK_W, 88), fill=(27, 32, 40))
        d.text((36, 34), "outoclick  mock device  1080x2400", fill=(160, 174, 192))
        d.text((36, 132), "DEMO APP", fill=(230, 237, 243))
        d.text((36, 176), "synthetic screen for offline testing", fill=(113, 128, 150))
        d.rounded_rectangle((60, 240, 1020, 620), radius=28, fill=(31, 111, 235))
        d.text((100, 300), "Tasks", fill=(255, 255, 255))
        d.text((100, 360), "tap the switch or a row", fill=(219, 234, 254))
        d.text((100, 440), "assertPixel checks the switch color", fill=(219, 234, 254))

        d.text((80, 660), "AUTO MODE", fill=(160, 174, 192))
        d.rounded_rectangle(SWITCH_RECT, radius=30, fill=SWITCH_ON if self.switch_on else SWITCH_OFF)
        d.text((820, 670), "ON" if self.switch_on else "OFF", fill=(230, 237, 243))

        for i, (x0, y0, x1, y1) in enumerate(ROW_RECTS):
            hit = i in self.rows_hit
            d.rounded_rectangle((x0, y0, x1, y1), radius=18, fill=ROW_HIT if hit else ROW_IDLE)
            d.text((x0 + 32, y0 + 28), f"Task {chr(65 + i)}", fill=(230, 237, 243))
            d.text((x0 + 32, y0 + 64), "done" if hit else "pending",
                   fill=(198, 246, 213) if hit else (113, 128, 150))
            if hit:
                d.text((x1 - 90, y0 + 54), "OK", fill=(198, 246, 213))

        d.rounded_rectangle(DONE_RECT, radius=28, fill=(229, 62, 62))
        d.text((480, 2160), "FINISH", fill=(255, 255, 255))
        d.text((36, 2320), f"taps: {self.tap_count}", fill=(113, 128, 150))

        buf = BytesIO()
        img.save(buf, "PNG", optimize=False)
        data = buf.getvalue()
        self._cache = (key, data)
        return data

    def screencap(self) -> bytes:
        return self._render()

    def shell(self, cmd: str, timeout: float = 8.0) -> CmdResult:
        cmd = cmd.strip()
        if cmd == "input":
            return CmdResult(0, "", "Usage: input [<source>] <command> [<arg>...]\n"
                                     "The commands are: text keyevent tap swipe press roll")
        if cmd.startswith("input tap "):
            _, _, x, y = cmd.split()[:4]
            self.tap(int(x), int(y))
            return CmdResult(0)
        if cmd.startswith("input swipe "):
            parts = cmd.split()
            self.tap(int(parts[2]), int(parts[3]))
            return CmdResult(0)
        if cmd.startswith("input keyevent "):
            self.last_key = cmd.split()[-1]
            if self.last_key in ("KEYCODE_WAKEUP", "KEYCODE_MENU"):
                pass
            return CmdResult(0)
        if cmd.startswith("input text "):
            self.last_text = cmd[len("input text "):]
            return CmdResult(0)
        if cmd == "wm size":
            return CmdResult(0, "Physical size: 1080x2400")
        if cmd == "wm density":
            return CmdResult(0, "Physical density: 420")
        if cmd == "dumpsys power":
            return CmdResult(0, "Power Manager State:\n  mWakefulness=Awake")
        if cmd == "dumpsys window":
            return CmdResult(0, "mDreamingLockscreen=false")
        if cmd == "dumpsys input":
            return CmdResult(0, "SurfaceOrientation: 0")
        if cmd.startswith("getprop ro.build.version.sdk"):
            return CmdResult(0, "34")
        if cmd.startswith("getprop ro.build.version.release"):
            return CmdResult(0, "14")
        if cmd.startswith("getprop ro.product.model"):
            return CmdResult(0, "Mock Phone")
        if cmd.startswith("getprop ro.product.device"):
            return CmdResult(0, "mock")
        if cmd.startswith("monkey -p "):
            self.launched = cmd.split()[2]
            return CmdResult(0, "Events injected: 1")
        if cmd.startswith("cmd package resolve-activity"):
            pkg = cmd.split()[-1]
            return CmdResult(0, f"priority=0\n{pkg}/.MainActivity")
        if cmd.startswith("am start -n "):
            self.launched = cmd.split()[-1]
            return CmdResult(0, "Starting: Intent")
        if cmd.startswith("am force-stop "):
            return CmdResult(0)
        return CmdResult(0, "")

    def tap(self, x: int, y: int) -> CmdResult:
        self.tap_count += 1
        self.last_tap = (int(x), int(y))
        if self._inside(x, y, SWITCH_RECT):
            self.switch_on = not self.switch_on
        for i, rect in enumerate(ROW_RECTS):
            if self._inside(x, y, rect):
                self.rows_hit.add(i)
        if self._inside(x, y, DONE_RECT):
            self.done = True
        return CmdResult(0)

    def swipe(self, x1: int, y1: int, x2: int, y2: int, duration_ms: int = 300) -> CmdResult:
        self.tap_count += 1
        self.last_tap = (int(x2), int(y2))
        return CmdResult(0)

    def device_info(self) -> DeviceInfo:
        return DeviceInfo(
            serial=self.serial,
            state="device",
            model="Mock Phone",
            product="mock",
            transport="Mock",
            android="14",
        )

    def injection_check(self) -> CapabilityReport:
        return CapabilityReport(
            ok=True,
            detail="模拟设备：input tap/swipe/keyevent 可用",
            sdk=34,
            has_input=True,
            has_tap=True,
        )

    def awake(self) -> bool | None:
        return True

    def locked(self) -> bool | None:
        return False

    def wm_size(self) -> tuple[int, int, str]:
        return MOCK_W, MOCK_H, "Physical size: 1080x2400"

    def wm_density(self) -> int:
        return 420

    def rotation(self) -> int:
        return 0

    @staticmethod
    def _inside(x: int, y: int, rect: tuple[int, int, int, int]) -> bool:
        x0, y0, x1, y1 = rect
        return x0 <= x <= x1 and y0 <= y <= y1
