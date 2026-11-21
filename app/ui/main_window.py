from __future__ import annotations

import sys
from pathlib import Path

from PySide6.QtCore import QFile, Qt, QTimer, Signal
from PySide6.QtGui import QAction, QColor, QFont, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFileDialog,
    QFormLayout,
    QFrame,
    QGroupBox,
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QScrollArea,
    QSpinBox,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from ..core.config import AppConfig, ConfigStore
from ..core.coords import to_percent
from ..core.device_manager import DeviceEntry, DeviceManager
from ..core.engine import Engine, EngineEvent, EngineState
from ..core.errors import OutoclickError
from ..core.ipc import IpcServer
from ..core.logger import RunLogger
from ..core.paths import ensure_dirs, scripts_dir
from ..core.profile import ProfileStore, build_profile, calibrate_point, validate_for_run
from ..core.script_model import (
    Script,
    blank_script,
    demo_script,
    duplicate,
    list_scripts,
    new_step,
    step_summary,
    unique_path,
)
from ..core.transport import BaseTransport
from .canvas_picker import CanvasPicker
from .hotkey import GlobalHotkey
from .hud import Hud


class SettingsDialog(QDialog):
    def __init__(self, parent, config: AppConfig):
        super().__init__(parent)
        self.setWindowTitle("设置")
        self.setMinimumWidth(520)
        layout = QVBoxLayout(self)
        form = QFormLayout()

        row = QHBoxLayout()
        self.adb_edit = QLineEdit(config.adb_path)
        self.adb_edit.setPlaceholderText("留空则自动查找 adb")
        browse = QPushButton("浏览…")
        browse.clicked.connect(self._browse)
        row.addWidget(self.adb_edit, 1)
        row.addWidget(browse)
        holder = QWidget()
        holder.setLayout(row)
        form.addRow("adb 路径", holder)

        self.mock_check = QCheckBox("优先使用模拟设备（无需手机）")
        self.mock_check.setChecked(config.prefer_mock)
        form.addRow("", self.mock_check)

        hint = QLabel(
            "真机需要开启「开发者选项 → USB 调试」；小米/红米还需开启"
            "「USB 调试（安全设置）」，否则 input 注入会被拒绝。"
        )
        hint.setWordWrap(True)
        hint.setObjectName("hint")
        form.addRow("", hint)
        layout.addLayout(form)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def _browse(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "选择 adb.exe", "", "adb.exe (adb.exe);;所有文件 (*.*)")
        if path:
            self.adb_edit.setText(path)

    def apply_to(self, config: AppConfig) -> AppConfig:
        config.adb_path = self.adb_edit.text().strip()
        config.prefer_mock = self.mock_check.isChecked()
        return config


