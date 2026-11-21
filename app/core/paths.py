from __future__ import annotations

import json
import os
import re
import tempfile
from pathlib import Path


APP_NAME = "AutoClickAndroid"


def data_dir() -> Path:
    """Return the single user data directory (SPEC 6.3)."""
    override = os.environ.get("OUTOCLICK_DATA")
    base = Path(override) if override else Path.home() / "Documents" / APP_NAME
    return base


def scripts_dir() -> Path:
    return data_dir() / "scripts"


def logs_dir() -> Path:
    return data_dir() / "logs"


def assets_dir() -> Path:
    return data_dir() / "assets"


def ensure_dirs() -> None:
    for p in (data_dir(), scripts_dir(), logs_dir(), assets_dir()):
        p.mkdir(parents=True, exist_ok=True)


def atomic_write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name, suffix=".tmp", dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def read_json(path: Path) -> object:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def safe_filename(name: str, fallback: str = "script") -> str:
    cleaned = re.sub(r'[\\/:*?"<>|\r\n\t]+', "_", name).strip(" .")
    return cleaned[:80] or fallback
