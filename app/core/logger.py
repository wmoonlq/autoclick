from __future__ import annotations

import json
import shutil
import threading
import time
import uuid
from pathlib import Path
from typing import Any

from .paths import logs_dir

KEEP_RUNS = 50


def redact_text(text: str) -> str:
    if len(text) <= 4:
        return "*" * len(text)
    return f"{text[:2]}***{text[-2:]}"


class RunLogger:
    def __init__(self, script_name: str, script_id: str = ""):
        stamp = time.strftime("%Y%m%d-%H%M%S")
        self.run_id = f"run_{stamp}_{uuid.uuid4().hex[:6]}"
        self.dir = logs_dir() / self.run_id
        self.dir.mkdir(parents=True, exist_ok=True)
        self.jsonl_path = self.dir / "log.jsonl"
        self.script_name = script_name
        self.script_id = script_id
        self._lock = threading.Lock()
        self._fh = open(self.jsonl_path, "a", encoding="utf-8", newline="\n")
        self._closed = False
        self._prune()

    def _prune(self) -> None:
        try:
            runs = sorted(
                (p for p in logs_dir().glob("run_*") if p.is_dir()),
                key=lambda p: p.stat().st_mtime,
                reverse=True,
            )
            for old in runs[KEEP_RUNS:]:
                shutil.rmtree(old, ignore_errors=True)
        except Exception:  # noqa: BLE001
            pass

    def event(self, event: str, level: str = "INFO", **fields: Any) -> dict[str, Any]:
        record: dict[str, Any] = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}",
            "runId": self.run_id,
            "level": level,
            "event": event,
        }
        record.update(fields)
        with self._lock:
            if not self._closed:
                self._fh.write(json.dumps(record, ensure_ascii=False) + "\n")
                self._fh.flush()
        return record

    def screenshot(self, name: str, data: bytes) -> Path:
        safe = "".join(c if c.isalnum() or c in "._-" else "_" for c in name)[:60] or "shot"
        path = self.dir / f"{safe}.png"
        path.write_bytes(data)
        self.event("screenshot", path=str(path), bytes=len(data))
        return path

    def finish(self, status: str, summary: dict[str, Any]) -> None:
        self.event("run_end", status=status, **summary)
        lines = [
            f"# 运行 {self.run_id}",
            "",
            f"- 脚本：{self.script_name}",
            f"- 结果：{status}",
            f"- 步数：{summary.get('ok', 0)} 成功 / {summary.get('fail', 0)} 失败 / {summary.get('skip', 0)} 跳过",
            f"- 耗时：{summary.get('elapsedMs', 0) / 1000:.1f}s",
            f"- 校验步通过率：{summary.get('assertPass', 0)}/{summary.get('assertTotal', 0)}",
            "",
        ]
        fail_shot = summary.get("failScreenshot")
        if fail_shot:
            lines.append(f"失败截图：{Path(fail_shot).name}")
        (self.dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.close()

    def close(self) -> None:
        with self._lock:
            if not self._closed:
                self._closed = True
                self._fh.close()
