from __future__ import annotations

from PySide6.QtCore import QPoint, Qt, Signal
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import QApplication, QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget


class Hud(QWidget):
    """Always-on-top control strip: the PC equivalent of an on-device floating ball."""

    pause_clicked = Signal()
    stop_clicked = Signal()

    def __init__(self):
        super().__init__(
            None,
            Qt.WindowType.Tool
            | Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint,
        )
        self.setAttribute(Qt.WidgetAttribute.WA_ShowWithoutActivating, True)
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground, True)
        self._drag: QPoint | None = None
        self._paused = False

        root = QVBoxLayout(self)
        root.setContentsMargins(12, 10, 12, 10)
        card = QWidget()
        card.setObjectName("hudCard")
        root.addWidget(card)

        inner = QVBoxLayout(card)
        inner.setContentsMargins(14, 10, 14, 10)
        inner.setSpacing(6)

        self.title = QLabel("outoclick")
        self.title.setObjectName("hudTitle")
        self.progress = QLabel("等待运行")
        self.progress.setObjectName("hudProgress")
        row = QHBoxLayout()
        row.setSpacing(8)
        self.btn_pause = QPushButton("暂停")
        self.btn_pause.setObjectName("hudPause")
        self.btn_stop = QPushButton("急停 F12")
        self.btn_stop.setObjectName("hudStop")
        row.addWidget(self.btn_pause)
        row.addWidget(self.btn_stop)
        row.addStretch(1)

        inner.addWidget(self.title)
        inner.addWidget(self.progress)
        inner.addLayout(row)

        self.btn_pause.clicked.connect(self._on_pause)
        self.btn_stop.clicked.connect(self.stop_clicked.emit)

        self.setStyleSheet(
            """
            #hudCard { background: rgba(17, 22, 30, 235); border: 1px solid #2D3748; border-radius: 12px; }
            #hudTitle { color: #E6EDF3; font-weight: 700; font-size: 13px; }
            #hudProgress { color: #9FB0C3; font-size: 12px; }
            QPushButton { color: #E6EDF3; background: #1F2937; border: 1px solid #374151;
                          border-radius: 7px; padding: 5px 12px; font-size: 12px; }
            QPushButton:hover { background: #2D3748; }
            #hudStop { background: #B91C1C; border-color: #B91C1C; font-weight: 600; }
            #hudStop:hover { background: #DC2626; }
            """
        )
        self.setMinimumWidth(300)

    def _on_pause(self) -> None:
        self._paused = not self._paused
        self.btn_pause.setText("继续" if self._paused else "暂停")
        self.pause_clicked.emit()

    def reset_pause(self) -> None:
        self._paused = False
        self.btn_pause.setText("暂停")

    def update_state(self, script_name: str, state: str, index: int, total: int) -> None:
        self.title.setText(script_name)
        self.progress.setText(f"{state} · 第 {max(1, index)} / {total} 步")

    def place_top_right(self) -> None:
        screen = QApplication.primaryScreen()
        if not screen:
            return
        geo = screen.availableGeometry()
        self.adjustSize()
        self.move(geo.right() - self.width() - 24, geo.top() + 24)

    # drag anywhere
    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self._drag = event.globalPosition().toPoint() - self.frameGeometry().topLeft()
            event.accept()

    def mouseMoveEvent(self, event):
        if self._drag is not None and event.buttons() & Qt.MouseButton.LeftButton:
            self.move(event.globalPosition().toPoint() - self._drag)
            event.accept()

    def mouseReleaseEvent(self, event):
        self._drag = None
