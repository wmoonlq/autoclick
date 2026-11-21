from __future__ import annotations

import random
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable

from .coords import from_percent, to_device
from .errors import CapabilityError, DeviceError, OutOfBounds, OutoclickError, ResolutionMismatch
from .imageops import color_distance, hex_to_rgb, load_image, sample_pixel
from .logger import RunLogger
from .profile import DeviceProfile, validate_for_run
from .script_model import Script


class EngineState(str, Enum):
    IDLE = "Idle"
    PREPARING = "Preparing"
    RUNNING = "Running"
    PAUSED = "Paused"
    STOPPING = "Stopping"
    ERROR = "Error"


@dataclass
class EngineEvent:
    kind: str
    payload: dict[str, Any] = field(default_factory=dict)


@dataclass
class StepOutcome:
    ok: bool
    canceled: bool = False
    detail: str = ""
    sent: dict[str, int] | None = None
    skipped: bool = False
    extra: dict[str, Any] = field(default_factory=dict)


class Engine:
    """Sequential, cancellable executor. One adb call per action (SPEC 11.3)."""

    def __init__(
        self,
        transport,
        script: Script,
        profile: DeviceProfile,
        logger: RunLogger | None = None,
        on_event: Callable[[EngineEvent], None] | None = None,
    ):
        self.transport = transport
        self.script = script
        self.profile = profile
        self.logger = logger or RunLogger(script.name, script.id)
        self.on_event = on_event
        self.state = EngineState.IDLE
        self._cancel = threading.Event()
        self._pause = threading.Event()
        self._lock = threading.RLock()
        self._thread: threading.Thread | None = None
        self.runtime_size: tuple[int, int] = (profile.ref_width, profile.ref_height)
        self.fail_screenshot = ""
        self.last_error = ""

    # ---- controls -----------------------------------------------------
    def start(self) -> None:
        with self._lock:
            if self._thread and self._thread.is_alive():
                raise RuntimeError("engine already running")
            self._cancel.clear()
            self._pause.clear()
            self._thread = threading.Thread(target=self._run_guarded, name="outoclick-engine", daemon=True)
            self._thread.start()

    def join(self, timeout: float | None = None) -> None:
        if self._thread:
            self._thread.join(timeout)

    def pause(self) -> None:
        if self.state in (EngineState.RUNNING,):
            self._pause.set()
            self._set_state(EngineState.PAUSED)
            self._emit("state", state=self.state.value, message="已暂停")

    def resume(self) -> None:
        if self.state is EngineState.PAUSED:
            self._pause.clear()
            self._set_state(EngineState.RUNNING)
            self._emit("state", state=self.state.value, message="继续运行")

    def stop(self) -> None:
        if self.state in (EngineState.RUNNING, EngineState.PAUSED, EngineState.PREPARING):
            self._cancel.set()
            self._pause.clear()
            self._set_state(EngineState.STOPPING)
            self._emit("state", state=self.state.value, message="正在停止")

    @property
    def is_alive(self) -> bool:
        return bool(self._thread and self._thread.is_alive())

    # ---- internals ----------------------------------------------------
    def _set_state(self, state: EngineState) -> None:
        with self._lock:
            self.state = state

    def _emit(self, kind: str, **payload: Any) -> None:
        if self.on_event:
            try:
                self.on_event(EngineEvent(kind, payload))
            except Exception:  # noqa: BLE001
                pass

    def _run_guarded(self) -> None:
        started = time.monotonic()
        self.last_error = ""
        ok = fail = skip = assert_pass = assert_total = 0
        status = "error"
        step_total = 0
        try:
            self._set_state(EngineState.PREPARING)
            self._emit("state", state=self.state.value, message="准备中")
            self.logger.event("run_start", scriptId=self.script.id, script=self.script.name,
                              profileId=self.profile.id, serial=self.transport.serial)
            self._prepare()

            steps = list(self.script.iter_steps())
            step_total = len(steps)
            self._set_state(EngineState.RUNNING)
            self._emit("state", state=self.state.value, message="运行中",
                       total=step_total)

            for index, (step, depth) in enumerate(steps):
                if self._cancel.is_set():
                    break
                if not self._wait_if_paused():
                    break
                sid = str(step.get("id") or f"#{index + 1}")
                stype = str(step.get("type") or "?")
                self._emit("step_start", index=index, total=step_total, stepId=sid, type=stype,
                           label=str(step.get("label") or ""), depth=depth)
                t0 = time.monotonic()
                try:
                    outcome = self._execute(step)
                except (ResolutionMismatch, OutOfBounds) as exc:
                    outcome = StepOutcome(False, detail=f"坐标错误：{exc}")
                except CapabilityError as exc:
                    outcome = StepOutcome(False, detail=f"能力不足：{exc}")
                except DeviceError as exc:
                    outcome = StepOutcome(False, detail=f"设备错误：{exc}")
                except Exception as exc:  # noqa: BLE001
                    outcome = StepOutcome(False, detail=f"异常：{type(exc).__name__}: {exc}")

                latency = int((time.monotonic() - t0) * 1000)
                if outcome.extra.get("assertTotal"):
                    assert_total += 1
                    assert_pass += 1 if outcome.ok else 0

                if outcome.canceled:
                    break
                if outcome.skipped:
                    skip += 1
                    self._emit("step_end", index=index, total=step_total, stepId=sid,
                               type=stype, status="skip", latencyMs=latency, detail=outcome.detail)
                    continue

                if outcome.ok:
                    ok += 1
                    self.logger.event(
                        "action_sent", stepId=sid, type=stype, status="ok",
                        sent=outcome.sent, latencyMs=latency, detail=outcome.detail or None,
                    )
                    self._emit("step_end", index=index, total=step_total, stepId=sid,
                               type=stype, status="ok", latencyMs=latency, detail=outcome.detail)
                else:
                    fail += 1
                    self._capture_failure(sid)
                    self.logger.event(
                        "action_sent", stepId=sid, type=stype, status="fail",
                        sent=outcome.sent, latencyMs=latency, error=outcome.detail,
                    )
                    self._emit("step_end", index=index, total=step_total, stepId=sid,
                               type=stype, status="fail", latencyMs=latency,
                               error=outcome.detail, screenshot=self.fail_screenshot)
                    policy = self.script.resolved_on_error(step)
                    if policy == "continue":
                        self.logger.event("policy", stepId=sid, policy="continue")
                    elif policy == "pause":
                        self._pause.set()
                        self._set_state(EngineState.PAUSED)
                        self._emit("state", state=self.state.value,
                                   message=f"步骤 {sid} 失败，已暂停等待处理")
                        if not self._wait_if_paused():
                            break
                    else:
                        self.last_error = outcome.detail
                        self._set_state(EngineState.ERROR)
                        break

                if not self._sleep_ms(self.script.resolved_delay(step)):
                    break

            status = "stopped" if self._cancel.is_set() else ("ok" if fail == 0 else "failed")
        except OutoclickError as exc:
            status = "error"
            self.last_error = str(exc)
            self._set_state(EngineState.ERROR)
            self.logger.event("error", level="ERROR", error=str(exc))
            self._emit("state", state=self.state.value, message=str(exc))
        except Exception as exc:  # noqa: BLE001
            status = "error"
            self.last_error = f"{type(exc).__name__}: {exc}"
            self._set_state(EngineState.ERROR)
            self.logger.event("error", level="ERROR", error=self.last_error)
            self._emit("state", state=self.state.value, message=self.last_error)
        finally:
            elapsed = int((time.monotonic() - started) * 1000)
            summary = {
                "ok": ok,
                "fail": fail,
                "skip": skip,
                "total": step_total,
                "elapsedMs": elapsed,
                "avgStepMs": int(elapsed / max(1, ok + fail + skip)),
                "assertPass": assert_pass,
                "assertTotal": assert_total,
                "failScreenshot": self.fail_screenshot,
                "state": self.state.value,
            }
            try:
                self.logger.finish(status, summary)
            except Exception:  # noqa: BLE001
                pass
            self._set_state(EngineState.IDLE)
            self._emit("run_end", status=status, summary=summary)
            self._emit("state", state=self.state.value,
                       message="已完成" if status == "ok" else f"结束：{status}")

    def _prepare(self) -> None:
        issues = self.script.validate(self.profile, for_run=True)
        errors = [i for i in issues if i.level == "error"]
        if errors:
            raise CapabilityError("脚本校验失败：" + "；".join(str(i) for i in errors))

        report = validate_for_run(self.profile, self.transport)
        if not report.ok:
            raise CapabilityError("；".join(report.issues))

        cap = self.transport.injection_check()
        if not cap.ok:
            raise CapabilityError(cap.detail)

        awake = self.transport.awake()
        if awake is False:
            self._emit("log", level="WARN", message="屏幕未唤醒，尝试唤醒")
            self.transport.wake()
            time.sleep(0.6)
            awake = self.transport.awake()
        if awake is False:
            raise CapabilityError("设备屏幕未唤醒，无法注入点击")

        locked = self.transport.locked()
        if locked:
            self.transport.wake()
            time.sleep(0.6)
            if self.transport.locked():
                raise CapabilityError("设备处于锁屏状态，请先解锁")

        wm_w, wm_h, _ = self.transport.wm_size()
        self.runtime_size = (wm_w, wm_h)
        for warning in report.warnings:
            self.logger.event("warning", level="WARN", message=warning)
            self._emit("log", level="WARN", message=warning)

    def _wait_if_paused(self) -> bool:
        while self._pause.is_set():
            if self._cancel.is_set():
                return False
            time.sleep(0.05)
        return not self._cancel.is_set()

    def _sleep_ms(self, ms: int) -> bool:
        end = time.monotonic() + max(0, ms) / 1000.0
        while True:
            if self._cancel.is_set():
                return False
            if self._pause.is_set():
                pause_start = time.monotonic()
                if not self._wait_if_paused():
                    return False
                end += time.monotonic() - pause_start
            remaining = end - time.monotonic()
            if remaining <= 0:
                return True
            time.sleep(min(0.05, remaining))

    def _resolve(self, point: dict[str, Any]) -> tuple[int, int]:
        w, h = self.runtime_size
        if self.script.coord_mode == "percent":
            return from_percent(float(point["x"]), float(point["y"]), w, h)
        return to_device(
            int(point["x"]), int(point["y"]),
            self.profile.ref_width, self.profile.ref_height, w, h,
        )

    def _jitter(self, x: int, y: int, amount: int) -> tuple[int, int]:
        if amount <= 0:
            return x, y
        w, h = self.runtime_size
        return (
            min(max(0, x + random.randint(-amount, amount)), w - 1),
            min(max(0, y + random.randint(-amount, amount)), h - 1),
        )

    def _execute(self, step: dict[str, Any]) -> StepOutcome:
        stype = str(step.get("type"))
        if stype == "note":
            return StepOutcome(True, detail=str(step.get("content", "")))
        if stype == "wait":
            done = self._sleep_ms(int(step.get("ms", 0)))
            return StepOutcome(done, canceled=not done, detail="等待完成")
        if stype == "tap":
            x, y = self._resolve(step["point"])
            x, y = self._jitter(x, y, self.script.resolved_offset_jitter(step))
            result = self.transport.tap(x, y)
            return StepOutcome(result.ok, detail=result.text, sent={"x": x, "y": y})
        if stype == "longPress":
            x, y = self._resolve(step["point"])
            duration = int(step.get("durationMs", 800))
            result = self.transport.long_press(x, y, duration)
            return StepOutcome(result.ok, detail=result.text, sent={"x": x, "y": y, "ms": duration})
        if stype == "swipe":
            x1, y1 = self._resolve(step["from"])
            x2, y2 = self._resolve(step["to"])
            duration = int(step.get("durationMs", 300))
            result = self.transport.swipe(x1, y1, x2, y2, duration)
            return StepOutcome(result.ok, detail=result.text,
                               sent={"x1": x1, "y1": y1, "x2": x2, "y2": y2, "ms": duration})
        if stype == "key":
            result = self.transport.key(str(step.get("keycode", "")))
            return StepOutcome(result.ok, detail=result.text,
                               sent={"keycode": str(step.get("keycode", ""))})
        if stype == "text":
            content = str(step.get("content", ""))
            content.encode("ascii")  # hard block, never send garbled ANSI
            result = self.transport.text(content)
            from .logger import redact_text

            return StepOutcome(result.ok, detail=f"text={redact_text(content)}")
        if stype == "launchApp":
            result = self.transport.launch_app(str(step.get("pkg", "")))
            return StepOutcome(result.ok, detail=result.text, sent={"pkg": str(step.get("pkg", ""))})
        if stype == "stopApp":
            result = self.transport.stop_app(str(step.get("pkg", "")))
            return StepOutcome(result.ok, detail=result.text)
        if stype == "screenshot":
            data = self.transport.screencap()
            path = self.logger.screenshot(str(step.get("name") or "manual"), data)
            return StepOutcome(True, detail=str(path))
        if stype == "assertPixel":
            return self._assert_pixel(step)
        return StepOutcome(False, detail=f"MVP 不支持步骤类型：{stype}")

    def _assert_pixel(self, step: dict[str, Any]) -> StepOutcome:
        x, y = self._resolve(step["point"])
        expected = hex_to_rgb(str(step.get("color", "#000000")))
        tolerance = int(step.get("tolerance", 10))
        retries = max(0, int(step.get("retries", self.script.defaults.get("retries", 0)) or 0))
        last = (0, 0, 0)
        distance = 999
        for attempt in range(retries + 1):
            shot = self.transport.screencap()
            img = load_image(shot)
            last = sample_pixel(img, x, y)
            distance = color_distance(last, expected)
            if distance <= tolerance:
                return StepOutcome(
                    True,
                    detail=f"期望 {step.get('color')} 实际 {('#%02X%02X%02X' % last)} 距离 {distance}",
                    sent={"x": x, "y": y},
                    extra={"assertTotal": True},
                )
            self.logger.event(
                "assert_miss", level="WARN", stepId=str(step.get("id", "")),
                expected=str(step.get("color")), actual="#%02X%02X%02X" % last,
                distance=distance, attempt=attempt + 1,
            )
            if attempt < retries and not self._sleep_ms(300):
                return StepOutcome(False, canceled=True)
        return StepOutcome(
            False,
            detail=f"像素不匹配：期望 {step.get('color')} 实际 {'#%02X%02X%02X' % last} 距离 {distance}",
            sent={"x": x, "y": y},
            extra={"assertTotal": True},
        )

    def _capture_failure(self, step_id: str) -> None:
        try:
            data = self.transport.screencap()
            self.fail_screenshot = str(self.logger.screenshot(f"fail_{step_id}", data))
        except Exception:  # noqa: BLE001
            self.fail_screenshot = ""
