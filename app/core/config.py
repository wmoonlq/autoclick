from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .paths import atomic_write_json, data_dir, read_json


@dataclass
class AppConfig:
    adb_path: str = ""
    prefer_mock: bool = False
    hotkey: str = "F12"
    last_device_serial: str = ""
    last_script_path: str = ""
    window_width: int = 1280
    window_height: int = 820
    extra: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> "AppConfig":
        known = {f for f in cls.__dataclass_fields__ if f != "extra"}
        extra = {k: v for k, v in data.items() if k not in known}
        cfg = cls(**{k: v for k, v in data.items() if k in known})
        cfg.extra = extra
        return cfg


class ConfigStore:
    def __init__(self, path: Path | None = None):
        self.path = path or (data_dir() / "settings.json")

    def load(self) -> AppConfig:
        if not self.path.exists():
            return AppConfig()
        try:
            raw = read_json(self.path)
            return AppConfig.from_dict(raw if isinstance(raw, dict) else {})
        except Exception:  # noqa: BLE001
            return AppConfig()

    def save(self, config: AppConfig) -> None:
        atomic_write_json(self.path, config.to_dict())