class MainWindow(QMainWindow):
    engine_event = Signal(object)
    ipc_request = Signal(object)

    def __init__(self):
        super().__init__()
        ensure_dirs()
        self.config_store = ConfigStore()
        self.config = self.config_store.load()
        self.manager = DeviceManager(self.config.adb_path)
        self.profiles = ProfileStore()
        self.entries: list[DeviceEntry] = []
        self.transport: BaseTransport | None = None
        self.profile = None
        self.script: Script | None = None
        self.engine: Engine | None = None

        self._current = -1
        self._loading = False
        self._dirty = False
        self._pick_mode = ""
        self._pick_stage = 0
        self._calibrate_armed = False
        self._run_index = 0
        self._run_total = 0
        self._run_token = 0
        self.last_summary: dict | None = None
        self._active_script: Script | None = None

        self.hud = Hud()
        self.hud.hide()

        self.setWindowTitle("outoclick · 安卓自动点击器")
        self.resize(self.config.window_width, self.config.window_height)
        self._build_ui()
        self._build_menu()
        self._connect()
        self._start_ipc()
        self._start_hotkey()
        self.refresh_devices()
        self._restore_last_script()

    # ------------------------------------------------------------------
    # UI construction
    # ------------------------------------------------------------------
    def _build_ui(self) -> None:
        central = QWidget()
        self.setCentralWidget(central)
        root = QVBoxLayout(central)
        root.setContentsMargins(10, 8, 10, 8)
        root.setSpacing(8)

        # top device bar
        bar = QFrame()
        bar.setObjectName("deviceBar")
        bar_l = QHBoxLayout(bar)
        bar_l.setContentsMargins(12, 8, 12, 8)
        bar_l.addWidget(QLabel("设备"))
        self.device_combo = QComboBox()
        self.device_combo.setMinimumWidth(340)
        bar_l.addWidget(self.device_combo)
        self.btn_refresh = QPushButton("刷新")
        self.btn_selftest = QPushButton("自检")
        self.btn_calibrate = QPushButton("校准落点")
        self.btn_shot = QPushButton("截图")
        self.btn_settings = QPushButton("设置")
        for b in (self.btn_refresh, self.btn_selftest, self.btn_calibrate, self.btn_shot, self.btn_settings):
            bar_l.addWidget(b)
        bar_l.addStretch(1)
        self.mode_label = QLabel("")
        self.mode_label.setObjectName("modeLabel")
        bar_l.addWidget(self.mode_label)
        root.addWidget(bar)

        # main splitter
        outer = QSplitter(Qt.Orientation.Vertical)

        top = QSplitter(Qt.Orientation.Horizontal)

        # left: scripts
        left = QWidget()
        left_l = QVBoxLayout(left)
        left_l.setContentsMargins(0, 0, 0, 0)
        left_l.addWidget(self._section("脚本"))
        self.script_list = QListWidget()
        left_l.addWidget(self.script_list, 1)
        row1 = QHBoxLayout()
        self.btn_new = QPushButton("新建")
        self.btn_demo = QPushButton("演示模板")
        row1.addWidget(self.btn_new)
        row1.addWidget(self.btn_demo)
        left_l.addLayout(row1)
        row2 = QHBoxLayout()
        self.btn_open = QPushButton("打开…")
        self.btn_save = QPushButton("保存")
        row2.addWidget(self.btn_open)
        row2.addWidget(self.btn_save)
        left_l.addLayout(row2)

        # center: canvas
        center = QWidget()
        center_l = QVBoxLayout(center)
        center_l.setContentsMargins(0, 0, 0, 0)
        tools = QHBoxLayout()
        self.btn_fit = QPushButton("适配")
        self.btn_actual = QPushButton("1:1")
        self.chk_markers = QCheckBox("显示落点")
        self.chk_markers.setChecked(True)
        self.image_label = QLabel("尚无截图")
        self.image_label.setObjectName("hint")
        tools.addWidget(self.btn_fit)
        tools.addWidget(self.btn_actual)
        tools.addWidget(self.chk_markers)
        tools.addStretch(1)
        tools.addWidget(self.image_label)
        center_l.addLayout(tools)
        self.canvas = CanvasPicker()
        center_l.addWidget(self.canvas, 1)
        hint = QLabel("左键取点/添加步骤 · 滚轮缩放 · 中键或空格+左键拖动")
        hint.setObjectName("hint")
        center_l.addWidget(hint)

        # right: properties
        right = QWidget()
        right_l = QVBoxLayout(right)
        right_l.setContentsMargins(0, 0, 0, 0)
        right_l.addWidget(self._section("步骤属性"))
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        inner = QWidget()
        self.form = QFormLayout(inner)
        self.form.setFieldGrowthPolicy(QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow)

        self.lbl_type = QLabel("—")
        self.lbl_type.setObjectName("typeBadge")
        self.form.addRow("类型", self.lbl_type)
        self.f_label = QLineEdit()
        self.form.addRow("名称", self.f_label)

        self.g_point = QGroupBox("坐标")
        gp = QFormLayout(self.g_point)
        self.f_x = QSpinBox()
        self.f_y = QSpinBox()
        for spin in (self.f_x, self.f_y):
            spin.setRange(0, 20000)
        gp.addRow("X", self.f_x)
        gp.addRow("Y", self.f_y)
        self.form.addRow(self.g_point)

        self.g_swipe = QGroupBox("滑动")
        gs = QFormLayout(self.g_swipe)
        self.f_fx, self.f_fy, self.f_tx, self.f_ty = QSpinBox(), QSpinBox(), QSpinBox(), QSpinBox()
        for spin in (self.f_fx, self.f_fy, self.f_tx, self.f_ty):
            spin.setRange(0, 20000)
        gs.addRow("起点 X", self.f_fx)
        gs.addRow("起点 Y", self.f_fy)
        gs.addRow("终点 X", self.f_tx)
        gs.addRow("终点 Y", self.f_ty)
        self.f_dur = QSpinBox()
        self.f_dur.setRange(1, 60000)
        self.f_dur.setSingleStep(100)
        gs.addRow("时长 ms", self.f_dur)
        self.form.addRow(self.g_swipe)

        self.g_misc = QGroupBox("参数")
        gm = QFormLayout(self.g_misc)
        self.gm_layout = gm
        self.f_ms = QSpinBox()
        self.f_ms.setRange(0, 3600000)
        self.f_ms.setSingleStep(100)
        gm.addRow("等待 ms", self.f_ms)
        self.f_press = QSpinBox()
        self.f_press.setRange(1, 60000)
        self.f_press.setSingleStep(100)
        gm.addRow("长按 ms", self.f_press)
        self.f_key = QLineEdit()
        gm.addRow("按键", self.f_key)
        self.f_text = QLineEdit()
        gm.addRow("文本(ASCII)", self.f_text)
        self.f_pkg = QLineEdit()
        gm.addRow("包名", self.f_pkg)
        self.f_repeat = QSpinBox()
        self.f_repeat.setRange(1, 10000)
        gm.addRow("循环次数", self.f_repeat)
        self.form.addRow(self.g_misc)

        self.g_assert = QGroupBox("像素校验")
        ga = QFormLayout(self.g_assert)
        self.f_color = QLineEdit()
        self.f_color.setPlaceholderText("#38A169")
        ga.addRow("期望颜色", self.f_color)
        self.f_tol = QSpinBox()
        self.f_tol.setRange(0, 255)
        ga.addRow("容差", self.f_tol)
        self.form.addRow(self.g_assert)

        self.g_common = QGroupBox("通用")
        gc = QFormLayout(self.g_common)
        self.f_delay = QSpinBox()
        self.f_delay.setRange(0, 3600000)
        self.f_delay.setSingleStep(50)
        gc.addRow("执行后延时", self.f_delay)
        self.f_jitter = QSpinBox()
        self.f_jitter.setRange(0, 60000)
        self.f_jitter.setSingleStep(10)
        gc.addRow("延时抖动 ±ms", self.f_jitter)
        self.f_offset = QSpinBox()
        self.f_offset.setRange(0, 200)
        gc.addRow("点击偏移 ±px", self.f_offset)
        self.f_on_error = QComboBox()
        self.f_on_error.addItems(["abort", "retry", "continue", "pause"])
        gc.addRow("失败策略", self.f_on_error)
        self.f_enabled = QCheckBox("启用该步骤")
        gc.addRow("", self.f_enabled)
        self.form.addRow(self.g_common)

        self.form_note = QLabel("")
        self.form_note.setWordWrap(True)
        self.form_note.setObjectName("hint")
        self.form.addRow(self.form_note)

        buttons = QHBoxLayout()
        self.btn_apply = QPushButton("应用修改")
        self.btn_pick = QPushButton("取点")
        buttons.addWidget(self.btn_apply)
        buttons.addWidget(self.btn_pick)
        buttons.addStretch(1)
        holder = QWidget()
        holder.setLayout(buttons)
        self.form.addRow(holder)

        scroll.setWidget(inner)
        right_l.addWidget(scroll, 1)

        top.addWidget(left)
        top.addWidget(center)
        top.addWidget(right)
        top.setStretchFactor(0, 0)
        top.setStretchFactor(1, 1)
        top.setStretchFactor(2, 0)
        top.setSizes([220, 760, 320])

        # bottom: timeline + run
        bottom = QWidget()
        bottom_l = QVBoxLayout(bottom)
        bottom_l.setContentsMargins(0, 0, 0, 0)
        bottom_l.addWidget(self._section("步骤时间线"))
        self.table = QTableWidget(0, 6)
        self.table.setHorizontalHeaderLabels(["#", "类型", "内容", "延时", "失败", "启用"])
        self.table.verticalHeader().setVisible(False)
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch)
        self.table.setMinimumHeight(180)
        bottom_l.addWidget(self.table, 1)

        edit_row = QHBoxLayout()
        self.btn_add_tap = QPushButton("+ 点击")
        self.btn_add_wait = QPushButton("+ 等待")
        self.btn_add_key = QPushButton("+ 返回键")
        self.btn_add_assert = QPushButton("+ 像素校验")
        self.btn_up = QPushButton("上移")
        self.btn_down = QPushButton("下移")
        self.btn_dup = QPushButton("复制")
        self.btn_del = QPushButton("删除")
        for b in (
            self.btn_add_tap, self.btn_add_wait, self.btn_add_key, self.btn_add_assert,
            self.btn_up, self.btn_down, self.btn_dup, self.btn_del,
        ):
            edit_row.addWidget(b)
        edit_row.addStretch(1)
        bottom_l.addLayout(edit_row)

        run_row = QHBoxLayout()
        self.btn_run = QPushButton("▶ 运行")
        self.btn_run.setObjectName("primary")
        self.btn_step = QPushButton("单步试跑")
        self.btn_pause = QPushButton("⏸ 暂停")
        self.btn_stop = QPushButton("■ 急停 F12")
        self.btn_stop.setObjectName("danger")
        run_row.addWidget(self.btn_run)
        run_row.addWidget(self.btn_step)
        run_row.addWidget(self.btn_pause)
        run_row.addWidget(self.btn_stop)
        self.progress = QProgressBar()
        self.progress.setRange(0, 1)
        self.progress.setValue(0)
        self.progress.setTextVisible(True)
        run_row.addWidget(self.progress, 1)
        self.state_label = QLabel("Idle")
        self.state_label.setObjectName("stateLabel")
        run_row.addWidget(self.state_label)
        bottom_l.addLayout(run_row)

        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumBlockCount(2000)
        self.log.setMinimumHeight(90)
        self.log.setPlaceholderText("运行日志会显示在这里")
        bottom_l.addWidget(self.log)

        outer.addWidget(top)
        outer.addWidget(bottom)
        outer.setSizes([500, 340])
        root.addWidget(outer, 1)

        self.status_coords = QLabel("")
        self.statusBar().addPermanentWidget(self.status_coords)
        self.statusBar().showMessage("就绪")

        self._sync_form_visibility(None)
        self._update_buttons()

    def _section(self, text: str) -> QLabel:
        label = QLabel(text)
        label.setObjectName("section")
        return label

    def _build_menu(self) -> None:
        menu = self.menuBar()
        file_menu = menu.addMenu("文件")
        self._action(file_menu, "新建脚本", "Ctrl+N", self.new_script)
        self._action(file_menu, "打开…", "Ctrl+O", self.open_script_dialog)
        self._action(file_menu, "保存", "Ctrl+S", self.save_script)
        self._action(file_menu, "另存为…", "Ctrl+Shift+S", self.save_script_as)
        file_menu.addSeparator()
        self._action(file_menu, "退出", "Alt+F4", self.close)

        dev_menu = menu.addMenu("设备")
        self._action(dev_menu, "刷新设备", "F5", self.refresh_devices)
        self._action(dev_menu, "设备自检", "", self.selftest)
        self._action(dev_menu, "校准落点", "", self.arm_calibration)
        self._action(dev_menu, "截图", "F6", self.screenshot)
        self._action(dev_menu, "设置…", "", self.open_settings)

        run_menu = menu.addMenu("运行")
        self._action(run_menu, "运行脚本", "F9", self.start_run)
        self._action(run_menu, "单步试跑", "", self.run_selected_step)
        self._action(run_menu, "暂停/继续", "F10", self.toggle_pause)
        self._action(run_menu, "急停", "F12", self.emergency_stop)

        help_menu = menu.addMenu("帮助")
        self._action(help_menu, "使用说明", "F1", self.show_help)

    def _action(self, menu, text: str, shortcut: str, slot) -> QAction:
        action = QAction(text, self)
        if shortcut:
            action.setShortcut(QKeySequence(shortcut))
        action.triggered.connect(slot)
        menu.addAction(action)
        return action

    def _connect(self) -> None:
        self.btn_refresh.clicked.connect(self.refresh_devices)
        self.device_combo.currentIndexChanged.connect(self._on_device_changed)
        self.btn_selftest.clicked.connect(self.selftest)
        self.btn_calibrate.clicked.connect(self.arm_calibration)
        self.btn_shot.clicked.connect(self.screenshot)
        self.btn_settings.clicked.connect(self.open_settings)

        self.btn_new.clicked.connect(self.new_script)
        self.btn_demo.clicked.connect(self.create_demo)
        self.btn_open.clicked.connect(self.open_script_dialog)
        self.btn_save.clicked.connect(self.save_script)
        self.script_list.itemSelectionChanged.connect(self._on_script_selected)

        self.btn_fit.clicked.connect(self.canvas.fit)
        self.btn_actual.clicked.connect(self.canvas.actual_size)
        self.chk_markers.toggled.connect(self.canvas.show_markers)
        self.canvas.picked.connect(self._on_canvas_pick)
        self.canvas.cursor_moved.connect(
            lambda x, y: self.status_coords.setText(f"x={x}  y={y}")
        )
        self.canvas.image_loaded.connect(
            lambda w, h: self.image_label.setText(f"截图 {w} × {h}")
        )

        self.table.itemSelectionChanged.connect(self._on_step_selected)
        self.btn_apply.clicked.connect(self.apply_step)
        self.btn_pick.clicked.connect(lambda: self._arm_pick("point"))
        self.btn_add_tap.clicked.connect(lambda: self.add_step("tap"))
        self.btn_add_wait.clicked.connect(lambda: self.add_step("wait"))
        self.btn_add_key.clicked.connect(lambda: self.add_step("key"))
        self.btn_add_assert.clicked.connect(lambda: self.add_step("assertPixel"))
        self.btn_up.clicked.connect(lambda: self.move_step(-1))
        self.btn_down.clicked.connect(lambda: self.move_step(1))
        self.btn_dup.clicked.connect(self.duplicate_step)
        self.btn_del.clicked.connect(self.delete_step)

        self.btn_run.clicked.connect(self.start_run)
        self.btn_step.clicked.connect(self.run_selected_step)
        self.btn_pause.clicked.connect(self.toggle_pause)
        self.btn_stop.clicked.connect(self.emergency_stop)
        self.hud.pause_clicked.connect(self.toggle_pause)
        self.hud.stop_clicked.connect(self.emergency_stop)

        self.engine_event.connect(self._on_engine_event)
        self.ipc_request.connect(self._on_ipc_request, Qt.ConnectionType.QueuedConnection)

    def _start_ipc(self) -> None:
        self.ipc = IpcServer(self._handle_ipc)
        try:
            self.ipc.start()
        except OSError:
            QTimer.singleShot(
                0,
                lambda: QMessageBox.warning(
                    self, "已有实例", "检测到另一个 outoclick 正在运行，本窗口将退出。"
                ),
            )
            QTimer.singleShot(50, self.close)

    def _start_hotkey(self) -> None:
        self.hotkey = GlobalHotkey(self.config.hotkey, parent=self)
        self.hotkey.triggered.connect(self.emergency_stop)
        if self.hotkey.registered:
            if self.hotkey.key != self.config.hotkey.upper():
                self.append_log(
                    f"[热键] {self.config.hotkey} 已被其他程序占用，全局急停改用 {self.hotkey.key}"
                )
            else:
                self.append_log(f"[热键] 全局急停键：{self.hotkey.key}")
            self.hud.btn_stop.setText(f"急停 {self.hotkey.key}")
            self.btn_stop.setText(f"■ 急停 {self.hotkey.key}")
        else:
            self.append_log(f"[热键] 全局热键注册失败：{self.hotkey.error}")
            self.append_log("[热键] 窗口获得焦点时菜单里的 F12 仍然可用。")

    # ------------------------------------------------------------------
    # Device / profile
    # ------------------------------------------------------------------
    def refresh_devices(self) -> None:
        self.entries = self.manager.list_devices(include_mock=True)
        self.device_combo.blockSignals(True)
        self.device_combo.clear()
        for entry in self.entries:
            suffix = " · 模拟" if entry.mock else ""
            self.device_combo.addItem(entry.info.label + suffix)
        self.device_combo.blockSignals(False)
        if not self.entries:
            self.transport = None
            self.mode_label.setText("无可用设备")
            return

        target = 0
        for i, entry in enumerate(self.entries):
            if self.config.last_device_serial and entry.info.serial == self.config.last_device_serial:
                target = i
                break
            if self.config.prefer_mock and entry.mock:
                target = i
                break
        else:
            for i, entry in enumerate(self.entries):
                if entry.info.usable and not entry.mock:
                    target = i
                    break
        self.device_combo.setCurrentIndex(target)
        self._on_device_changed()

    def _on_device_changed(self) -> None:
        index = self.device_combo.currentIndex()
        if not (0 <= index < len(self.entries)):
            return
        entry = self.entries[index]
        try:
            self.transport = self.manager.transport_for(entry)
        except Exception as exc:  # noqa: BLE001
            self.transport = None
            self.append_log(f"[设备] 连接失败：{exc}")
            self.mode_label.setText("连接失败")
            return
        self.profile = None
        self.config.last_device_serial = entry.info.serial
        self.mode_label.setText("模拟模式" if entry.mock else "真机模式")
        self._update_buttons()
        self._ensure_profile(quiet=True)
        self.append_log(f"[设备] 已选择 {entry.info.label}")
        self.screenshot()

    def _ensure_profile(self, force: bool = False, quiet: bool = False):
        if not self.transport:
            if not quiet:
                QMessageBox.warning(self, "没有设备", "请先选择设备。")
            return None
        if self.profile is not None and not force:
            return self.profile
        if not force:
            try:
                wm_w, wm_h, _ = self.transport.wm_size()
                for candidate in reversed(self.profiles.load()):
                    if candidate.serial == self.transport.serial and candidate.ref_size == (wm_w, wm_h):
                        self.profile = candidate
                        self.append_log(f"[档案] 复用 {candidate.id} · {wm_w}x{wm_h}")
                        return candidate
            except Exception:  # noqa: BLE001
                pass
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            profile, report = build_profile(self.transport)
            profile.calibrated = self.transport.kind == "mock"
            self.profiles.upsert(profile)
            self.profile = profile
            self.append_log(
                f"[档案] {profile.serial} · {profile.ref_width}x{profile.ref_height} "
                f"· density {profile.density}"
            )
            if not report.ok:
                if not quiet:
                    QMessageBox.warning(self, "档案一致性检查未通过", "\n".join(report.issues))
                else:
                    self.append_log("[档案] " + "；".join(report.issues))
            elif report.warnings:
                self.append_log("[档案] " + "；".join(report.warnings))
            return profile
        except Exception as exc:  # noqa: BLE001
            if not quiet:
                QMessageBox.critical(self, "档案创建失败", str(exc))
            else:
                self.append_log(f"[档案] 创建失败：{exc}")
            return None
        finally:
            QApplication.restoreOverrideCursor()

    def _update_buttons(self) -> None:
        has_device = self.transport is not None
        has_script = self.script is not None
        running = bool(self.engine and self.engine.is_alive)
        for b in (self.btn_shot, self.btn_selftest, self.btn_calibrate):
            b.setEnabled(has_device and not running)
        for b in (self.btn_run, self.btn_step):
            b.setEnabled(has_script and has_device and not running)
        for b in (
            self.btn_add_tap, self.btn_add_wait, self.btn_add_key, self.btn_add_assert,
            self.btn_up, self.btn_down, self.btn_dup, self.btn_del, self.btn_apply, self.btn_pick,
        ):
            b.setEnabled(has_script and not running)
        self.btn_pause.setEnabled(running)
        self.btn_stop.setEnabled(running)

    # ------------------------------------------------------------------
    # Scripts
    # ------------------------------------------------------------------
    def _restore_last_script(self) -> None:
        path = Path(self.config.last_script_path) if self.config.last_script_path else None
        if path and path.exists():
            self._load_script_path(path)
        else:
            items = list_scripts(scripts_dir())
            if items:
                self._load_script_path(items[0])

    def _refresh_script_list(self, select: Path | None = None) -> None:
        self.script_list.blockSignals(True)
        self.script_list.clear()
        target_row = -1
        for i, path in enumerate(list_scripts(scripts_dir())):
            item = QListWidgetItem(path.stem)
            item.setData(Qt.ItemDataRole.UserRole, str(path))
            self.script_list.addItem(item)
            if select and path == select:
                target_row = i
        self.script_list.blockSignals(False)
        if target_row >= 0:
            self.script_list.setCurrentRow(target_row)

    def _on_script_selected(self) -> None:
        item = self.script_list.currentItem()
        if not item:
            return
        path = Path(item.data(Qt.ItemDataRole.UserRole))
        if self.script and self.script.path == path:
            return
        if not self._maybe_save():
            return
        self._load_script_path(path)

    def _load_script_path(self, path: Path) -> None:
        try:
            script = Script.load(path)
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "无法加载脚本", str(exc))
            return
        self.script = script
        self._current = -1
        self._dirty = False
        self.config.last_script_path = str(path)
        self.setWindowTitle(f"outoclick · {script.name}")
        self._refresh_script_list(select=path)
        self._refresh_table()
        self._refresh_markers()
        self._sync_form_visibility(None)
        self._update_buttons()
        self.append_log(f"[脚本] 已加载 {script.name}")
        if script.warnings:
            self.append_log("[脚本] " + "；".join(script.warnings))
        if self.profile and script.device_profile_id and script.device_profile_id != self.profile.id:
            self.append_log("[脚本] 注意：脚本绑定的档案与当前设备档案不同，运行前会校验。")

    def _maybe_save(self) -> bool:
        if not self._dirty or not self.script:
            return True
        answer = QMessageBox.question(
            self,
            "未保存的修改",
            f"脚本「{self.script.name}」有修改，是否保存？",
            QMessageBox.StandardButton.Save
            | QMessageBox.StandardButton.Discard
            | QMessageBox.StandardButton.Cancel,
        )
        if answer == QMessageBox.StandardButton.Cancel:
            return False
        if answer == QMessageBox.StandardButton.Save:
            return self.save_script()
        self._dirty = False
        return True

    def new_script(self) -> None:
        if not self._maybe_save():
            return
        name, ok = QInputDialog.getText(self, "新建脚本", "脚本名称：", text="新建脚本")
        if not ok or not name.strip():
            return
        profile = self._ensure_profile() if self.transport else None
        script = blank_script(profile, name.strip())
        path = unique_path(scripts_dir(), script.name)
        script.save(path)
        self.script = script
        self._dirty = False
        self._current = -1
        self.config.last_script_path = str(path)
        self._refresh_script_list(select=path)
        self._refresh_table()
        self._refresh_markers()
        self._update_buttons()
        self.append_log(f"[脚本] 已新建 {name}")

    def create_demo(self) -> None:
        if not self._maybe_save():
            return
        profile = self._ensure_profile() if self.transport else None
        script = demo_script(profile)
        path = unique_path(scripts_dir(), script.name)
        script.save(path)
        self.script = script
        self._dirty = False
        self._current = -1
        self.config.last_script_path = str(path)
        self._refresh_script_list(select=path)
        self._refresh_table()
        self._refresh_markers()
        self._update_buttons()
        self.append_log("[脚本] 已生成演示脚本（坐标为 1080×2400 模拟界面）")

    def open_script_dialog(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self, "打开脚本", str(scripts_dir()), "outoclick 脚本 (*.json);;所有文件 (*.*)"
        )
        if not path:
            return
        if not self._maybe_save():
            return
        self._load_script_path(Path(path))

    def save_script(self) -> bool:
        if not self.script:
            return False
        target = self.script.path or unique_path(scripts_dir(), self.script.name)
        try:
            self.script.save(target)
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "保存失败", str(exc))
            return False
        self._dirty = False
        self.config.last_script_path = str(target)
        self._refresh_script_list(select=target)
        self.append_log(f"[脚本] 已保存 {target.name}")
        self.statusBar().showMessage(f"已保存 {target}", 4000)
        return True

    def save_script_as(self) -> bool:
        if not self.script:
            return False
        path, _ = QFileDialog.getSaveFileName(
            self, "另存为", str(scripts_dir() / f"{self.script.name}.json"), "outoclick 脚本 (*.json)"
        )
        if not path:
            return False
        self.script.save(Path(path))
        self._dirty = False
        self._refresh_script_list(select=Path(path))
        self.append_log(f"[脚本] 已另存为 {path}")
        return True

    # ------------------------------------------------------------------
    # Steps
    # ------------------------------------------------------------------
    def _refresh_table(self) -> None:
        self._loading = True
        self.table.setRowCount(0)
        if not self.script:
            self._loading = False
            return
        for i, step in enumerate(self.script.steps):
            self.table.insertRow(i)
            values = [
                str(i + 1),
                str(step.get("type", "")),
                step_summary(step),
                str(step.get("delayAfterMs", self.script.defaults.get("stepDelayMs", ""))),
                str(step.get("onError", self.script.defaults.get("onError", ""))),
                "✓" if step.get("enabled", True) else "",
            ]
            for col, value in enumerate(values):
                item = QTableWidgetItem(value)
                if col == 0:
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                if col == 2 and step.get("type") == "note":
                    item.setForeground(QColor("#8B98A9"))
                self.table.setItem(i, col, item)
        self.table.setRowCount(len(self.script.steps))
        if 0 <= self._current < len(self.script.steps):
            self.table.selectRow(self._current)
        self._loading = False

    def _on_step_selected(self) -> None:
        if self._loading:
            return
        rows = self.table.selectionModel().selectedRows()
        if not rows or not self.script:
            return
        index = rows[0].row()
        if index == self._current:
            return
        self._current = index
        self._load_step_form()
        self._refresh_markers()

    def _load_step_form(self) -> None:
        if not self.script or not (0 <= self._current < len(self.script.steps)):
            self._sync_form_visibility(None)
            return
        step = self.script.steps[self._current]
        stype = str(step.get("type", ""))
        self._loading = True
        self.lbl_type.setText(stype)
        self.f_label.setText(str(step.get("label", "")))
        point = step.get("point") or {}
        self.f_x.setValue(int(point.get("x", 0) or 0))
        self.f_y.setValue(int(point.get("y", 0) or 0))
        frm, to = step.get("from") or {}, step.get("to") or {}
        self.f_fx.setValue(int(frm.get("x", 0) or 0))
        self.f_fy.setValue(int(frm.get("y", 0) or 0))
        self.f_tx.setValue(int(to.get("x", 0) or 0))
        self.f_ty.setValue(int(to.get("y", 0) or 0))
        self.f_dur.setValue(int(step.get("durationMs", 300) or 300))
        self.f_press.setValue(int(step.get("durationMs", 800) or 800))
        self.f_ms.setValue(int(step.get("ms", 1000) or 1000))
        self.f_key.setText(str(step.get("keycode", "")))
        self.f_text.setText(str(step.get("content", "")) if stype == "text" else "")
        self.f_pkg.setText(str(step.get("pkg", "")))
        self.f_repeat.setValue(int(step.get("repeat", 1) or 1))
        self.f_color.setText(str(step.get("color", "")))
        self.f_tol.setValue(int(step.get("tolerance", 10) or 10))
        self.f_delay.setValue(int(step.get("delayAfterMs", self.script.defaults.get("stepDelayMs", 300))))
        self.f_jitter.setValue(int(step.get("jitterMs", self.script.defaults.get("jitterMs", 0))))
        self.f_offset.setValue(int(step.get("offsetJitterPx", self.script.defaults.get("offsetJitterPx", 0))))
        self.f_on_error.setCurrentText(str(step.get("onError", self.script.defaults.get("onError", "abort"))))
        self.f_enabled.setChecked(bool(step.get("enabled", True)))
        self._sync_form_visibility(stype)
        self._loading = False

    def _sync_form_visibility(self, stype: str | None) -> None:
        stype = stype or ""
        if not stype:
            self.form_note.setText("提示：在画布上单击即可新增一个点击步骤；选中步骤后在这里编辑参数。")
        misc_types = ("wait", "longPress", "key", "text", "launchApp", "stopApp", "group")
        self.g_point.setVisible(stype in ("tap", "longPress", "assertPixel"))
        self.g_swipe.setVisible(stype == "swipe")
        self.g_misc.setVisible(stype in misc_types)
        self.g_assert.setVisible(stype == "assertPixel")
        self.g_common.setVisible(bool(stype))

        def show(layout, field, condition: bool) -> None:
            field.setVisible(condition)
            label = layout.labelForField(field)
            if label is not None:
                label.setVisible(condition)

        show(self.gm_layout, self.f_ms, stype == "wait")
        show(self.gm_layout, self.f_press, stype == "longPress")
        show(self.gm_layout, self.f_key, stype == "key")
        show(self.gm_layout, self.f_text, stype == "text")
        show(self.gm_layout, self.f_pkg, stype in ("launchApp", "stopApp"))
        show(self.gm_layout, self.f_repeat, stype == "group")
        self.btn_pick.setEnabled(stype in ("tap", "longPress", "assertPixel", "swipe"))
        self.btn_apply.setEnabled(bool(stype))
        if stype == "group":
            self.form_note.setText("分组的子步骤在 MVP 中通过 JSON 编辑；时间线里分组算一行。")
        elif stype in ("note", "screenshot"):
            self.form_note.setText("该类型没有坐标参数。")
        elif self.script and self.script.coord_mode == "percent":
            self.form_note.setText("百分比坐标脚本：请直接编辑 JSON（MVP 界面仅支持绝对值编辑）。")
        elif not stype:
            self.form_note.setText("提示：在画布上单击即可新增一个点击步骤；选中步骤后在这里编辑参数。")
        else:
            self.form_note.setText("")

    def apply_step(self) -> None:
        if not self.script or not (0 <= self._current < len(self.script.steps)):
            return
        step = self.script.steps[self._current]
        stype = str(step.get("type", ""))
        step["label"] = self.f_label.text().strip() or stype
        if stype in ("tap", "longPress", "assertPixel"):
            step["point"] = {"x": self.f_x.value(), "y": self.f_y.value()}
        if stype == "swipe":
            step["from"] = {"x": self.f_fx.value(), "y": self.f_fy.value()}
            step["to"] = {"x": self.f_tx.value(), "y": self.f_ty.value()}
            step["durationMs"] = self.f_dur.value()
        if stype == "longPress":
            step["durationMs"] = self.f_press.value() or 800
        if stype == "wait":
            step["ms"] = self.f_ms.value()
        if stype == "key":
            step["keycode"] = self.f_key.text().strip()
        if stype == "text":
            step["content"] = self.f_text.text()
        if stype in ("launchApp", "stopApp"):
            step["pkg"] = self.f_pkg.text().strip()
        if stype == "group":
            step["repeat"] = self.f_repeat.value()
        if stype == "assertPixel":
            step["color"] = self.f_color.text().strip()
            step["tolerance"] = self.f_tol.value()
        step["delayAfterMs"] = self.f_delay.value()
        step["jitterMs"] = self.f_jitter.value()
        step["offsetJitterPx"] = self.f_offset.value()
        step["onError"] = self.f_on_error.currentText()
        step["enabled"] = self.f_enabled.isChecked()
        self._dirty = True
        self._refresh_table()
        self._refresh_markers()
        self.append_log(f"[脚本] 更新步骤 {self._current + 1}")

    def add_step(self, stype: str) -> None:
        if not self.script:
            return
        if stype == "wait":
            step = new_step("wait", ms=1000)
        elif stype == "key":
            step = new_step("key", keycode="KEYCODE_BACK")
        elif stype == "assertPixel":
            x = self.f_x.value() if self.canvas.image_size[0] else 0
            y = self.f_y.value() if self.canvas.image_size[1] else 0
            step = new_step("assertPixel", x, y, color="#38A169", tolerance=10)
        else:
            size = self.canvas.image_size
            step = new_step("tap", size[0] // 2, size[1] // 2)
        self.script.steps.append(step)
        self._current = len(self.script.steps) - 1
        self._dirty = True
        self._refresh_table()
        self._load_step_form()
        self._refresh_markers()
        self.table.selectRow(self._current)
        self.append_log(f"[脚本] 新增 {stype}")

    def move_step(self, delta: int) -> None:
        if not self.script or not (0 <= self._current < len(self.script.steps)):
            return
        target = self._current + delta
        if not (0 <= target < len(self.script.steps)):
            return
        steps = self.script.steps
        steps[self._current], steps[target] = steps[target], steps[self._current]
        self._current = target
        self._dirty = True
        self._refresh_table()
        self._refresh_markers()
        self.table.selectRow(target)

    def duplicate_step(self) -> None:
        if not self.script or not (0 <= self._current < len(self.script.steps)):
            return
        import copy
        import uuid

        step = copy.deepcopy(self.script.steps[self._current])
        step["id"] = f"s_{uuid.uuid4().hex[:6]}"
        self.script.steps.insert(self._current + 1, step)
        self._current += 1
        self._dirty = True
        self._refresh_table()
        self._refresh_markers()
        self.table.selectRow(self._current)

    def delete_step(self) -> None:
        if not self.script or not (0 <= self._current < len(self.script.steps)):
            return
        step = self.script.steps[self._current]
        if QMessageBox.question(
            self, "删除步骤", f"删除步骤 {self._current + 1}（{step.get('type')}）？"
        ) != QMessageBox.StandardButton.Yes:
            return
        del self.script.steps[self._current]
        self._current = min(self._current, len(self.script.steps) - 1)
        self._dirty = True
        self._refresh_table()
        self._refresh_markers()
        if self.script.steps:
            self.table.selectRow(self._current)
        else:
            self._sync_form_visibility(None)

    # ------------------------------------------------------------------
    # Canvas
    # ------------------------------------------------------------------
    def screenshot(self) -> None:
        if not self.transport:
            return
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            data = self.transport.screencap()
            if not self.canvas.set_image(data):
                raise RuntimeError("截图解码失败")
            self.append_log(f"[截图] {self.canvas.image_size[0]}×{self.canvas.image_size[1]}")
            self._refresh_markers()
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "截图失败", str(exc))
        finally:
            QApplication.restoreOverrideCursor()

    def _on_canvas_pick(self, x: int, y: int) -> None:
        if self._calibrate_armed:
            self._calibrate_armed = False
            self._run_calibration(x, y)
            return
        if self._pick_mode:
            self._apply_pick(x, y)
            return
        if not self.script:
            self.append_log("[提示] 先新建或打开一个脚本")
            return
        if self.script.coord_mode == "percent":
            self.append_log("[提示] 百分比脚本请在 JSON 中编辑（MVP 界面不支持画布新增）")
            return
        point = self._image_to_script_point(x, y)
        step = new_step("tap", point[0], point[1], label="点击")
        self.script.steps.append(step)
        self._current = len(self.script.steps) - 1
        self._dirty = True
        self._refresh_table()
        self._load_step_form()
        self._refresh_markers()
        self.table.selectRow(self._current)
        self.append_log(f"[脚本] 画布拾取 ({point[0]}, {point[1]}) 并新增点击")

    def _arm_pick(self, kind: str) -> None:
        if not self.script or not (0 <= self._current < len(self.script.steps)):
            return
        step = self.script.steps[self._current]
        stype = step.get("type")
        if stype == "swipe":
            self._pick_mode = "swipe"
            self._pick_stage = 0
            self.statusBar().showMessage("请在画布上点击滑动起点")
        elif stype in ("tap", "longPress", "assertPixel"):
            self._pick_mode = "point"
            self.statusBar().showMessage("请在画布上点击目标位置")
        else:
            return
        self.canvas.setCursor(Qt.CursorShape.CrossCursor)

    def _apply_pick(self, x: int, y: int) -> None:
        if not self.script or not (0 <= self._current < len(self.script.steps)):
            self._pick_mode = ""
            return
        step = self.script.steps[self._current]
        point = self._image_to_script_point(x, y)
        if self._pick_mode == "point":
            step["point"] = {"x": point[0], "y": point[1]}
            self._pick_mode = ""
        elif self._pick_mode == "swipe":
            if self._pick_stage == 0:
                step["from"] = {"x": point[0], "y": point[1]}
                self._pick_stage = 1
                self.statusBar().showMessage("起点已记录，请点击终点")
                return
            step["to"] = {"x": point[0], "y": point[1]}
            self._pick_mode = ""
        self.canvas.unsetCursor()
        self._dirty = True
        self._load_step_form()
        self._refresh_table()
        self._refresh_markers()
        self.append_log(f"[脚本] 取点 ({point[0]}, {point[1]})")

    def _image_to_script_point(self, x: int, y: int):
        iw, ih = self.canvas.image_size
        if iw <= 0 or ih <= 0:
            return x, y
        if self.script and self.script.coord_mode == "percent":
            rx, ry = to_percent(x, y, iw, ih)
            return rx, ry
        if self.profile:
            return (
                round(x * self.profile.ref_width / iw),
                round(y * self.profile.ref_height / ih),
            )
        return x, y

    def _script_to_image_point(self, point: dict) -> tuple[int, int] | None:
        iw, ih = self.canvas.image_size
        if iw <= 0 or ih <= 0 or not isinstance(point, dict):
            return None
        if self.script and self.script.coord_mode == "percent":
            return round(float(point.get("x", 0)) * iw), round(float(point.get("y", 0)) * ih)
        if self.profile and self.profile.ref_width:
            return (
                round(int(point.get("x", 0)) * iw / self.profile.ref_width),
                round(int(point.get("y", 0)) * ih / self.profile.ref_height),
            )
        return int(point.get("x", 0)), int(point.get("y", 0))

    def _refresh_markers(self) -> None:
        if not self.script or not self.canvas.image_size[0]:
            self.canvas.set_steps([], show=self.chk_markers.isChecked())
            return
        markers: list[tuple[str, int, int, str]] = []
        active = -1

        def add(label: str, point: dict | None, kind: str, top_index: int) -> None:
            nonlocal active
            p = self._script_to_image_point(point or {})
            if not p:
                return
            markers.append((label, p[0], p[1], kind))
            if top_index == self._current:
                active = len(markers) - 1

        def walk(steps, prefix: str = "", parent_index: int = -1) -> None:
            for i, step in enumerate(steps):
                top_index = parent_index if parent_index >= 0 else i
                stype = step.get("type")
                if stype == "group":
                    walk(step.get("steps") or [], prefix + f"[{step.get('label', '组')}] ", top_index)
                    continue
                label = prefix + str(step.get("label") or stype)
                if stype in ("tap", "longPress", "assertPixel"):
                    add(label, step.get("point"), str(stype), top_index)
                elif stype == "swipe":
                    add(label + " →", step.get("from"), str(stype), top_index)

        walk(self.script.steps)
        self.canvas.set_steps(markers, show=self.chk_markers.isChecked(), active=active)

    # ------------------------------------------------------------------
    # Device actions
    # ------------------------------------------------------------------
    def selftest(self) -> None:
        if not self.transport:
            return
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            info = self.transport.device_info()
            cap = self.transport.injection_check()
            wm = self.transport.wm_size()
            shot = self.transport.screencap()
            from ..core.imageops import png_size

            sw, sh = png_size(shot)
            lines = [
                f"设备：{info.model}  Android {info.android}",
                f"serial：{info.serial}",
                f"注入能力：{'OK' if cap.ok else 'FAIL'} — {cap.detail}",
                f"SDK：{cap.sdk}",
                f"唤醒：{self.transport.awake()}   锁屏：{self.transport.locked()}",
                f"wm size：{wm[0]}×{wm[1]}   density：{self.transport.wm_density()}",
                f"截图：{sw}×{sh}   旋转：{self.transport.rotation()}",
                f"三向一致：{'OK' if (wm[0], wm[1]) == (sw, sh) else 'FAIL（禁止运行）'}",
            ]
            QMessageBox.information(self, "设备自检", "\n".join(lines))
            self.append_log("[自检] " + ("通过" if cap.ok else "失败"))
        except Exception as exc:  # noqa: BLE001
            QMessageBox.critical(self, "自检失败", str(exc))
        finally:
            QApplication.restoreOverrideCursor()

    def arm_calibration(self) -> None:
        if not self.transport:
            return
        if not self.canvas.image_size[0]:
            self.screenshot()
        QMessageBox.information(
            self,
            "校准落点",
            "请在画布上点击一个「点击后会明显变化」的可逆开关（例如设置里的开关）。\n\n"
            "程序会：先截取该区域 → 点击 → 再截图 → 比较像素变化。\n"
            "变化明显才说明坐标链路有效。",
        )
        self._calibrate_armed = True
        self.canvas.setCursor(Qt.CursorShape.CrossCursor)
        self.statusBar().showMessage("校准模式：请点击一个可逆开关")

    def _run_calibration(self, x: int, y: int) -> None:
        self.canvas.unsetCursor()
        profile = self._ensure_profile()
        if not profile or not self.transport:
            return
        px, py = int(x), int(y)
        QApplication.setOverrideCursor(Qt.CursorShape.WaitCursor)
        try:
            result = calibrate_point(self.transport, px, py)
        finally:
            QApplication.restoreOverrideCursor()
        if result.ok:
            profile.calibrated = True
            self.profiles.upsert(profile)
            QMessageBox.information(self, "校准成功", result.detail)
            self.append_log(f"[校准] 通过 · {result.detail}")
        else:
            QMessageBox.warning(self, "校准未通过", result.detail)
            self.append_log(f"[校准] 未通过 · {result.detail}")

    def open_settings(self) -> None:
        dialog = SettingsDialog(self, self.config)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.config = dialog.apply_to(self.config)
            self.config_store.save(self.config)
            self.manager = DeviceManager(self.config.adb_path)
            self.append_log("[设置] 已保存")
            self.refresh_devices()

    # ------------------------------------------------------------------
    # Run
    # ------------------------------------------------------------------
    def _validate_before_run(self, script: Script) -> bool:
        profile = self._ensure_profile()
        if not profile:
            return False
        issues = script.validate(profile, for_run=True)
        errors = [str(i) for i in issues if i.level == "error"]
        warnings = [str(i) for i in issues if i.level == "warning"]
        if errors:
            QMessageBox.critical(self, "脚本校验失败", "\n".join(errors[:12]))
            return False
        if warnings:
            answer = QMessageBox.question(
                self, "运行提示", "\n".join(warnings[:10]) + "\n\n仍然运行？"
            )
            if answer != QMessageBox.StandardButton.Yes:
                return False
        return True

    def start_run(self) -> bool:
        if not self.script:
            return False
        if self.engine and self.engine.is_alive:
            QMessageBox.warning(self, "正在运行", "已有运行中的任务。")
            return False
        if not self.save_script():
            return False
        return self._start_engine(self.script)

    def run_selected_step(self) -> bool:
        if not self.script or not (0 <= self._current < len(self.script.steps)):
            QMessageBox.information(self, "单步试跑", "请先在时间线选择一个步骤。")
            return False
        if self.engine and self.engine.is_alive:
            return False
        step = self.script.steps[self._current]
        single = Script(
            name=f"单步：{step.get('label') or step.get('type')}",
            steps=[step],
            device_profile_id=self.script.device_profile_id,
            coord_mode=self.script.coord_mode,
            defaults=dict(self.script.defaults),
        )
        return self._start_engine(single, persist=False)

    def _start_engine(self, script: Script, persist: bool = True) -> bool:
        if not self.transport:
            return False
        if not self._validate_before_run(script):
            return False
        self._active_script = script
        logger = RunLogger(script.name, script.id)
        self.engine = Engine(
            self.transport,
            script,
            self.profile,
            logger=logger,
            on_event=lambda event: self.engine_event.emit(event),
        )
        self._run_total = max(1, script.expanded_count())
        self._run_index = 0
        self.progress.setRange(0, self._run_total)
        self.progress.setValue(0)
        self._run_token += 1
        self.log.clear()
        self.append_log(f"[运行] {script.name} · {self._run_total} 步 · {logger.dir}")
        self.hud.reset_pause()
        self.hud.update_state(script.name, EngineState.RUNNING.value, 0, self._run_total)
        self.hud.place_top_right()
        self.hud.show()
        self._set_running(True)
        self.engine.start()
        return True

    def _set_running(self, running: bool) -> None:
        self.btn_run.setEnabled(not running)
        self.btn_step.setEnabled(not running)
        self.btn_pause.setEnabled(running)
        self.btn_stop.setEnabled(running)
        self.device_combo.setEnabled(not running)
        self.btn_refresh.setEnabled(not running)
        for b in (
            self.btn_add_tap, self.btn_add_wait, self.btn_add_key, self.btn_add_assert,
            self.btn_up, self.btn_down, self.btn_dup, self.btn_del, self.btn_apply, self.btn_pick,
            self.btn_shot, self.btn_selftest, self.btn_calibrate,
        ):
            b.setEnabled(not running)

    def toggle_pause(self) -> None:
        if not self.engine:
            return
        if self.engine.state is EngineState.PAUSED:
            self.engine.resume()
        elif self.engine.state is EngineState.RUNNING:
            self.engine.pause()

    def emergency_stop(self) -> None:
        if self.engine and self.engine.is_alive:
            self.engine.stop()
            self.append_log("[运行] 急停")
            self.state_label.setText("Stopping")

    def _on_engine_event(self, event: EngineEvent) -> None:
        payload = event.payload
        if event.kind == "state":
            state = str(payload.get("state", ""))
            self.state_label.setText(state)
            if self._active_script:
                self.hud.update_state(
                    self._active_script.name, state, max(1, self._run_index), self._run_total
                )
        elif event.kind == "step_start":
            self._run_index = int(payload.get("index", 0)) + 1
            total = int(payload.get("total", self._run_total) or self._run_total)
            self._run_total = total
            self.progress.setRange(0, total)
            self.progress.setValue(self._run_index - 1)
            self.state_label.setText(f"{self._run_index}/{total}")
            if self._active_script:
                self.hud.update_state(
                    self._active_script.name, "Running", self._run_index, total
                )
        elif event.kind == "step_end":
            status = payload.get("status")
            mark = {"ok": "OK", "fail": "FAIL", "skip": "SKIP"}.get(status, "?")
            detail = payload.get("error") or payload.get("detail") or ""
            self.append_log(
                f"[{mark:4}] {payload.get('stepId')} {payload.get('type')} "
                f"{payload.get('latencyMs')}ms {detail}"[:300]
            )
            self.progress.setValue(int(payload.get("index", 0)) + 1)
        elif event.kind == "log":
            self.append_log(f"[{payload.get('level', 'INFO')}] {payload.get('message')}")
        elif event.kind == "run_end":
            summary = payload.get("summary", {})
            summary["token"] = self._run_token
            summary["state"] = payload.get("status")
            summary["script"] = self._active_script.name if self._active_script else ""
            self.last_summary = summary
            self.hud.hide()
            self._set_running(False)
            self.progress.setValue(self.progress.maximum())
            self.state_label.setText(payload.get("status", ""))
            self.append_log(
                f"[结束] {payload.get('status')} · 成功 {summary.get('ok')} / "
                f"失败 {summary.get('fail')} / 跳过 {summary.get('skip')} · "
                f"{summary.get('elapsedMs', 0) / 1000:.1f}s · "
                f"校验 {summary.get('assertPass')}/{summary.get('assertTotal')}"
            )
            self.statusBar().showMessage(
                f"运行结束：{payload.get('status')}", 8000
            )

    def append_log(self, text: str) -> None:
        self.log.appendPlainText(text)

    # ------------------------------------------------------------------
    # IPC
    # ------------------------------------------------------------------
    def _handle_ipc(self, command: dict) -> dict:
        cmd = command.get("cmd")
        if cmd == "ping":
            return {"ok": True, "app": "outoclick"}
        if cmd == "status":
            return {
                "ok": True,
                "state": self.engine.state.value if self.engine else "Idle",
                "script": self.script.name if self.script else "",
                "token": self._run_token,
                "last": self.last_summary,
            }
        if cmd == "run":
            if self.engine and self.engine.is_alive:
                return {"ok": False, "error": "busy"}
            self.ipc_request.emit(command)
            return {"ok": True, "accepted": True}
        if cmd == "stop":
            self.ipc_request.emit(command)
            return {"ok": True}
        return {"ok": False, "error": f"unknown command: {cmd}"}

    def _on_ipc_request(self, command: dict) -> None:
        cmd = command.get("cmd")
        if cmd == "run":
            path = command.get("script")
            try:
                if path and Path(path).exists():
                    script = Script.load(Path(path))
                else:
                    script = self.script
                if not script:
                    self.append_log("[IPC] 没有可运行的脚本")
                    return
                if self.script and script.path != self.script.path and not self._maybe_save():
                    return
                if script.path:
                    self._load_script_path(script.path)
                else:
                    self.script = script
                    self._refresh_table()
                if not self.start_run():
                    self._ipc_run_failed("启动失败：脚本校验、保存或设备检查未通过")
            except Exception as exc:  # noqa: BLE001
                self._ipc_run_failed(str(exc))
        elif cmd == "stop":
            self.emergency_stop()

    def _ipc_run_failed(self, message: str) -> None:
        self._run_token += 1
        self.last_summary = {
            "token": self._run_token,
            "state": "error",
            "ok": 0,
            "fail": 0,
            "skip": 0,
            "total": 0,
            "elapsedMs": 0,
            "assertPass": 0,
            "assertTotal": 0,
            "error": message,
            "script": self.script.name if self.script else "",
        }
        self.append_log(f"[IPC] {message}")
        self.state_label.setText("error")

    # ------------------------------------------------------------------
    # Misc
    # ------------------------------------------------------------------
    def show_help(self) -> None:
        QMessageBox.information(
            self,
            "使用说明",
            "1. 手机开启 USB 调试后点「刷新」，选择设备。\n"
            "2. 点「自检」确认注入能力与分辨率一致。\n"
            "3. 点「截图」，在画布上点击目标位置即可生成点击步骤。\n"
            "4. 选中步骤后可用「取点」重新定位，右侧可改延时与失败策略。\n"
            "5. 运行前会做分辨率/旋转/注入权限校验；F12 随时急停。\n\n"
            "没有手机时可直接使用「Mock Phone」模拟设备体验完整流程。\n"
            "命令行：python -m app.cli --run <脚本> [--mock]",
        )

    def closeEvent(self, event):  # noqa: N802
        if self._dirty and not self._maybe_save():
            event.ignore()
            return
        if self.engine and self.engine.is_alive:
            self.engine.stop()
            self.engine.join(2)
        try:
            self.ipc.close()
        except Exception:  # noqa: BLE001
            pass
        try:
            self.hotkey.close()
        except Exception:  # noqa: BLE001
            pass
        self.hud.close()
        self.config.window_width = self.width()
        self.config.window_height = self.height()
        self.config_store.save(self.config)
        super().closeEvent(event)
