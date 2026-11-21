from __future__ import annotations

from dataclasses import dataclass

from .transport import AdbTransport, BaseTransport, DeviceInfo, MockTransport, find_adb


@dataclass
class DeviceEntry:
    info: DeviceInfo
    mock: bool = False


class DeviceManager:
    """Thin facade over adb discovery / transport creation."""

    def __init__(self, adb_path: str | None = None):
        self.adb_path = find_adb(adb_path)
        self.last_error = ""

    @property
    def adb_ready(self) -> bool:
        return bool(self.adb_path)

    def list_devices(self, include_mock: bool = True) -> list[DeviceEntry]:
        entries: list[DeviceEntry] = []
        if self.adb_path:
            try:
                for info in AdbTransport.list_devices(self.adb_path):
                    entries.append(DeviceEntry(info=info))
                self.last_error = ""
            except Exception as exc:  # noqa: BLE001
                self.last_error = str(exc)
        else:
            self.last_error = "未找到 adb（Android Platform-Tools）"
        if include_mock:
            mock_info = MockTransport().device_info()
            entries.append(DeviceEntry(info=mock_info, mock=True))
        return entries

    def transport_for(self, entry: DeviceEntry) -> BaseTransport:
        if entry.mock:
            return MockTransport()
        if not self.adb_path:
            raise RuntimeError("adb 不可用")
        return AdbTransport(entry.info.serial, self.adb_path)
