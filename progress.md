# outoclick MVP 构建进度

- 开始时间：2026-11-22
- 完成时间：2026-11-22
- 目标：按 SPEC v0.3 的 MVP 范围交付可运行版本
- 环境：Windows 11 / Python 3.13 / PySide6 / Pillow / pytest；adb 未安装（用 MockTransport 验证）

## 步骤

| # | 步骤 | 状态 |
|---|---|---|
| 1 | 环境探测、依赖安装（PySide6 / pytest / Pillow） | 完成 |
| 2 | core：paths / errors / imageops / 坐标换算 | 完成 |
| 3 | core：AdbTransport + MockTransport + 注入自检 | 完成 |
| 4 | core：设备档案 + 三向一致性校验 + 可逆控件校准 | 完成 |
| 5 | core：脚本模型 + 校验 + 模板 | 完成 |
| 6 | core：执行引擎 + 日志 + 单实例回环 socket | 完成 |
| 7 | UI：主窗口 / 画布拾取 / 步骤编辑 / HUD / 热键 | 完成 |
| 8 | CLI：list / selftest / run / demo / status / stop | 完成 |
| 9 | 测试：coords / script / engine / transport / profile | 完成（29 项全绿） |
| 10 | 本地验证 + 文档 + git commit | 完成 |

## 验证结果

- `python -m pytest -q` → 29 passed
- `python -m app.cli --mock --run <demo>` → 6 步全成功，assertPixel 2/2
- GUI 冒烟：模拟设备截图、步骤表、运行、HUD、日志 → 通过
- GUI 常驻时 CLI 投递：`--run` 返回 `ok 6/0`，`--status` 可查上次结果
- 全局热键：本机 F12 被其他程序占用，自动降级 F11 并生效
- 截图：`docs/screenshot.png`

## 已知限制

- 未打包 exe；未接真实设备（无 adb）
- 曲线滑动 / 投屏 / 录制 / 找图 / 百分比可视化编辑不在 MVP
- 计划任务仅 CLI + 系统 schtasks，需「仅在用户登录时运行」

## 关键命令

- 安装：`python -m pip install -r requirements.txt`
- 启动 GUI：`python -m app.main`（无 adb 时自动进入模拟设备模式）
- 无界面运行：`python -m app.cli --mock --run scripts/demo_signin.json`
- 测试：`python -m pytest -q`
