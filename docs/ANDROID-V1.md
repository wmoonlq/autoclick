# Android v1 技术设计与验证记录

- 日期：2026-11-27
- 版本：outoclick `0.5.1`
- 需求基线：`产品功能示意图/数据实体建模.md`（v4）、`产品功能示意图/测试用例-v1.md`
- 状态：核心闭环已实现并在 Pixel 5 / Android 17 模拟器验证；v1 全量用例待探针 App 回归。
- 0.5.1 修复：运行前等待自己的编辑层/菜单从 WindowManager 移除，避免第一下点击打在自己的 overlay 上。

---

## 1. 总体架构

```
┌──────────────────────────── UI / 交互层 ────────────────────────────┐
│ MainActivity        方案列表 / 配额 / 当前方案 / 权限引导              │
│ AutomationService   前台服务：悬浮球、菜单、编辑层、通知、急停          │
│  ├ FloatingBallView 常驻悬浮球（不可隐藏；运行中单击停止）             │
│  ├ FloatingMenu     开始/暂停/继续/停止/编辑/方案列表/退出             │
│  └ EditorOverlay    工具条 + 全屏透明画布                              │
│      ├ EditorCanvas 编号点位 / 拖动改坐标 / 长按 1s 排序               │
│      ├ PointSettingsPanel 点击次数/间隔/时长/点位间隔                  │
│      └ SavePlanPanel 名称/循环轮次/预估时长                            │
└───────────────────────────────┬───────────────────────────────────────┘
                                │
┌──────────────────────────── 领域层 ──────────────────────────────────┐
│ SessionManager   状态机 / 时间轴执行 / 暂停继续停止 / 看门狗           │
│ Guards           权限 / 前台应用 / 方向 / 尺寸 / 系统页面              │
│ ForegroundTracker 无障碍事件 -> 前台包名                              │
│ TargetAppResolver 包名 -> 应用名/图标                                 │
│ Timeline          click_count / click_gap / press / point_gap          │
│ Jitter            ±5ms / ±3px，运行时 clamp                            │
│ RunSnapshot       会话深拷贝快照                                       │
└───────────────────────────────┬───────────────────────────────────────┘
                                │
┌──────────────────────────── 平台层 ──────────────────────────────────┐
│ ClickAccessibilityService   dispatchGesture + TYPE_WINDOW_STATE_CHANGED│
│ Screen                      DisplayMetrics / rotation / overlay type  │
│ 前台通知 / PendingIntent    方案列表 / 停止 / 删除后重发               │
└───────────────────────────────┬───────────────────────────────────────┘
                                │
┌──────────────────────────── 数据层 ──────────────────────────────────┐
│ AppRepository    SQLite 读写 / 旧数据迁移 / 启动对账                   │
│ DatabaseHelper   apps / buttons / plans / plan_steps / sessions /     │
│                  settings                                             │
└───────────────────────────────────────────────────────────────────────┘
```

单进程、无服务端、无账号、无网络权限。

---

## 2. 模块与文件

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 方案面板、权限引导、配额、当前方案、编辑/运行/删除入口 |
| `AutomationService.kt` | 前台服务、通知、悬浮球、菜单、编辑层生命周期、运行控制 |
| `ClickAccessibilityService.kt` | `dispatchGesture` 注入；前台包名事件；忽略自身 overlay |
| `OutoclickApp.kt` / `AppGraph` | Application 初始化、仓库/会话/解析器单例 |
| `model/Models.kt` | TargetApp / Button / Plan / PlanStep / RunSession / AppState |
| `data/AppRepository.kt` | 方案/按钮/步骤/会话的事务写入、迁移、统计 |
| `data/DatabaseHelper.kt` | SQLite 表结构、CRUD、会话保留 50 条 |
| `data/LegacyMigration.kt` | v0.4 JSON -> Button + PlanStep（TAP/LONG_PRESS） |
| `engine/Timeline.kt` | 时间轴公式、预估总时长、看门狗预期间隔 |
| `engine/Jitter.kt` | 时间/坐标抖动与二次 clamp |
| `engine/Guards.kt` | 启动校验、系统页面/桌面识别、方向/尺寸校验 |
| `engine/ForegroundTracker.kt` | 前台包名缓存 + 监听 |
| `engine/RunSnapshot.kt` | 会话快照（JSON 落库） |
| `engine/SessionManager.kt` | 状态机、执行循环、暂停/继续/停止、看门狗 |
| `ui/FloatingBallView.kt` | 悬浮球绘制与拖动/单击/长按 |
| `ui/FloatingMenu.kt` | 悬浮球菜单（可聚焦窗口） |
| `ui/EditorOverlay.kt` | 工具条、点位复用、删除确认、保存/退出 |
| `ui/EditorCanvas.kt` | 画布绘制、命中测试、拖动、长按排序 |
| `ui/PointSettingsPanel.kt` | 单点参数表单 |
| `ui/SavePlanPanel.kt` | 方案名/循环/预估/长时确认 |
| `ui/OverlayDialog.kt` | Service 内可聚焦模态卡片 |
| `ui/BindPrompt.kt` | 目标应用绑定提示 |

