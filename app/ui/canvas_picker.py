from __future__ import annotations

from PySide6.QtCore import QPointF, Qt, Signal
from PySide6.QtGui import QBrush, QColor, QFont, QPainter, QPen, QPixmap
from PySide6.QtWidgets import (
    QFrame,
    QGraphicsEllipseItem,
    QGraphicsItem,
    QGraphicsItemGroup,
    QGraphicsPixmapItem,
    QGraphicsScene,
    QGraphicsSimpleTextItem,
    QGraphicsView,
)

from ..core.coords import clamp_pick


class CanvasPicker(QGraphicsView):
    picked = Signal(int, int)
    cursor_moved = Signal(int, int)
    image_loaded = Signal(int, int)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._scene = QGraphicsScene(self)
        self.setScene(self._scene)
        self._pix_item: QGraphicsPixmapItem | None = None
        self._markers = QGraphicsItemGroup()
        self._scene.addItem(self._markers)
        self._image_size = (0, 0)
        self._auto_fit = True
        self._panning = False
        self._pan_start = QPointF()
        self._space = False
        self.setFocusPolicy(Qt.FocusPolicy.StrongFocus)

        self.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        self.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
        self.setDragMode(QGraphicsView.DragMode.NoDrag)
        self.setTransformationAnchor(QGraphicsView.ViewportAnchor.AnchorUnderMouse)
        self.setResizeAnchor(QGraphicsView.ViewportAnchor.AnchorViewCenter)
        self.setBackgroundBrush(QBrush(QColor("#0B0E13")))
        self.setFrameShape(QFrame.Shape.NoFrame)
        self.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.setMinimumSize(360, 320)

        self._cross_x = QGraphicsEllipseItem(-6, -6, 12, 12)
        self._cross_x.setPen(QPen(QColor("#F59E0B"), 0))
        self._cross_x.setBrush(Qt.BrushStyle.NoBrush)
        self._cross_x.setVisible(False)
        self._scene.addItem(self._cross_x)

    # ---- image -------------------------------------------------------
    def set_image(self, data: bytes) -> bool:
        pix = QPixmap()
        if not pix.loadFromData(data):
            return False
        if self._pix_item is not None:
            self._scene.removeItem(self._pix_item)
            self._pix_item = None
        self._pix_item = self._scene.addPixmap(pix)
        self._pix_item.setZValue(-10)
        self._image_size = (pix.width(), pix.height())
        self._scene.setSceneRect(0, 0, pix.width(), pix.height())
        self._markers.setZValue(20)
        self._auto_fit = True
        self.fit()
        self.image_loaded.emit(pix.width(), pix.height())
        return True

    @property
    def image_size(self) -> tuple[int, int]:
        return self._image_size

    def fit(self) -> None:
        if self._pix_item:
            self.fitInView(self._pix_item, Qt.AspectRatioMode.KeepAspectRatio)
            self._auto_fit = True

    def actual_size(self) -> None:
        self.resetTransform()
        self._auto_fit = False

    def zoom(self, factor: float) -> None:
        current = self.transform().m11()
        target = current * factor
        if 0.05 <= target <= 8.0:
            self.scale(factor, factor)
            self._auto_fit = False

    # ---- markers -----------------------------------------------------
    def set_steps(self, steps: list[tuple[str, int, int, str]], show: bool = True, active: int = -1) -> None:
        if self._markers is not None:
            self._scene.removeItem(self._markers)
        self._markers = QGraphicsItemGroup()
        self._markers.setZValue(20)
        self._scene.addItem(self._markers)
        self._markers.setVisible(show)
        if not show:
            return
        for index, (label, x, y, kind) in enumerate(steps):
            color = QColor("#38A169") if kind == "ok" else QColor("#3B82F6")
            if kind == "assertPixel":
                color = QColor("#F59E0B")
            is_active = index == active
            radius = 15 if is_active else 8
            ring = QGraphicsEllipseItem(x - radius, y - radius, radius * 2, radius * 2)
            pen = QPen(color, 2.5 if is_active else 1.4)
            pen.setCosmetic(True)
            ring.setPen(pen)
            if is_active:
                ring.setBrush(QBrush(QColor(color.red(), color.green(), color.blue(), 60)))
            else:
                ring.setBrush(Qt.BrushStyle.NoBrush)
            self._markers.addToGroup(ring)

            badge = QGraphicsSimpleTextItem(str(index + 1))
            badge.setBrush(QBrush(color))
            font = QFont()
            font.setBold(True)
            font.setPointSize(7)
            badge.setFont(font)
            badge.setPos(x + 10, y - 20 - (index % 3) * 11)
            badge.setFlag(QGraphicsItem.GraphicsItemFlag.ItemIgnoresTransformations, True)
            self._markers.addToGroup(badge)

            if is_active and label:
                tip = QGraphicsSimpleTextItem(label[:18])
                tip.setBrush(QBrush(QColor("#E6EDF3")))
                tip_font = QFont()
                tip_font.setPointSize(8)
                tip.setFont(tip_font)
                tip.setPos(x + 20, y + 2)
                tip.setFlag(QGraphicsItem.GraphicsItemFlag.ItemIgnoresTransformations, True)
                self._markers.addToGroup(tip)

    def show_markers(self, visible: bool) -> None:
        self._markers.setVisible(visible)

    # ---- events ------------------------------------------------------
    def resizeEvent(self, event):
        super().resizeEvent(event)
        if self._auto_fit and self._pix_item:
            self.fitInView(self._pix_item, Qt.AspectRatioMode.KeepAspectRatio)

    def wheelEvent(self, event):
        if not self._pix_item:
            return
        self.zoom(1.25 if event.angleDelta().y() > 0 else 0.8)
        event.accept()

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.MiddleButton:
            self._panning = True
            self._pan_start = event.position()
            self.setCursor(Qt.CursorShape.ClosedHandCursor)
            event.accept()
            return
        if event.button() == Qt.MouseButton.LeftButton and not self._space and self._pix_item:
            scene_pos = self.mapToScene(event.position().toPoint())
            x, y = clamp_pick(int(scene_pos.x()), int(scene_pos.y()), *self._image_size)
            self.picked.emit(x, y)
            event.accept()
            return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event):
        if self._panning:
            delta = event.position() - self._pan_start
            self._pan_start = event.position()
            self.horizontalScrollBar().setValue(self.horizontalScrollBar().value() - int(delta.x()))
            self.verticalScrollBar().setValue(self.verticalScrollBar().value() - int(delta.y()))
            event.accept()
            return
        if self._pix_item:
            scene_pos = self.mapToScene(event.position().toPoint())
            x, y = clamp_pick(int(scene_pos.x()), int(scene_pos.y()), *self._image_size)
            self._cross_x.setPos(x, y)
            self._cross_x.setVisible(True)
            self.cursor_moved.emit(x, y)
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.MouseButton.MiddleButton and self._panning:
            self._panning = False
            self.unsetCursor()
            event.accept()
            return
        super().mouseReleaseEvent(event)

    def leaveEvent(self, event):
        self._cross_x.setVisible(False)
        super().leaveEvent(event)

    def keyPressEvent(self, event):
        if event.key() == Qt.Key.Key_Space:
            self._space = True
            self.setCursor(Qt.CursorShape.OpenHandCursor)
            event.accept()
            return
        super().keyPressEvent(event)

    def keyReleaseEvent(self, event):
        if event.key() == Qt.Key.Key_Space:
            self._space = False
            self.unsetCursor()
            event.accept()
            return
        super().keyReleaseEvent(event)
