from __future__ import annotations

import ctypes
import sys
from ctypes import wintypes

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QWidget

WM_HOTKEY = 0x0312
MOD_NOREPEAT = 0x4000

VK = {
    "F8": 0x77,
    "F9": 0x78,
    "F10": 0x79,
    "F11": 0x7A,
    "F12": 0x7B,
}


class _MSG(ctypes.Structure):
    _fields_ = [
        ("hwnd", wintypes.HWND),
        ("message", wintypes.UINT),
        ("wParam", wintypes.WPARAM),
        ("lParam", wintypes.LPARAM),
        ("time", wintypes.DWORD),
        ("pt", wintypes.POINT),
    ]


class GlobalHotkey(QWidget):
    """Windows RegisterHotKey wrapper: F12 works even when the app is unfocused."""

    triggered = Signal()

    def __init__(self, key: str = "F12", hotkey_id: int = 0xB001, parent=None):
        super().__init__(parent)
        self.setWindowFlags(Qt.WindowType.Tool | Qt.WindowType.FramelessWindowHint)
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating, True)
        self.hide()
        self.hotkey_id = hotkey_id
        self._mod_id = hotkey_id
        self.registered = False
        self.key = key.upper()
        self.error = ""
        if sys.platform != "win32":
            self.error = "非 Windows 平台不支持全局热键"
            return
        # F12 is grabbed by some IDEs/tools on real machines; fall back instead of
        # silently losing the emergency stop.
        candidates = [self.key] + [k for k in ("F11", "F10", "F9", "F8") if k != self.key]
        for offset, candidate in enumerate(candidates):
            vk = VK.get(candidate)
            if not vk:
                continue
            candidate_id = hotkey_id + offset
            try:
                ok = bool(
                    ctypes.windll.user32.RegisterHotKey(
                        int(self.winId()), candidate_id, MOD_NOREPEAT, vk
                    )
                )
            except Exception:  # noqa: BLE001
                ok = False
            if ok:
                self.key = candidate
                self._mod_id = candidate_id
                self.registered = True
                return
        self.error = f"RegisterHotKey 失败（{self.key} 及备用键均被占用）"

    def nativeEvent(self, event_type, message):  # noqa: N802
        if sys.platform == "win32":
            try:
                msg = ctypes.cast(int(message), ctypes.POINTER(_MSG)).contents
                if msg.message == WM_HOTKEY and int(msg.wParam) == self._mod_id:
                    self.triggered.emit()
                    return True, 0
            except Exception:  # noqa: BLE001
                pass
        return super().nativeEvent(event_type, message)

    def closeEvent(self, event):  # noqa: N802
        if self.registered and sys.platform == "win32":
            try:
                ctypes.windll.user32.UnregisterHotKey(int(self.winId()), self._mod_id)
            except Exception:  # noqa: BLE001
                pass
        super().closeEvent(event)