---

## 3. 数据模型

```
TargetApp 1 ── N Button
TargetApp 1 ── N Plan
Plan     1 ── N PlanStep N ── 1 Button
Plan     1 ── 1 JitterProfile（内嵌字段）
Plan     1 ── N RunSession
AppSettings.active_plan_id -> Plan（全局单选）
```

SQLite 表：

| 表 | 关键字段 |
|---|---|
| `apps` | `id, package_name, app_label, bound_rotation, bound_display_w/h` |
| `buttons` | `id, target_app_id, anchor_x/y, width/height, click_count, click_gap_ms, press_ms` |
| `plans` | `id, target_app_id, name, status, loop_count, anti_detect_enabled, jitter_*` |
| `plan_steps` | `id, plan_id, button_id, seq, enabled, point_gap_ms` |
| `sessions` | `id, plan_id, plan_version, target_package, snapshot_json, status, paused_reason, stop_reason, current_step_seq, loop_index, last_progress_at, foreground_package, display_w/h, rotation, error_code, ok/fail/skip, started_at/ended_at` |
| `settings` | `id=1, active_plan_id, schema_version` |

约束（v1）：

- 方案数 ≤ 10（未删除、未归档）。
- 每方案步骤 1..30，`seq` 连续。
- 点击次数 1..50；点击间隔 0..60000；点击时长 10..500；点位间隔 0..60000。
- 循环 1..99。
- 全局最多 1 个非终态会话。
- 同一按钮允许被同一方案多次引用。
- 按钮被删除时，确认后级联移除所有方案中的引用并重排 `seq`。

---

## 4. 状态机

方案状态（只管内容）：

```
DRAFT --保存--> SAVED --编辑保存--> SAVED
SAVED --归档--> ARCHIVED --恢复--> SAVED
DRAFT --丢弃--> 删除
```

会话状态：

```
PENDING --首次下发--> RUNNING
RUNNING --跑满轮次--> FINISHED
RUNNING --用户停止/看门狗超时--> STOPPED
RUNNING --连续注入失败--> FAILED
RUNNING --用户暂停/方向变化/息屏/目标不匹配/权限丢失--> PAUSED
PAUSED --用户确认恢复--> RUNNING
PAUSED --用户停止/看门狗--> STOPPED
PAUSED --权限撤销--> FAILED
```

进程被杀时不会收到回调；下次启动 `AppRepository.reconcileSessionsOnStartup()` 把所有非终态会话标记为 `STOPPED(KILLED)` 并写 `error_code=PROCESS_KILLED`。

---

## 5. 时间轴

```
第 i 次按下        t_i
第 i 次抬起        t_i + press_i
下一次按下         t_{i+1} = t_i + press_i + click_gap
本步结束           t_n + press_n
下一步第一次按下   本步结束 + point_gap
```

```
单步动作 = click_count × press + (click_count − 1) × click_gap
单轮     = Σ(enabled 步骤动作 + point_gap)
总时长   = loop_count × 单轮 − 最后一个 point_gap
```

- `point_gap` 跨轮生效；最后一轮最后一步不执行。
- 抖动：时间类 `base ± jitter` 后 clamp 到合法区间；坐标按热区半宽/半高与屏幕边界双重 clamp。
- 单元测试覆盖 3 点 × 10 轮 = 31250ms、3 点 × 3 轮 = 8675ms、连击动作时长、禁用步骤排除。

---

## 6. 守卫

启动前（`Guards.checkStart`）：

1. 悬浮窗权限。
2. 无障碍服务已连接。
3. 屏幕亮、未锁屏。
4. 方案已保存（`SAVED`）。
5. 目标应用已绑定，且不是系统页面/桌面。
6. 步骤 1..30、至少 1 个启用、引用的按钮存在。
7. 循环 1..99。
8. 当前前台包名 == 目标包名。
9. 当前方向 == 绑定方向；显示尺寸 == 绑定尺寸。
10. 全局没有其他非终态会话。

运行中：

- 前台包名变化：
  - 系统设置 / 安装器 / 权限控制器 / SystemUI → 立即 `STOPPED(TARGET_MISMATCH)`。
  - 桌面 / 其他普通 App / outoclick MainActivity → `PAUSED(TARGET_MISMATCH)`，不自动恢复。
- 方向变化 → `PAUSED(ROTATION)`。
- 息屏 → `PAUSED(SCREEN_OFF)`。
- 恢复前重新校验前台目标、方向、亮屏/解锁。

无障碍事件过滤：`packageName == outoclick` 且 `className` 不是 `MainActivity` 的事件直接忽略，避免悬浮球/菜单/设置弹窗把前台包名污染成自己。

