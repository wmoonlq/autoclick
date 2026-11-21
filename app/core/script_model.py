from __future__ import annotations

import copy
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator

from .errors import ScriptVersionError
from .paths import atomic_write_json, read_json, safe_filename
from .profile import DeviceProfile

SUPPORTED_MAJOR = 1

STEP_TYPES = {
    "tap",
    "longPress",
    "swipe",
    "wait",
    "key",
    "text",
    "launchApp",
    "stopApp",
    "screenshot",
    "assertPixel",
    "note",
    "group",
}

POINT_TYPES = {"tap", "longPress", "assertPixel"}
DEFAULT_STEP: dict[str, Any] = {
    "stepDelayMs": 300,
    "jitterMs": 80,
    "retries": 1,
    "onError": "abort",
    "maxSteps": 10000,
    "offsetJitterPx": 0,
}
ON_ERROR = {"abort", "retry", "continue", "pause"}


@dataclass
class ValidationIssue:
    level: str
    message: str
    step_id: str = ""

    def __str__(self) -> str:
        prefix = "ERROR" if self.level == "error" else "WARN"
        sid = f"[{self.step_id}] " if self.step_id else ""
        return f"{prefix} {sid}{self.message}"


@dataclass
class Script:
    name: str
    steps: list[dict[str, Any]] = field(default_factory=list)
    id: str = field(default_factory=lambda: f"sc_{uuid.uuid4().hex[:12]}")
    schema_version: int = 1
    coord_mode: str = "absolute"
    device_profile_id: str = ""
    description: str = ""
    defaults: dict[str, Any] = field(default_factory=lambda: dict(DEFAULT_STEP))
    created_at: str = field(default_factory=lambda: time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    updated_at: str = field(default_factory=lambda: time.strftime("%Y-%m-%dT%H:%M:%S%z"))
    path: Path | None = None
    warnings: list[str] = field(default_factory=list)

    # ---- serialization ------------------------------------------------
    def to_dict(self) -> dict[str, Any]:
        return {
            "schemaVersion": self.schema_version,
            "coordMode": self.coord_mode,
            "id": self.id,
            "name": self.name,
            "description": self.description,
            "deviceProfileId": self.device_profile_id,
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
            "defaults": self.defaults,
            "steps": self.steps,
        }

    @classmethod
    def from_dict(cls, data: dict[str, Any], path: Path | None = None) -> "Script":
        if not isinstance(data, dict):
            raise ScriptVersionError("脚本根节点必须是对象")
        version = int(data.get("schemaVersion", 1) or 1)
        if version > SUPPORTED_MAJOR:
            raise ScriptVersionError(
                f"脚本版本 {version} 高于本程序支持版本 {SUPPORTED_MAJOR}，请升级 outoclick。"
            )
        warnings: list[str] = []
        known = {
            "schemaVersion", "coordMode", "id", "name", "description", "deviceProfileId",
            "createdAt", "updatedAt", "defaults", "steps",
        }
        unknown = set(data) - known
        if unknown:
            warnings.append("忽略未知字段：" + ", ".join(sorted(unknown)))
        steps = data.get("steps", [])
        if not isinstance(steps, list):
            raise ScriptVersionError("steps 必须是数组")
        defaults = dict(DEFAULT_STEP)
        defaults.update(data.get("defaults") or {})
        script = cls(
            name=str(data.get("name") or "未命名脚本"),
            steps=steps,
            id=str(data.get("id") or f"sc_{uuid.uuid4().hex[:12]}"),
            schema_version=version,
            coord_mode=str(data.get("coordMode") or "absolute"),
            device_profile_id=str(data.get("deviceProfileId") or ""),
            description=str(data.get("description") or ""),
            defaults=defaults,
            created_at=str(data.get("createdAt") or time.strftime("%Y-%m-%dT%H:%M:%S%z")),
            updated_at=str(data.get("updatedAt") or time.strftime("%Y-%m-%dT%H:%M:%S%z")),
            path=path,
            warnings=warnings,
        )
        return script

    @classmethod
    def load(cls, path: Path) -> "Script":
        raw = read_json(Path(path))
        return cls.from_dict(raw, Path(path))

    def save(self, path: Path | None = None) -> Path:
        target = Path(path or self.path or "")
        if not str(target):
            raise ValueError("no path for script")
        self.updated_at = time.strftime("%Y-%m-%dT%H:%M:%S%z")
        atomic_write_json(target, self.to_dict())
        self.path = target
        return target

    # ---- validation ---------------------------------------------------
    def validate(
        self,
        profile: DeviceProfile | None = None,
        for_run: bool = False,
    ) -> list[ValidationIssue]:
        issues: list[ValidationIssue] = []

        def err(msg: str, sid: str = "") -> None:
            issues.append(ValidationIssue("error", msg, sid))

        def warn(msg: str, sid: str = "") -> None:
            issues.append(ValidationIssue("warning", msg, sid))

        if not self.name.strip():
            warn("脚本名称为空")
        if self.coord_mode not in ("absolute", "percent"):
            err(f"未知 coordMode: {self.coord_mode}")
        if not self.steps:
            err("脚本没有任何步骤")
        if not self.device_profile_id:
            warn("脚本未绑定设备档案，运行时将要求选择")
        max_steps = int(self.defaults.get("maxSteps", 10000) or 10000)
        if max_steps <= 0:
            err("defaults.maxSteps 必须为正数")
        if self.defaults.get("onError") not in ON_ERROR:
            err(f"defaults.onError 非法：{self.defaults.get('onError')}")

        seen_ids: set[str] = set()

        def walk(steps: list[Any], depth: int, path_prefix: str) -> int:
            count = 0
            if depth > 5:
                err("分组嵌套超过 5 层", path_prefix)
                return 0
            for index, step in enumerate(steps):
                if not isinstance(step, dict):
                    err(f"第 {index + 1} 个步骤不是对象", path_prefix)
                    continue
                sid = str(step.get("id") or f"{path_prefix}{index + 1}")
                if sid in seen_ids:
                    err("步骤 id 重复", sid)
                seen_ids.add(sid)
                stype = step.get("type")
                if stype not in STEP_TYPES:
                    err(f"未知步骤类型：{stype!r}", sid)
                    continue
                for key in ("delayAfterMs", "jitterMs", "retries"):
                    if key in step and not isinstance(step[key], int):
                        err(f"{key} 必须是整数", sid)
                if "delayAfterMs" in step and step["delayAfterMs"] < 0:
                    err("delayAfterMs 不能为负", sid)
                if step.get("onError") and step["onError"] not in ON_ERROR:
                    err(f"onError 非法：{step.get('onError')}", sid)

                if stype in POINT_TYPES:
                    if not _check_point(step.get("point"), self.coord_mode, profile, err, warn, sid):
                        pass
                if stype == "swipe":
                    _check_point(step.get("from"), self.coord_mode, profile, err, warn, sid, "from")
                    _check_point(step.get("to"), self.coord_mode, profile, err, warn, sid, "to")
                    if int(step.get("durationMs", 300)) <= 0:
                        err("durationMs 必须为正数", sid)
                if stype == "longPress" and int(step.get("durationMs", 800)) <= 0:
                    err("durationMs 必须为正数", sid)
                if stype == "wait" and int(step.get("ms", 0)) < 0:
                    err("ms 不能为负", sid)
                if stype == "key":
                    code = str(step.get("keycode", ""))
                    if not code:
                        err("keycode 不能为空", sid)
                if stype == "text":
                    content = str(step.get("content", ""))
                    if not content:
                        warn("文本内容为空", sid)
                    try:
                        content.encode("ascii")
                    except UnicodeEncodeError:
                        (err if for_run else warn)(
                            "input text 仅支持 ASCII，中文会在运行时被阻止", sid
                        )
                if stype in ("launchApp", "stopApp") and not step.get("pkg"):
                    err("缺少包名 pkg", sid)
                if stype == "assertPixel":
                    color = str(step.get("color", ""))
                    if not _valid_color(color):
                        err(f"颜色格式非法：{color!r}", sid)
                    tol = int(step.get("tolerance", 10))
                    if not (0 <= tol <= 255):
                        err("tolerance 必须在 0..255", sid)
                if stype == "group":
                    repeat = int(step.get("repeat", 1))
                    if not (1 <= repeat <= 10000):
                        err("group.repeat 必须在 1..10000", sid)
                    children = step.get("steps")
                    if not isinstance(children, list) or not children:
                        err("group.steps 不能为空", sid)
                        continue
                    sub = walk(children, depth + 1, f"{sid}.")
                    count += sub * repeat
                    continue
                count += 1
            return count

        total = walk(self.steps, 0, "")
        if total > max_steps:
            err(f"展开后共 {total} 步，超过上限 {max_steps}")
        if for_run and self.coord_mode == "percent" and profile is not None:
            pass
        return issues

    # ---- iteration ----------------------------------------------------
    def iter_steps(self, _steps: list[dict[str, Any]] | None = None, depth: int = 0) -> Iterator[tuple[dict[str, Any], int]]:
        steps = self.steps if _steps is None else _steps
        for step in steps:
            if not isinstance(step, dict):
                continue
            if step.get("type") == "group":
                if step.get("enabled", True) is False:
                    continue
                repeat = int(step.get("repeat", 1))
                for _ in range(max(1, repeat)):
                    yield from self.iter_steps(step.get("steps") or [], depth + 1)
            else:
                if step.get("enabled", True) is False:
                    continue
                yield step, depth

    def resolved_delay(self, step: dict[str, Any]) -> int:
        return int(step.get("delayAfterMs", self.defaults.get("stepDelayMs", 300)))

    def resolved_jitter(self, step: dict[str, Any]) -> int:
        return max(0, int(step.get("jitterMs", self.defaults.get("jitterMs", 0))))

    def resolved_on_error(self, step: dict[str, Any]) -> str:
        value = step.get("onError") or self.defaults.get("onError") or "abort"
        return str(value)

    def resolved_offset_jitter(self, step: dict[str, Any]) -> int:
        return max(0, int(step.get("offsetJitterPx", self.defaults.get("offsetJitterPx", 0))))

    def expanded_count(self) -> int:
        return sum(1 for _ in self.iter_steps())


def _valid_color(value: str) -> bool:
    text = value.strip().lstrip("#")
    if len(text) == 3:
        text = "".join(c * 2 for c in text)
    return len(text) == 6 and all(c in "0123456789abcdefABCDEF" for c in text)


def _check_point(
    point: Any,
    coord_mode: str,
    profile: DeviceProfile | None,
    err,
    warn,
    sid: str,
    name: str = "point",
) -> bool:
    if not isinstance(point, dict) or "x" not in point or "y" not in point:
        err(f"{name} 必须是 {{x,y}}", sid)
        return False
    x, y = point.get("x"), point.get("y")
    if coord_mode == "percent":
        if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
            err(f"{name} 百分比坐标必须是数字", sid)
            return False
        if not (0 <= x <= 1 and 0 <= y <= 1):
            err(f"{name} 百分比坐标必须在 0..1", sid)
            return False
        return True
    if not isinstance(x, int) or not isinstance(y, int):
        err(f"{name} 坐标必须是整数", sid)
        return False
    if profile is not None:
        if not (0 <= x < profile.ref_width and 0 <= y < profile.ref_height):
            err(
                f"{name} ({x},{y}) 超出档案范围 "
                f"{profile.ref_width}x{profile.ref_height}",
                sid,
            )
            return False
    elif x < 0 or y < 0:
        err(f"{name} 坐标不能为负", sid)
        return False
    return True


def new_step(stype: str, x: int = 0, y: int = 0, **extra: Any) -> dict[str, Any]:
    step: dict[str, Any] = {
        "id": f"s_{uuid.uuid4().hex[:6]}",
        "type": stype,
        "label": stype,
        "enabled": True,
    }
    if stype in POINT_TYPES:
        step["point"] = {"x": int(x), "y": int(y)}
    if stype == "assertPixel":
        step["color"] = "#38A169"
        step["tolerance"] = 10
    if stype == "longPress":
        step["durationMs"] = 800
    if stype == "wait":
        step["ms"] = 1000
    if stype == "key":
        step["keycode"] = "KEYCODE_BACK"
    if stype == "text":
        step["content"] = ""
    if stype == "group":
        step["repeat"] = 1
        step["steps"] = []
    step.update(extra)
    return step


def step_summary(step: dict[str, Any]) -> str:
    stype = step.get("type", "?")
    if stype in POINT_TYPES:
        p = step.get("point") or {}
        base = f"({p.get('x')}, {p.get('y')})"
        if stype == "assertPixel":
            return f"{base} {step.get('color', '')} ±{step.get('tolerance', 10)}"
        return base
    if stype == "swipe":
        a = step.get("from") or {}
        b = step.get("to") or {}
        return f"({a.get('x')},{a.get('y')}) → ({b.get('x')},{b.get('y')}) {step.get('durationMs', 300)}ms"
    if stype == "wait":
        return f"{step.get('ms', 0)} ms"
    if stype in ("launchApp", "stopApp"):
        return str(step.get("pkg", ""))
    if stype == "key":
        return str(step.get("keycode", ""))
    if stype == "text":
        content = str(step.get("content", ""))
        return (content[:12] + "…") if len(content) > 12 else content
    if stype == "group":
        return f"循环 {step.get('repeat', 1)} 次 · {len(step.get('steps') or [])} 步"
    if stype == "note":
        return str(step.get("content", ""))[:24]
    if stype == "screenshot":
        return str(step.get("name", "shot"))
    return ""


def demo_script(profile: DeviceProfile | None = None) -> Script:
    from .transport import MOCK_DONE_CENTER, MOCK_ROW_CENTER, MOCK_SWITCH_CENTER, ROW_HIT, SWITCH_ON
    from .imageops import rgb_to_hex

    profile_id = profile.id if profile else ""
    steps = [
        {
            "id": f"s_{uuid.uuid4().hex[:6]}",
            "type": "note",
            "label": "说明",
            "content": "模拟设备演示：打开开关 → 校验颜色 → 完成任务 A → 结束",
        },
        new_step("tap", *MOCK_SWITCH_CENTER, label="打开 AUTO MODE", delayAfterMs=450),
        new_step(
            "assertPixel",
            *MOCK_SWITCH_CENTER,
            label="校验开关变绿",
            color=rgb_to_hex(SWITCH_ON),
            tolerance=10,
            retries=2,
            delayAfterMs=250,
        ),
        new_step("tap", *MOCK_ROW_CENTER, label="完成任务 A", delayAfterMs=300),
        new_step(
            "assertPixel",
            *MOCK_ROW_CENTER,
            label="校验任务 A 完成",
            color=rgb_to_hex(ROW_HIT),
            tolerance=10,
            retries=2,
            delayAfterMs=250,
        ),
        new_step("tap", *MOCK_DONE_CENTER, label="点击完成", delayAfterMs=300),
    ]
    return Script(
        name="演示：开关与任务",
        steps=steps,
        device_profile_id=profile_id,
        description="用于验证坐标拾取、点击、assertPixel 与日志链路",
    )


def blank_script(profile: DeviceProfile | None = None, name: str = "新建脚本") -> Script:
    return Script(
        name=name,
        steps=[new_step("note", label="说明", content="从这里开始添加步骤")],
        device_profile_id=profile.id if profile else "",
    )


def list_scripts(folder: Path) -> list[Path]:
    if not folder.exists():
        return []
    return sorted(folder.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)


def unique_path(folder: Path, name: str) -> Path:
    base = safe_filename(name)
    candidate = folder / f"{base}.json"
    index = 2
    while candidate.exists():
        candidate = folder / f"{base}-{index}.json"
        index += 1
    return candidate


def duplicate(script: Script) -> Script:
    data = copy.deepcopy(script.to_dict())
    data["id"] = f"sc_{uuid.uuid4().hex[:12]}"
    data["name"] = f"{script.name} 副本"
    data["createdAt"] = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    dup = Script.from_dict(data)
    dup.path = None
    return dup
