from __future__ import annotations

import sys

from PySide6.QtGui import QFont
from PySide6.QtWidgets import QApplication

from .core.paths import ensure_dirs

QSS = """
QMainWindow, QWidget {
    background: #0F1319;
    color: #D7E0EA;
    font-family: "Microsoft YaHei UI", "Segoe UI", sans-serif;
    font-size: 12px;
}
QMenuBar { background: #121821; border-bottom: 1px solid #1E2733; }
QMenuBar::item { padding: 5px 10px; background: transparent; }
QMenuBar::item:selected { background: #1C2735; border-radius: 4px; }
QMenu { background: #151C26; border: 1px solid #263140; padding: 4px; }
QMenu::item { padding: 6px 26px 6px 18px; border-radius: 4px; }
QMenu::item:selected { background: #1D3A5F; }
QStatusBar { background: #0C1117; color: #8296AB; border-top: 1px solid #1B2430; }
QFrame#deviceBar {
    background: #131A23;
    border: 1px solid #22303F;
    border-radius: 8px;
}
QPushButton {
    background: #1B2430;
    border: 1px solid #2B3644;
    border-radius: 6px;
    padding: 6px 12px;
    color: #D7E0EA;
}
QPushButton:hover { background: #243040; border-color: #3A4A5E; }
QPushButton:pressed { background: #16202B; }
QPushButton:disabled { color: #56657A; background: #141B24; border-color: #1E2833; }
QPushButton#primary { background: #1D4ED8; border-color: #2563EB; font-weight: 600; }
QPushButton#primary:hover { background: #2563EB; }
QPushButton#danger { background: #B91C1C; border-color: #DC2626; font-weight: 600; }
QPushButton#danger:hover { background: #DC2626; }
QComboBox, QLineEdit, QSpinBox, QDoubleSpinBox {
    background: #101821;
    border: 1px solid #263140;
    border-radius: 6px;
    padding: 5px 8px;
    selection-background-color: #1D4ED8;
}
QComboBox:focus, QLineEdit:focus, QSpinBox:focus { border-color: #3B82F6; }
QComboBox QAbstractItemView {
    background: #131A23;
    border: 1px solid #263140;
    selection-background-color: #1D3A5F;
}
QListWidget, QTableWidget, QPlainTextEdit {
    background: #101821;
    border: 1px solid #22303F;
    border-radius: 6px;
    alternate-background-color: #121A24;
}
QListWidget::item { padding: 7px 8px; border-radius: 4px; }
QListWidget::item:selected { background: #1D3A5F; color: #E6EDF3; }
QTableWidget { gridline-color: #1D2735; }
QTableWidget::item:selected { background: #1D3A5F; }
QHeaderView::section {
    background: #151E29;
    color: #8FA3B8;
    border: none;
    border-bottom: 1px solid #263140;
    padding: 6px;
    font-weight: 600;
}
QPlainTextEdit { color: #A9B8C9; font-family: Consolas, "Cascadia Mono", monospace; font-size: 11px; }
QScrollArea { border: none; background: transparent; }
QGroupBox {
    border: 1px solid #22303F;
    border-radius: 8px;
    margin-top: 12px;
    padding-top: 4px;
    font-weight: 600;
    color: #8FA3B8;
}
QGroupBox::title { subcontrol-origin: margin; left: 10px; padding: 0 4px; }
QLabel#section { color: #7C93AA; font-weight: 700; letter-spacing: 1px; }
QLabel#typeBadge {
    color: #93C5FD;
    background: #16273B;
    border: 1px solid #24425F;
    border-radius: 5px;
    padding: 3px 8px;
    font-weight: 600;
}
QLabel#modeLabel { color: #F59E0B; font-weight: 600; }
QLabel#hint { color: #6C7F95; font-size: 11px; }
QLabel#stateLabel { color: #63B3ED; font-weight: 600; min-width: 70px; }
QProgressBar {
    background: #101821;
    border: 1px solid #22303F;
    border-radius: 6px;
    text-align: center;
    color: #B9C7D6;
    height: 16px;
}
QProgressBar::chunk { background: #1D4ED8; border-radius: 5px; }
QSplitter::handle { background: #171F2A; }
QSplitter::handle:hover { background: #22303F; }
QCheckBox { spacing: 6px; }
QToolTip {
    background: #1B2430;
    color: #D7E0EA;
    border: 1px solid #2B3644;
    padding: 4px 6px;
}
"""


def _setup_console() -> None:
    if sys.platform == "win32":
        try:
            import ctypes

            ctypes.windll.kernel32.SetConsoleOutputCP(65001)
        except Exception:  # noqa: BLE001
            pass
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            pass


def main() -> int:
    _setup_console()
    ensure_dirs()
    app = QApplication(sys.argv)
    app.setApplicationName("outoclick")
    app.setApplicationDisplayName("outoclick")
    app.setStyle("Fusion")
    app.setFont(QFont("Microsoft YaHei UI", 9))
    app.setStyleSheet(QSS)

    from .ui.main_window import MainWindow

    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
