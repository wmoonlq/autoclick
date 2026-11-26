# outoclick 构建进度

- 更新：2026-11-27
- 主交付物：**Android 独立 App**（`android/`）
- 当前版本：**0.6.0（悬浮球交互重构）**
- 需求基线：`产品功能示意图/数据实体建模.md`（v4）+ `产品功能示意图/测试用例-v1.md`
- 仓库：https://github.com/wmoonlq/autoclick

## v0.5（v1 重写）已完成

| 模块 | 内容 | 状态 |
|---|---|---|
| 数据层 | SQLite 六张表：`apps / buttons / plans / plan_steps / sessions / settings`；事务写入；会话保留 50 条 | 完成 |
| 目标应用 | 无障碍事件自动识别 + 一次确认；手动选择；记录方向和分辨率 | 完成 |
| 按钮库 | `Button` 与 `PlanStep` 拆分；复用点位；共享坐标同步 | 完成 |
| 方案编排 | 创建/编辑/删除、10 个配额、草稿/已保存、循环 1–99、预估时长 | 完成 |
| 单点参数 | 点击次数 1–50、点击间隔 0–60000ms、点击时长 10–500ms、点位间隔 0–60000ms | 完成 |
| 运行会话 | RunSession 状态机、快照、暂停/继续/停止、SQLite 落库 | 完成 |
| 目标守卫 | 启动前前台包名/方向/尺寸校验；Home/切 App 暂停；系统页面硬停 | 完成 |
| 悬浮球 | 只在绑定目标应用前台显示；停止单击开始/长按边栏；运行单击暂停/长按停止；暂停单击继续/长按停止 | 完成 |
| 操作边栏 | 长按打开；编辑模式含添加/复用/删除/保存/开始/完成；无独立顶部工具条 | 完成 |
| 通知 | 运行中暂停/停止；暂停时继续/停止 | 完成 |
| 守卫细节 | 输入法窗口不触发隐藏/暂停；主页面/桌面不再残留全屏 overlay | 完成 |
| 随机化 | ±5ms / ±3px 默认开启，无 UI 入口 | 完成 |
| 迁移 | v0.4 JSON 的 TAP/LONG_PRESS 迁移；SWIPE/WAIT 跳过 | 完成 |
| UI | Google / ChatGPT 深色风格；白色主按钮 + 绿色状态；新应用图标 | 完成 |
| 单元测试 | `TimelineTest`、`JitterTest` | 通过 |

## 验证记录（Pixel 5 · Android 17 / API 37）

- 安装启动、旧脚本迁移（2 个方案）
- 创建方案（3/10）、绑定 Clock、添加点位、单点设置、保存
- 1 步 1 轮 → `FINISHED / COMPLETE / ok_count=1`
- Clock `+` 按钮实际点击 → 打开「Search for a city」
- 悬浮球只在 Clock 前台显示；主页面/桌面无 overlay
- 99 轮：单击暂停 → `PAUSED / USER`；单击继续 → `RUNNING`；长按停止 → `STOPPED / USER`
- Gboard 弹出时悬浮球保持可见；停止状态长按打开操作边栏
- 运行中按 Home → `PAUSED / TARGET_MISMATCH`；返回后继续
- 运行中 `am force-stop` → 重启后 `STOPPED / KILLED / PROCESS_KILLED`
- `TimelineTest`、`JitterTest` 全部通过

## 待办

- 配套探针 App（记录 MotionEvent + 导出 CSV），跑完 v1 全量用例
- 补测旋转、息屏/锁屏、通知被划掉、看门狗、系统页面硬停
- Android 7.1 / 10 / 13 / 14 真机或云手机回归
- 独立按钮库页面、导入/导出、滑动点、录方案（v1.5）
- release keystore、隐私说明、分发流程

## 历史

- 2026-11-21：PC + ADB 原型（`app/`、`SPEC.md`），已归档。
- 2026-11-22：v0.2 全屏可视化编排、悬浮球手势、方案切换。
- 2026-11-22：v0.3 iQOO 式贴边悬浮条 + 侧滑抽屉 + GPT 深色 UI。
- 2026-11-23：v0.4 方案管理、回放落点、坐标归一化、随机节奏、画布画滑动。
- 2026-11-27：v0.5 按 v4 实体建模 + v1 用例重写：SQLite、目标应用守卫、按钮库、RunSession、常驻急停、启动对账、ChatGPT 风格 UI 与新图标。