---

## 7. 急停、看门狗、生命周期

急停层次：

1. **常驻悬浮球**：不可隐藏；运行中单击立即停止；长按打开菜单。
2. **通知栏停止**：`ACTION_STOP`；通知被划掉时通过 `deleteIntent` 重新发出。
3. **看门狗**：`elapsedRealtime` + `last_progress_at`；超时 `max(3 × 当前步预期间隔, 30s)` 无进度 → `STOPPED(TIMEOUT)`。
4. 进程被杀：下次启动对账，标记 `KILLED`，不自动复活（`START_NOT_STICKY`）。

停止语义：不再派发下一次点击；已经下发的一次手势（75ms 左右）自然结束，不补点。

---

## 8. UI / 交互

- 风格：Google / ChatGPT 深色；`#0D0D0D` 背景、`#171717` 卡片、白色主按钮、`#10A37F` 绿色状态。
- 主面板：风险提示、新手指南、权限、方案列表、配额、当前方案、悬浮球。
- 编辑层：顶部横向滚动工具条 + 全屏透明画布；工具条可隐藏，悬浮球常驻。
- 画布：
  - 单击点位 → 单点设置。
  - 拖动点位 → 改归一化坐标。
  - 长按 1 秒 → 排序模式，拖到目标序号松手。
  - 重叠点位：序号径向偏移 + 重复点击轮换选中。
- 复用点位：从按钮库选择已有按钮插入当前方案，形成共享引用。
- 应用图标：深色圆角方块 + 白色准星 + 绿色中心点。

---

## 9. 存储与迁移

- SQLite 单库 `outoclick.db`。
- 结构表整体事务重写；运行进度只 UPSERT 单条 session。
- 旧 v0.4 `files/scripts/*.json` 首次启动迁移：
  - `TAP` / `LONG_PRESS` → `Button` + `PlanStep`，坐标按 `baseWidth/baseHeight` 归一化。
  - `SWIPE` / `WAIT` 跳过（旧文件保留，不删）。
  - 迁移标记 `files/v1/legacy_migrated`，只执行一次。

---

## 10. 验证记录

环境：Pixel 5 模拟器，Android 17 / API 37，debug APK。

| 场景 | 结果 |
|---|---|
| 安装启动 / 旧脚本迁移 | 2 个旧方案出现在列表 |
| 创建方案 / 配额 | 3/10 |
| 绑定目标应用（Clock） | `apps` + `plans.target_app_id` 落库 |
| 添加点位 / 单点设置 / 保存 | 按钮、步骤、方案参数落库 |
| 1 步 1 轮运行 | `FINISHED / COMPLETE / ok_count=1` |
| 99 轮运行中单击悬浮球 | `STOPPED / USER / ok_count=2` |
| 运行中按 Home | `PAUSED / TARGET_MISMATCH` |
| 返回目标后继续 | `RUNNING`，未重复已完成点击 |
| 运行中 `am force-stop` | 重启后 `STOPPED / KILLED / PROCESS_KILLED` |
| Clock `+` 按钮实际点击 | 真实打开「Search for a city」，证明注入落到目标 App |
| 单元测试 | `TimelineTest` 6 条、`JitterTest` 3 条通过 |

点击落点证据：`docs/v1-click-result.png`（Clock 搜索页 + 悬浮球）。

### 关于「ok_count 增加但目标没反应」

`dispatchGesture` 的 `onCompleted` 只代表系统接受了手势并完成，不代表落点一定在目标 App。若启动瞬间自己的全屏编辑层/菜单还没移除，第一下会打在自己的 overlay 上。`startRun` 现在会：

1. `menu.dismiss()`；
2. `editor.hide()` / 关闭设置弹窗；
3. 等待 400ms；
4. 再调用 `SessionManager.start()` 派发第一下。

日志（0.5.1）：

```
SessionManager: start display=1080x2340
SessionManager: tap x=542 y=1773 anchor=0.5,0.7566
ClickAccessibility: tap x=542 y=1773 duration=71
ClickAccessibility: gesture completed
```

未回归：旋转、息屏/锁屏、通知被划掉、看门狗超时、系统设置硬停、v1 全量用例。

---

## 11. 待办与风险

1. 做探针 App（记录 `MotionEvent` + 导出 CSV），跑 `测试用例-v1.md`。
2. 补测旋转 / 息屏 / 通知划掉 / 看门狗 / 系统页面硬停。
3. Android 7.1 / 10 / 13 / 14 真机或云手机回归；重点验证 `TYPE_PHONE` 悬浮窗、`dispatchGesture` 时序。
4. 按钮共享在 UI 上只做了「复用点位」，还没有独立按钮库页面；v1.5 再补。
5. release keystore、隐私说明、分发流程。
6. 图像识别 / 控件定位不在 v1 范围。
