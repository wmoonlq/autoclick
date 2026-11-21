# 安卓自动点击器 · 产品与技术规格说明书

| 项 | 内容 |
|---|---|
| 文档版本 | v0.3（可行性评审后修订） |
| 日期 | 2026-11-21 |
| 产品代号 | outoclick（暂定） |
| 形态 | Windows 单机桌面工具 → 通过 ADB 驱动一台 Android 设备 |
| 定位方式 | 固定坐标为主（v1 不引入图像识别） |
| 目标用户 | 作者本人（单机、单设备、无账号体系） |
| 状态 | 待 MVP 前置 Spike（附录 C）验证后冻结 v1.0 |
| 评审记录 | `docs/FEASIBILITY-REVIEW.md`；审查 agent：`.opencode/agents/spec-reviewer.md` |

> **v0.3 修订要点**：删除默认批处理（与急停互斥）、纠正 `motionevent` 与 `show_touches` 的错误假设、补充 MIUI 注入门槛与屏幕熄灭静默失效、单实例改为管道投递、计划任务锁定交互会话、`assertPixel` 升级为 P0、越界策略统一、排期改为「MVP 7–9 人日 / v1.0 20–26 人日」。

---

## 1. 背景与目标

### 1.1 背景

日常存在大量「重复、位置固定、无脑但必须点」的安卓操作：游戏日常、App 签到、批量表单、自测回归。人工执行耗时且易漏步、错序。

市面已有成熟方案（按键精灵、触动精灵、点点精灵、Airtest、Total Control 等），但大多需要在手机装 App + 开无障碍/越狱/Root，或是面向团队的重量级框架。本项目只要一条窄路径：**电脑侧编排、纯 JSON 脚本、强校验、可追溯、设备零侵入**。

### 1.2 产品目标

在 Windows 上把「固定坐标点击序列」编排成脚本，对一台 Android 设备稳定复现：

- **一次编排**：截图点选即生成动作，不写代码。
- **稳定复现**：同设备同分辨率重复运行，节奏一致。
- **可中断可追溯**：急停可靠，每次运行有日志与失败现场。
- **零侵入**：手机不装 App、不开无障碍、不 Root，仅开 USB 调试。

### 1.3 成功指标（KPI）

| 编号 | 指标 | 目标值 | 判定方式 |
|---|---|---|---|
| KPI-1 | 编排 10 步脚本耗时 | ≤ 3 分钟 | 含一次试跑，人工计时 |
| KPI-2 | 注入坐标与脚本记录一致 | 100% | 比对日志 `params` 与 `sent` 字段；真实落点另由 `assertPixel` 抽检 |
| KPI-3 | 单动作下发开销 P95 | ≤ 250 ms（USB，Android 12+） | 200 次采样 |
| KPI-4 | 急停响应 | ≤ 300 ms + 最多 1 个在途动作 | 录屏计数 |
| KPI-5 | 8 小时运行指令异常率 | ≤ 1% | 分母 = 下发指令数；同时单列「校验步通过率」 |
| KPI-6 | 异常退出后定位中断步骤 | 100% | 重启提示 |
| KPI-7 | 首次使用到跑通示例 | ≤ 10 分钟 | 干净机 |
| KPI-8 | 注入能力自检覆盖 | 100% 开跑前 | 自检不通过禁止运行 |

### 1.4 非目标

- 不做图像识别 / OCR / 目标检测（v2+）。
- 不做多设备群控、任务分发、云端同步。
- 不做 iOS、不做手机端常驻 App、不做无障碍服务。
- 不做 Root 特权能力（`sendevent` / 底层注入）。
- 不做对抗性绕过（反作弊、模拟器检测、风控对抗）。
- 不做 APK 打包、脚本商城、账号授权、商业化。

> 唯一预留的例外：v2 中文输入可能引入 **ADBKeyBoard 输入法**（用户显式安装的独立 App），届时单列风险与安装步骤，不计入「零侵入」承诺。

---

## 2. 用户与场景

### 2.1 用户画像

单一用户（作者本人），具备基础电脑操作能力，愿意开启 USB 调试，不写代码。

### 2.2 核心场景

| 编号 | 场景 | 描述 | 优先级 |
|---|---|---|---|
| S1 | 每日签到 | 启动 App → 5 个固定入口 → 关弹窗 → 退出，每天 1 次 | P0 |
| S2 | 游戏日常 | 20~60 步固定点按，含长按、滑动、等待，循环 3 轮 | P0 |
| S3 | 批量表单 | 固定位置输入文本、切页、提交，重复 50 次 | P1 |
| S4 | 自测回归 | 固定路径冒烟点击，失败留截图 | P1 |
| S5 | 中途接管 | 暂停后人工操作，再继续 | P1 |
| S6 | 极简连点 | 临时挂机连点，不建脚本 | P2（v1.1） |

### 2.3 关键旅程（S1）

1. 手机开 USB 调试并连接 → 工具列出设备 → **注入自检**通过。
2. 校准档案（三向一致性校验）。
3. 截图 → 点选 5 个入口 → 生成 tap。
4. 插入「等待」「返回键」。
5. 试跑；某步早了 → 改数字。
6. 保存 → 运行 ×1 → 日志全绿。
7. 下次选中运行，或由计划任务在「已登录 + USB 连接」时触发。

---

## 3. 竞品参考与功能取舍

### 3.1 调研对象

| 产品 | 形态 | 公开资料核心能力 | 主要优势 |
|---|---|---|---|
| 按键精灵（手机助手 + 安卓版） | PC IDE + 手机运行端 | MQ 脚本、录制、抓抓取色取坐标、找图找色、OCRex、OpenCV/YOLO、控件、悬浮窗、打包 APK、定时、群控/远程 | 生态最全、识别最强 |
| 触动精灵 | 手机端 Lua | 录制、找图找色、点阵字库、群控、云脚本、授权 | 脚本分发与字库 |
| 点点精灵 | PC + 手机 App | 46 动作、录制、智能找图、离线 OCR、找色、控件、贝塞尔滑动、百分比坐标、定时、悬浮窗、模板、分享、日志浮层、极简连点 | 免 Root 体验完整 |
| Airtest / Poco | Python IDE | 图像识别录制、UI 控件、报告、多设备 | 测试报告与跨端 |
| Total Control | PC 群控 | 多设备镜像、同步、FindNode、脚本/API、剪贴板、文件 | 低延迟、大规模 |
| scrcpy / QtScrcpy | PC 投屏 | 低延迟镜像、键鼠控制、截图录屏、多设备、快捷键映射、息屏镜像 | 开源事实标准 |
| uiautomator2 | Python 库 | 元素/XPath、watcher、快速输入、App 管理 | 控件层自动化 |
| 手机连点器（GC/迅指/YoClaw） | 手机 App | 悬浮球、单点/多点、随机间隔、区域随机、导入导出、定时 | 极简即用 |

### 3.2 功能对照与决策

| 能力 | 竞品普遍性 | 本项目 | 决策与理由 |
|---|---|---|---|
| 截图取坐标 | 全都有 | ✅ P0 | 核心交互 |
| 录制回放 | 全都有 | ⚠️ v1.2（能力探测） | `getevent` 受限且坐标需归一化，见 §3.3 |
| 实时投屏 | 主流有 | ✅ v1.1（外挂 scrcpy，`--no-control`） | 不自研解码；仅显示，不做控制通道 |
| 悬浮窗控制 | 手机端都有 | ✅ P0（PC 置顶 HUD + 托盘 + 热键） | 设备端悬浮窗不可用（不装 App） |
| 极简连点 | 连点器都有 | ✅ v1.1 | 成本低，但受 `input` 节拍限制，见 §8.9 |
| 定时执行 | 手机端都有 | ✅ P1（计划任务 + CLI） | 必须锁定「用户已登录」会话 |
| 脚本模板 | 点点精灵有 | ✅ v1（1 个）+ v1.1（4 个） | 先降低首次门槛，不堆数量 |
| 导入导出 | 都有 | ✅ P0 | 纯 JSON 自包含（v1 无图片） |
| 随机间隔/偏移 | 高端有 | ✅ P1 | 降机械感，成本低 |
| 贝塞尔滑动 | 点点精灵有 | ❌ v2 实验 | `motionevent` 仅 Android 10+ 且手势流跨进程不连续，见 §8.3 |
| 百分比坐标 | 点点精灵有 | ✅ P1（脚本级） | 跨分辨率更稳 |
| 等待屏幕稳定 | 点点精灵有 | ⚠️ v1.2 | 截图 200–600ms，判据需先定义 |
| 找色点击 | 常见 | ⚠️ v1.2 | 成本低于找图，收益明确 |
| 找图/特征匹配 | 主流有 | ❌ v2 | OpenCV 依赖与素材管理 |
| OCR / YOLO | 按键精灵有 | ❌ v3+ | 依赖重，偏离定位 |
| 控件定位 | 按键精灵/u2/Airtest | ❌ v2 | `uiautomator dump` 可做 |
| 多指/捏合 | 手机端有 | ❌ v1/v2 | `input` 不支持；免 Root 可经 scrcpy 控制通道实现，但属于 v3 |
| 批处理（一条 shell 多动作） | — | ❌ v1 | **破坏急停与逐步日志**，见 §11.2 |
| 多设备群控 | Total Control | ❌ 不做 | 架构预留 |
| APK 打包/云脚本/授权 | 商业产品 | ❌ 不做 | 无商业目标 |
| 变量/条件/表达式 | 高端有 | ⚠️ v1.2 简化 | v1 用 `assertPixel` + 失败策略 |

### 3.3 录制回放的可行性取舍

| 方案 | 原理 | 现实约束 | 结论 |
|---|---|---|---|
| A. `getevent` 采样 | 读 `/dev/input/event*`，解析 ABS_MT 与时间戳 | ① shell 通常可读（属 input 组），但并非所有 ROM 都放行；② **触控面板原始值 ≠ 显示像素**，必须用 `getevent -p` 的 min/max 归一化；③ 看不到注入事件 | v1.2 首选，启动探测，不可用则禁用 |
| B. PC 投屏窗口点击录制 | 全局鼠标钩子 + scrcpy 内容矩形映射 | letterbox、标题栏、DPI 三处误差；且 scrcpy 默认会自己注入，需 `--no-control` | 不采用 |
| C. 手动「记一步」 | 点设备后按热键截屏再拾取 | 体验一般，100% 可用 | v1 兜底（截图拾取已覆盖） |

### 3.4 差异化定位

1. **零侵入**：不装 App、不开无障碍、不 Root。
2. **可审计**：纯文本 JSON 可 diff；JSONL 日志 + 失败截图。竞品多为加密脚本包。
3. **强校验**：分辨率/旋转/宽高比/注入权限任一不符 → **拒绝执行**。固定坐标最大的风险是静默点错。

---

## 4. 范围与优先级

| 能力 | 优先级 | 说明 |
|---|---|---|
| 设备发现/连接/断开检测/授权提示 | P0 | `adb track-devices` 推送 |
| **注入能力自检**（权限/命令集/屏幕状态） | P0 | 不通过禁止开跑，见 §8.1 |
| 设备档案 + 三向一致性校验 + 校准 | P0 | 坐标地基 |
| 截图 + 坐标拾取（缩放/平移/1px 微调/落点叠加） | P0 | 核心交互 |
| tap / longPress / swipe / wait / keyevent / launchApp | P0 | 覆盖 90% |
| 顺序执行 + 单步延时 + 随机抖动 | P0 | |
| 分组与循环 | P0 | |
| 暂停 / 继续 / 急停（单动作粒度） | P0 | 安全底线 |
| 运行日志 + 失败截图 + 摘要 | P0 | |
| **`assertPixel` 校验** | P0 | 唯一「到位」判定手段 |
| JSON 保存/加载/导入导出 | P0 | |
| 简单置顶 HUD（进度 + 暂停 + 急停） | P0 | 设备悬浮窗的 PC 等价物 |
| 失败策略（中止/重试/继续/暂停） | P1 | 重试仅对校验类步骤有意义 |
| 文本输入（ASCII） | P1 | 中文见 §8.4 |
| 随机点击偏移 / 百分比坐标 | P1 | |
| 定时执行（计划任务 + CLI + 管道投递） | P1 | 见 §8.11 |
| 4 个脚本模板 | P1 | |
| 外挂 scrcpy 投屏（`--no-control`） | P1（v1.1） | 编排体验 |
| 快捷连点 | P2（v1.1） | 节拍受限 |
| `waitStable` / 曲线滑动 / 录制 / 找色 | P2（v1.2） | 先 Spike 再定 |
| 投屏窗口内取点 / 内嵌镜像 / 录屏证据 | P2（实验） | 误差源多 |
| 找图 / 控件定位 / OCR | P3（v2+） | |
| 多设备并行 | P3 | 架构预留 |

---

## 5. 术语

| 术语 | 定义 |
|---|---|
| 设备档案 DeviceProfile | 设备在特定分辨率/旋转下的坐标基准 |
| 基准分辨率 | 档案记录的 `refWidth×refHeight`，脚本坐标以此存储 |
| 三向一致性 | `wm size` / `screencap` 图幅 / 实际命中 三者一致 |
| 步骤 Step | 最小执行单元 |
| 分组 Group | 可循环的子步骤集合 |
| 运行 Run | 从第 1 步到最后一步的一次执行，有 runId |
| 在途动作 | 已下发但未返回的 adb 调用（最多 1 个） |
| HUD | 运行期置顶迷你控制条 |

---

## 6. 运行环境与技术选型

### 6.1 环境要求

| 项 | 要求 |
|---|---|
| 主机 | Windows 10/11 x64（当前 Win11 26200 已验证） |
| 设备 | Android 7.0+，真机或模拟器 |
| 连接 | **USB 数据线（P0）**；无线调试仅用于人工调试（P1），不作为无人值守路径 |
| 必装 | Android Platform-Tools（含 `adb`）——**当前机器未安装** |
| 可选 | scrcpy ≥ 2.0（当前 5.0.1，2026-10 确认），仅投屏/录屏 |
| 权限 | 无需管理员；手机开「USB 调试」 |
| 小米/红米 | 需开「USB 调试（安全设置）」，否则 `input` 抛 `SecurityException: INJECT_EVENTS`；该开关通常要求小米账号、部分机型需 SIM 卡并重启；存在账号级开启配额（以目标机实测为准） |
| 屏幕 | 主机 1920×1080+，支持 125%/150% DPI |

### 6.2 技术选型

| 方案 | 结论 | 理由 |
|---|---|---|
| **Python 3.13 + PySide6 + adb 子进程** | **推荐** | 本机已装；adb 交互简单；PyInstaller 打包；单人维护成本最低 |
| Electron + adbkit | 备选 | 体积与原生模块成本 |
| C# WPF + SharpAdbClient | 不选 | 本机无 .NET SDK |

**架构约束**

1. 设备交互必须经 `DeviceTransport`，禁止 UI 层拼命令。
2. `core` 包**禁止 import PySide6**（保证 CLI/无 GUI 模式可用），CI 加 import 检查。
3. `transport.py` 预留 `ScrcpyTransport` 空实现与能力协商接口，避免 v3 重构。
4. 统一 adb 版本：启动时记录 `adb version`，所有调用走同一路径，避免 scrcpy 自带 adb 抢占 server。

### 6.3 工程目录

```
outoclick/
├─ SPEC.md
├─ docs/FEASIBILITY-REVIEW.md
├─ .opencode/agents/spec-reviewer.md
├─ app/
│  ├─ main.py
│  ├─ ui/  main_window.py canvas_picker.py step_editor.py run_panel.py hud.py tray.py
│  ├─ core/
│  │  ├─ device_manager.py   # track-devices、授权状态、注入自检
│  │  ├─ transport.py        # DeviceTransport + AdbTransport（+ ScrcpyTransport 预留）
│  │  ├─ profile.py          # 档案、三向校验、校准
│  │  ├─ coords.py           # 坐标换算（单测重点）
│  │  ├─ script_model.py     # schema、校验、迁移
│  │  ├─ engine.py           # 状态机、取消语义
│  │  ├─ ipc.py              # 命名管道（GUI ↔ CLI）
│  │  ├─ scheduler.py        # 计划任务注册（INTERACTIVE_TOKEN）
│  │  ├─ mirror.py           # scrcpy 进程（--no-control）
│  │  ├─ recorder.py         # v1.2 getevent
│  │  └─ logger.py           # JSONL、原子写
│  └─ resources/
├─ tests/test_coords.py test_script_model.py test_engine.py
└─ build/
```

**数据目录（唯一）**：`%USERPROFILE%\Documents\AutoClickAndroid\`
```
profiles.json      # 原子写 + 备份
scripts/<id>.json
logs/<runId>/      # log.jsonl + summary.md + shot_*.png
settings.json
```

---

## 7. 系统架构

```
┌────────────────────────────────────────────────┐
│ UI（PySide6）：设备 │ 画布拾取 │ 时间线 │ 运行 │ HUD/托盘 │
└───────────────────┬────────────────────────────┘
                    │ 命令/事件
┌───────────────────▼────────────────────────────┐
│ Engine（独立线程，可取消）                       │
│ Idle→Preparing→Running⇄Paused→Stopping→Idle     │
│ Error→Idle / Running→Stopping                    │
│ ├ Executor：**一次 adb 调用 = 一个动作**          │
│ ├ Guard：三向校验、注入自检、边界、上限            │
│ └ Policy：abort/retry/continue/pause             │
└───────────────────┬────────────────────────────┘
                    │ execute(op) -> Result
┌───────────────────▼────────────────────────────┐
│ DeviceTransport                                 │
│ AdbTransport：input / screencap / am / monkey   │
│ ScrcpyTransport（v3 预留，控制通道）              │
└───────────────────┬────────────────────────────┘
                    │ USB
                 Android

scrcpy 进程（可选，v1.1）：--no-control，仅显示/录屏
```

**关键设计决定**

1. **取消语义下行不到设备端**：一次调用只含一个动作，急停最多等待 1 个在途动作返回（通常 ≤250ms）。**不做批处理**。
2. 延时由**主机侧**执行（可取消）；设备侧 sleep 仅作「精确延时模式」可选项，且单次 ≤1s，需接受该段不可中断。
3. 坐标在下发瞬间换算。
4. 任一前置校验失败 → 拒绝开跑。
5. GUI 与 CLI 不是两个引擎：**单引擎 + 命名管道投递**。

> **MVP 实现备注（v0.4）**：① 单实例 IPC 用 `127.0.0.1:47821` 回环 socket 代替命名管道（免 pywin32 依赖，Windows 下用 `SO_EXCLUSIVEADDRUSE` 防抢占）；② 全局急停键 F12 被占用时自动降级 F11/F10/F9/F8，并在 HUD 与日志显示实际键位；③ MVP 暂不内置 adb、不打包 exe；④ scrcpy 投屏仍按 §8.2 的 v1.1 计划，未进入 MVP。

---

## 8. 功能需求

### 8.1 FR-1 设备管理

| 编号 | 需求 | 验收要点 | 优先级 |
|---|---|---|---|
| FR-1.1 | `adb track-devices` 推送式发现，展示 serial/机型/版本/连接方式 | 插拔 3 s 内刷新 | P0 |
| FR-1.2 | 单设备选中运行，多设备可切换不并行 | 仅 1 台激活 | P0 |
| FR-1.3 | 离线/未授权提示到「请在手机上允许 USB 调试」 | 不崩溃 | P0 |
| FR-1.4 | 运行中掉线：立即 Error，记录最后成功步骤 | 不重试、不点错 | P0 |
| FR-1.5 | **注入自检**（零副作用）：`input` 无参打印 usage → 判断命令集；`input tap` 仅在用户确认页面执行一次并检查 stderr/exit | 检出 `INJECT_EVENTS` 拒绝时给出精确文案 | P0 |
| FR-1.6 | 屏幕状态自检：`dumpsys power` 的 `mWakefulness` + 锁屏状态 | 熄灭/锁屏 → 提示并尝试 `KEYCODE_WAKEUP` + `wm dismiss-keyguard`（安全锁屏无效则失败） | P0 |
| FR-1.7 | adb 路径可配置；记录并校验版本，统一使用同一 adb | 缺失时引导 | P0 |
| FR-1.8 | 无线调试配对/连接（人工调试用） | 明确标注「不建议无人值守」 | P1 |
| FR-1.9 | 运行期保持常亮（`svc power stayon usb`），结束还原；**仅此一处管理** | scrcpy 不再加 `--stay-awake` | P1 |
| FR-1.10 | 设备档案与 serial 绑定 | 换机提示 | P0 |
| FR-1.11 | 多屏/折叠：读取 displayId，`screencap`/`input` 带 `-d` | 主屏识别正确 | P1 |

### 8.2 FR-2 截图与坐标拾取

| 编号 | 需求 | 验收要点 | 优先级 |
|---|---|---|---|
| FR-2.1 | 一键截图并显示，≤ 800 ms | 失败可重试 | P0 |
| FR-2.2 | 缩放、平移、1:1；HiDPI 下坐标不偏 | 125%/150% 实测 | P0 |
| FR-2.3 | 点击生成 tap，坐标换算 1:1 | 用可逆开关验证命中（见 §9.6） | P0 |
| FR-2.4 | 十字准星 + 数值 + 方向键 1px 微调 | — | P0 |
| FR-2.5 | 框选生成 swipe；框选矩形生成区域随机点击 | — | P1 |
| FR-2.6 | 叠加显示步骤落点与序号 | 可开关 | P1 |
| FR-2.7 | 重新截图不丢步骤 | 截图与脚本分离 | P0 |
| FR-2.8 | 百分比视图切换 + 复制 | — | P1 |
| FR-2.9 | v1.1：启动 scrcpy 投屏（`--no-control --window-title outoclick-<serial>`） | 缺失时置灰 | P1 |
| FR-2.10 | 投屏内取点 | 需内容矩形映射，实验性 | P2 |
| FR-2.11 | 可选录屏证据 | 默认关闭 | P2 |
| FR-2.12 | `FLAG_SECURE` 页面截图全黑 → 明确提示「该页面禁止截图」 | 不误报颜色错误 | P1 |

### 8.3 FR-3 动作库

| 类型 | 参数 | 实现 | 优先级 |
|---|---|---|---|
| `tap` | x,y, `offsetJitterPx`, 延时 | `input tap` | P0 |
| `longPress` | x,y, 时长 | `input swipe X Y X Y dur` | P0 |
| `swipe` | 起止点, 时长 | `input swipe`（直线） | P0 |
| `wait` | 毫秒 | **主机侧**可取消等待 | P0 |
| `key` | keycode | `input keyevent` | P0 |
| `launchApp` | 包名 | `cmd package resolve-activity --brief` + `am start -n`，`monkey` 兜底 | P0 |
| `assertPixel` | x,y, 期望色, 容差 | 截图像素比对（P0：唯一到位判定） | P0 |
| `text` | ASCII | `input text`，空格转 `%s` | P1 |
| `stopApp` | 包名 | `am force-stop` | P1 |
| `screenshot` | 名称 | 存档 | P1 |
| `note` | 文本 | 注释 | P1 |
| `group` | 名称, repeat, steps | 结构化循环 | P0 |
| `waitStable` | 超时/采样/阈值 | 连续截图差异比对（v1.2，需先定判据） | P2 |
| `findColor` | 区域/色/容差 | 像素搜索（v1.2） | P2 |
| `drag` / `curve=bezier` | 分段 | **v2 实验**：`motionevent` 仅 Android 10+，且跨进程 downTime 不连续，不保证构成合法手势流 | P2 |

**明确不支持**：多指/捏合（v1/v2）。

### 8.4 文本输入约束

- `input text` 仅 ASCII；空格 `%s`；`& < > | ; " '` 需 shell 转义。
- 中文/emoji 不支持；v2 评估 ADBKeyBoard（需用户显式安装输入法）。
- 日志脱敏（首尾各 2 字符）。

### 8.5 FR-4 脚本模型与编辑

| 编号 | 需求 | 优先级 |
|---|---|---|
| FR-4.1 | 时间线：排序、插入、复制、删除、启停 | P0 |
| FR-4.2 | 每步延时/抖动/重试 | P0 |
| FR-4.3 | 分组折叠/改名/循环 1~10000 | P0 |
| FR-4.4 | 脚本级默认参数 | P0 |
| FR-4.5 | JSON 保存/另存/导入/导出 | P0 |
| FR-4.6 | 保存前校验（越界/空分组/无步骤/档案不存在/非 ASCII 文本） | P0 |
| FR-4.7 | 单步试运行 | P1 |
| FR-4.8 | 从失败步骤继续 | P1 |
| FR-4.9 | 模板：v1 一个「启动+等待+签到」，v1.1 扩到 4 个 | P1 |
| FR-4.10 | 备注、时间戳、最近运行结果徽标 | P2 |

### 8.6 FR-5 执行引擎

| 编号 | 需求 | 验收要点 | 优先级 |
|---|---|---|---|
| FR-5.1 | 单次/重复 N/无限（需勾选） | 无限默认不选 | P0 |
| FR-5.2 | 开跑前检查：在线、注入自检、屏幕唤醒、三向一致、脚本非空 | 任一失败阻止并说明 | P0 |
| FR-5.3 | 3 秒倒计时（可跳过） | — | P0 |
| FR-5.4 | 暂停/继续：不再下发，等在途动作返回 | ≤300 ms + 1 个在途 | P0 |
| FR-5.5 | 急停：F12 + UI + HUD | 停止后零新指令，无补偿动作 | P0 |
| FR-5.6 | 失败策略：中止/重试/跳过/暂停 | 重试仅对 `assertPixel` 等有反馈步骤有意义 | P1 |
| FR-5.7 | 单次运行步数上限（默认 10000） | 超出停止 | P0 |
| FR-5.8 | 实时高亮当前步骤 + 已用时/剩余 | — | P1 |
| FR-5.9 | 运行摘要（成功/失败/跳过/耗时/平均步耗时） | — | P1 |
| FR-5.10 | **单引擎互斥**：GUI 常驻时 CLI 经命名管道投递任务 | CLI 不被拒绝，返回结果码 | P0 |
| FR-5.11 | 崩溃/断电恢复：日志可判定中断步骤 | 重启提示 | P0 |

### 8.7 FR-6 防误点与安全

| 编号 | 需求 | 说明 | 优先级 |
|---|---|---|---|
| FR-6.1 | 三向不一致/旋转不符 → **拒绝运行** | 不降级、不猜 | P0 |
| FR-6.2 | **越界统一策略**：保存拒绝、运行前拒绝、**永不裁剪** | 删除 clamp 逻辑 | P0 |
| FR-6.3 | 首步屏幕指纹（可选） | 与模板截图低精度比对；模板图存 `assets/`（v1.1） | P1 |
| FR-6.4 | 高危二次确认 | <100ms 密集、无限循环、敏感文本 | P1 |
| FR-6.5 | 急停后不做恢复性点击 | — | P0 |
| FR-6.6 | 结束/异常还原设备设置（stayon 等） | 记录原值 | P1 |
| FR-6.7 | 能力探测零副作用 | 不执行「无害 tap」这类伪安全操作 | P0 |

### 8.8 FR-7 日志与证据

| 编号 | 需求 | 优先级 |
|---|---|---|
| FR-7.1 | 每次运行独立目录 + `log.jsonl` | P0 |
| FR-7.2 | 字段：ts/runId/stepId/type/params/sent/status/latencyMs/error | P0 |
| FR-7.3 | 失败截图 | P0 |
| FR-7.4 | 历史运行列表 | P1 |
| FR-7.5 | 保留 50 次或 30 天 | P1 |
| FR-7.6 | 文本脱敏 | P0 |
| FR-7.7 | 可选录屏 | P2 |

### 8.9 FR-8 设备档案与校准

| 编号 | 需求 | 优先级 |
|---|---|---|
| FR-8.1 | 采集 serial/model/版本/`wm size`/`wm density`/旋转（记录原始输出） | P0 |
| FR-8.2 | **三向一致性**：`wm size` 声明、`screencap` 图幅、实际命中；不一致拒绝运行 | P0 |
| FR-8.3 | 校准：用**可逆控件**（如设置里的开关）点击并截图比对状态；可参考开发者选项「指针位置」 | P0 |
| FR-8.4 | 档案与脚本绑定 | P0 |
| FR-8.5 | 档案管理（改名/删除/复制） | P1 |
| FR-8.6 | **不使用 `show_touches` 验证注入**（它只显示物理触摸） | P0 |

### 8.10 FR-9 极简连点（v1.1）

| 编号 | 需求 | 优先级 |
|---|---|---|
| FR-9.1 | ≤5 点，截图拾取 | P2 |
| FR-9.2 | 间隔下限：Android 12+ 60ms，≤11 150ms；随机 ±；区域随机 | P2 |
| FR-9.3 | F9 开始 / F10 暂停 / F12 急停 | P2 |
| FR-9.4 | 配置可另存，不生成脚本 | P2 |

### 8.11 FR-10 定时与命令行

| 编号 | 需求 | 优先级 |
|---|---|---|
| FR-10.1 | `outoclick.exe --run <id> --device <serial> --exit-when-done` | P1 |
| FR-10.2 | 计划任务注册：**必须**「仅在用户登录时运行」+ `WakeToRun` + `StartIn` + 30–60s 延迟 | P1 |
| FR-10.3 | GUI 已运行时 CLI 走命名管道投递（`\\.\pipe\outoclick`），等待结果码 | P1 |
| FR-10.4 | 静默模式：不弹窗，日志落盘 | P1 |
| FR-10.5 | 前置不满足（未登录/无设备/未唤醒）→ 明确失败日志，不静默成功 | P1 |
| FR-10.6 | 结束动作：退出/通知/关机/休眠 | P2 |

### 8.12 FR-11 HUD 与托盘

| 编号 | 需求 | 优先级 |
|---|---|---|
| FR-11.1 | 置顶迷你条：脚本名、进度、当前步、暂停、急停 | P0 |
| FR-11.2 | 半透明、可拖动、不占任务栏、不抢焦点（`Qt::Tool` + `WindowDoesNotAcceptFocus` + `WA_TranslucentBackground`） | P0 |
| FR-11.3 | 托盘：状态、开始/暂停/停止、打开主窗 | P1 |
| FR-11.4 | HUD 与主窗状态双向同步 | P0 |
| FR-11.5 | 热键仅 F12 可配置（v1） | P1 |

---

## 9. 坐标与分辨率策略（核心）

### 9.1 基本假设与反例

- 触控坐标 = 物理像素，原点左上。
- `screencap` 通常为物理像素，但**在 `wm size` Override 下不同 ROM 行为不一致**：有的跟随 Override，有的仍输出物理尺寸。因此必须实测，不可假设。
- 模拟器窗口缩放不影响 adb 坐标（内部仍是设备像素）。

### 9.2 设备档案

```json
{
  "id": "dp_01J8...",
  "serial": "emulator-5554",
  "model": "Pixel_6_API_34",
  "androidVersion": "14",
  "refWidth": 1080,
  "refHeight": 2400,
  "density": 420,
  "orientation": 0,
  "displayId": 0,
  "navMode": "gesture",
  "wmSizeRaw": "Physical size: 1080x2400",
  "screencapSize": "1080x2400",
  "calibrated": true,
  "createdAt": "2026-11-21T10:00:00+08:00"
}
```

- `orientation`：**v1 仅支持 0（竖屏）**；90 及 180/270 降为 P2，需先定义顺逆时针与读取命令。
- `coordMode` **不属于档案**，属于脚本（见 §10.1）。

### 9.3 换算与边界

```python
def to_device(x_ref, y_ref, prof, cur_w, cur_h):
    sx, sy = cur_w / prof.ref_width, cur_h / prof.ref_height
    if abs(sx - sy) / max(sx, sy) > 0.02:
        raise ResolutionMismatch(...)          # 比例失真 → 拒绝运行
    x, y = round(x_ref * sx), round(y_ref * sy)
    if not (0 <= x < cur_w and 0 <= y < cur_h):
        raise OutOfBounds(...)                 # 永不裁剪
    return x, y
```

**策略统一**：保存校验、运行校验、执行换算三处都「拒绝」，**全链路删除 clamp**。

### 9.4 旋转

| 当前旋转 | 变换（v2 才实现） |
|---|---|
| 0 | (x,y) |
| 90 | (y, W-1-x) |
| 180 | (W-1-x, H-1-y) |
| 270 | (H-1-y, x) |

v1 只支持 0；其他直接拒绝并提示。

### 9.5 常见陷阱

| 陷阱 | 现象 | 对策 |
|---|---|---|
| 状态栏/导航栏/刘海 | 点击被系统吃掉 | 拾取避开；用 key 代替手势 |
| 手势导航区 | 底部 20~40px 上滑回桌面 | 底部安全区告警 |
| 分辨率 Override | 两行 `wm size`，渲染与截图不一致 | 三向校验，不一致拒绝 |
| 折叠屏/背屏 | `screencap` 抓错屏 | `-d displayId` + 实测 |
| 分屏/悬浮窗 | 目标 App 只占半屏 | 脚本绑定全屏前置 |
| `FLAG_SECURE` | 截图全黑 | 明确提示，不当作颜色错误 |
| 屏幕熄灭 | 注入被吞且**返回 0** | 开跑前 `mWakefulness` 检查 |

### 9.6 校准流程（重做）

1. 读取 `wm size` / `wm density` / `screencap` 图幅，做三向比对。
2. 打开一个**可逆控件**（如「设置 → 显示 → 自动亮度」开关），记录初始截图。
3. 点击该控件中心坐标 → 再截图 → 状态确实翻转 → 判定坐标链路有效 → 复原开关。
4. 可选：开发者选项「指针位置」叠加显示触摸坐标，人工核对（需实测其对注入事件的显示行为）。
5. **不使用 `show_touches`**：它只反映物理触摸，注入事件不会出现白点。

---

## 10. 脚本模型（JSON Schema）

### 10.1 顶层

```json
{
  "schemaVersion": 1,
  "coordMode": "absolute",
  "id": "sc_01J8ABCDEF",
  "name": "每日签到",
  "deviceProfileId": "dp_01J8XYZ",
  "defaults": {
    "stepDelayMs": 300,
    "jitterMs": 80,
    "retries": 1,
    "onError": "abort",
    "maxSteps": 10000,
    "offsetJitterPx": 0
  },
  "steps": []
}
```

- `coordMode` 在**脚本级**：`absolute`（整数，基于档案基准分辨率）或 `percent`（0~1 浮点，v1.1）。
- 兼容策略：只拒绝**更高 major 版本**；未知字段忽略并告警。禁止「未知版本一律拒绝」。

### 10.2 步骤通用字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | string | `s_001` |
| `type` | enum | 见 §8.3 |
| `label` | string | 展示名 |
| `enabled` | bool | 默认 true |
| `delayAfterMs` | int | 主机侧等待 |
| `jitterMs` | int | `±jitter` 均匀随机 |
| `retries` | int | 仅对有反馈步骤生效 |
| `onError` | enum | abort/retry/continue/pause |

### 10.3 示例

```json
[
  { "id": "s_001", "type": "launchApp", "pkg": "com.example.app", "delayAfterMs": 4000 },
  { "id": "s_002", "type": "tap", "label": "签到入口",
    "point": { "x": 540, "y": 1200 }, "offsetJitterPx": 6, "delayAfterMs": 800 },
  { "id": "s_003", "type": "assertPixel",
    "point": { "x": 100, "y": 200 }, "color": "#FF5722", "tolerance": 12, "onFail": "retry" },
  { "id": "s_004", "type": "longPress", "point": { "x": 300, "y": 900 }, "durationMs": 800 },
  { "id": "s_005", "type": "swipe",
    "from": { "x": 540, "y": 1800 }, "to": { "x": 540, "y": 900 }, "durationMs": 300 },
  { "id": "s_006", "type": "key", "keycode": "KEYCODE_BACK" },
  { "id": "s_007", "type": "group", "label": "领奖 3 轮", "repeat": 3,
    "steps": [
      { "id": "s_007_1", "type": "tap", "point": { "x": 900, "y": 2100 }, "delayAfterMs": 1200 }
    ]
  }
]
```

### 10.4 校验规则

1. major 版本高于支持范围 → 拒绝；低版本 → 迁移。
2. 坐标越界 → **拒绝保存**（不裁剪）。
3. `group.repeat` ∈ [1,10000]，`steps` 非空。
4. 展开步数 ≤ `maxSteps`。
5. 非 ASCII 文本 → 保存告警、运行前阻止。
6. `deviceProfileId` 必须存在。
7. `curve`/`motionevent`/`waitStable` 等实验特性需能力探测通过，否则阻止保存（而非运行期降级）。

---

## 11. 执行引擎

### 11.1 状态机

```
Idle ──start──▶ Preparing ──ok──▶ Running ──done──▶ Idle
                   │                 │  │  ▲
                   │fail             │  │  │
                   ▼                 │  ▼  │
                 Error ◀─────────────┘ Paused
                   │                    │
                   │                 Stopping ◀── (Running/Paused 急停)
                   └────────▶ Idle ◀─────┘
```

- `Running → Stopping`、`Paused → Stopping`、`Error → Idle` 必须显式建模。
- 状态字段：`state`、`currentStepIndex`、`inFlight`、`startedAt`、`elapsedMs`、`ok/fail/skip`。

### 11.2 性能模型（分档，实测回填）

| 操作 | Android ≤11 | Android 12+ | 无线 |
|---|---|---|---|
| `input tap`（单次 shell 端到端） | 300–700 ms | 40–200 ms | +50–300 ms |
| `screencap` 全屏 | 200–600 ms | 200–600 ms | 300–900 ms |
| `launchApp` | 1–5 s | 1–5 s | — |

- **节拍结论**：含 300ms 步间延时，实际约 **1.2–3 次/秒**（≤11 更慢），不是「3–8 次/秒」。`3–8/s` 只在无延时且 12+ 机型上成立。
- 「设备侧 sleep 精度 ±20ms」仅指 `sleep` 本身；**动作间隔误差**主要来自 `input` 启动与 USB 往返（20–200ms）。

### 11.3 批处理（v1 不做，v2 条件引入）

**为什么砍**：`adb shell "a; sleep; b"` 在设备端串行执行，主机侧取消无法中断远端进程；退出码只反映最后一条；逐步日志与 latency 失真。与「急停 ≤300ms + 零后续动作」互斥（评审阻断项 B1）。

v2 若确需高频，采用**设备端哨兵文件**：

```sh
# 每条动作前检查，存在即退出
[ -f /data/local/tmp/oc.stop ] && exit 9
input tap 540 1200 || exit 1
```

急停 = `adb shell touch /data/local/tmp/oc.stop`（约 50ms 生效），单批 ≤3 步，跑前清理哨兵。即便如此，**v1 不实现**。

### 11.4 时序与取消

- 延时默认主机侧 `wait`，可被急停打断。
- 可选「精确延时模式」：把 `sleep` 追加到同一 adb 命令尾部，代价是该段不可中断（仅在延时 ≤1s 且用户接受时启用）。
- 每个动作前检查 `cancel_event`；`inFlight` 最多 1，返回即停。

### 11.5 失败判定

| 来源 | 说明 |
|---|---|
| adb exit code ≠ 0 | 命令级失败 |
| stderr 含 `INJECT_EVENTS` / `SecurityException` | 权限失败，立即中止并提示 |
| 超时（默认 5s） | 设备无响应 |
| 设备掉线 | 立即 Error |
| `assertPixel` 不匹配 | 唯一的「到位」失败 |

> `input tap` 在屏幕熄灭、被系统丢弃时仍可能返回 0，**不能用返回码判断点击是否生效**；关键路径必须配 `assertPixel`。

### 11.6 急停

- `RegisterHotKey` 注册 F12；HUD/托盘同权。
- 停止后不发补偿动作，状态置 `Stopping` 直到在途动作返回。

### 11.7 录制（v1.2）

1. `adb shell getevent -pl` 列出输入设备与能力，识别触摸节点。
2. 读取 `ABS_MT_POSITION_X/Y` 的 min/max，把原始值归一化到显示像素。
3. `getevent -lt` 采样，按 `SYN_REPORT` 切分手势，生成 tap/longPress/swipe + 间隔。
4. 注意：`getevent` **看不到注入事件**，录制只对真实手指操作有效；PC 投屏点击不会产生输入事件。
5. 探测失败 → 录制入口置灰。

---

## 12. 界面设计

```
┌──────────────────────────────────────────────────────────────────────┐
│ 设备: [小米14 (USB)] ▼ [刷新] [自检] [校准] [截图] [投屏v1.1]  ● 就绪  │
├───────────────┬──────────────────────────────────┬───────────────────┤
│ 脚本列表       │        截图画布                   │  当前步骤属性      │
│ ▸ 每日签到     │  ┌────────────────────────────┐  │  类型: tap        │
│ ▸ 游戏日常     │  │                            │  │  坐标: 540, 1200  │
│ [+ 新建][模板] │  │      [手机画面]             │  │  偏移 ±[6] px     │
│                │  │      ① ②  ③                │  │  延时 [300] ms    │
│                │  └────────────────────────────┘  │  失败: 中止 ▼     │
│                │ [重新截图][显点][1:1][百分比]      │  [删除步骤]       │
├───────────────┴──────────────────────────────────┴───────────────────┤
│ 1 ▸ 启动 App   com.example.app        4000ms                        │
│ 2 ● 签到入口   (540,1200) ±6          800ms                         │
│ 3 ✓ 校验像素   (100,200) #FF5722      失败重试 2                     │
├──────────────────────────────────────────────────────────────────────┤
│ [▶试跑][▶▶运行×1][⏸暂停][■急停 F12]  进度 6/23 用时 00:12           │
│ 12:00:03 s_002 tap(540,1200) ok 118ms                                │
└──────────────────────────────────────────────────────────────────────┘
```

**原则**：三栏固定、拾取不弹窗、危险操作二次确认、运行中锁编辑、HUD 常驻可急停。

---

## 13. 日志格式

```json
{"ts":"2026-11-21T12:00:03.412+08:00","runId":"run_01J8","event":"action_sent","stepId":"s_002","type":"tap","sent":{"x":546,"y":1203},"latencyMs":118,"status":"ok"}
{"ts":"2026-11-21T12:00:04.900+08:00","runId":"run_01J8","event":"assert","stepId":"s_003","expected":"#FF5722","actual":"#FF5722","diff":0,"status":"ok"}
{"ts":"2026-11-21T12:00:05.001+08:00","runId":"run_01J8","level":"ERROR","event":"step_end","stepId":"s_007","type":"text","params":{"content":"he***ld"},"status":"fail","error":"adb exit 1: Injection failed"}
{"ts":"2026-11-21T12:00:05.100+08:00","runId":"run_01J8","event":"run_end","status":"error","ok":6,"fail":1,"skip":0,"elapsedMs":12100}
```

`summary.md`：

```
运行 run_01J8 · 每日签到
结果: 失败（第 7 步）
总步数 23 / 成功 6 / 失败 1 / 跳过 0
耗时 12.1s（平均 1.7s/步）
校验步通过率: 1/1
失败截图: fail_s_007.png
```

---

## 14. 非功能需求

| 编号 | 类别 | 要求 |
|---|---|---|
| NFR-1 | 启动 | onedir 打包；冷启动 ≤3 s |
| NFR-2 | 长稳 | 8 h：内存增长 ≤50 MB，无句柄泄漏，UI 不卡死 |
| NFR-3 | UI | 操作反馈 ≤100 ms；运行期不阻塞 |
| NFR-4 | 取消 | 急停 ≤300 ms + 最多 1 个在途动作 |
| NFR-5 | 恢复 | 非正常退出提示中断步骤 |
| NFR-6 | 权限 | 无需管理员 |
| NFR-7 | 兼容 | Win10/11；125%/150% DPI；中文/空格路径 |
| NFR-8 | 数据 | 全本地；无外发网络（除 adb 回环） |
| NFR-9 | 可移植 | 无硬编码分辨率 |
| NFR-10 | 可测试 | `coords.py`/`script_model.py` ≥80% |
| NFR-11 | 分层 | `core` 不 import PySide6（CI 检查） |
| NFR-12 | 资源 | 空闲 ≤200 MB |

---

## 15. 验收标准（可判定）

| 编号 | 场景 | 通过标准 |
|---|---|---|
| AC-01 | 未装 adb | 配置向导可指定目录并验证；`adb version` 记录 |
| AC-02 | 已授权设备 | 3 s 内列出，机型/分辨率正确 |
| AC-03 | 未授权 | 明确提示，不崩溃 |
| AC-04 | 截图点选 5 点 | 生成 5 个 tap；用可逆控件验证 3 个点命中（状态确实翻转） |
| AC-05 | 1080×2400 档案在 720×1600 运行 | 按比例换算；可逆控件验证命中；无越界 |
| AC-06 | 宽高比不一致 | 拒绝运行，提示「比例失真」 |
| AC-07 | 20 步脚本 ×10 次（含 3 个 assertPixel） | 日志 200 条 `action_sent`，无漏步；校验步通过率 100% |
| AC-08 | 运行中 F12 | ≤300 ms 后不再有新指令；最多 1 个在途动作完成；无补偿点击 |
| AC-09 | 运行中拔 USB | 立即 Error，最后一步标记离线，不卡死 |
| AC-10 | 分组 repeat=3 | 子步骤 3 遍，stepId 带层级 |
| AC-11 | 无限循环未确认 | 无法启动 |
| AC-12 | 越界脚本 | **保存被拒绝**（不是运行期裁剪） |
| AC-13 | 中文文本 | 保存告警，运行前阻止 |
| AC-14 | 8 h 循环 | 内存 ±50MB；指令异常率 ≤1%（分母=指令数）；可急停 |
| AC-15 | 断电重启 | 提示中断步骤，日志可读 |
| AC-16 | 干净 Win11 打包运行 | onedir 可启动，无缺 DLL，冷启动 ≤3 s |
| AC-17 | MIUI 未开安全设置 | 自检检出 `INJECT_EVENTS`，给出开启指引，禁止开跑 |
| AC-18 | 屏幕熄灭 | 开跑前检出并提示；唤醒成功则继续，否则失败 |
| AC-19 | 计划任务每天 8:00（已登录 + 锁屏 + USB） | 到点执行，日志落盘；注销/无设备时明确失败 |
| AC-20 | GUI 常驻时 CLI 触发 | 管道投递成功并返回结果码，不被单实例锁拒绝 |
| AC-21 | `wm size` Override 机型 | 三向校验检出不一致 → 拒绝运行 |
| AC-22 | `FLAG_SECURE` 页面 | 提示「禁止截图」，不误报颜色不匹配 |
| AC-23 | 脚本版本兼容 | 未知字段忽略并告警；更高 major 拒绝加载 |
| AC-24 | HUD | 显示进度；按钮可用；不抢焦点；不占任务栏 |
| AC-25 | 随机偏移 ±6px | 100 次日志落点呈离散分布，均值接近目标点 |
| AC-26 | 旧 Android（≤11）设备 | 性能表中对应档位；节拍如实显示，不虚标 |

---

## 16. 里程碑与工作量（评审修正）

### 16.1 MVP（7–9 人日）——先交付能用的最小闭环

**范围**：设备发现/授权/注入自检、档案 + 三向校验、截图拾取（缩放/平移/微调）、tap/longPress/swipe/wait/key/launchApp/group、单步延时 + 抖动、F12 急停、JSON 存取、JSONL 日志 + 失败截图、`assertPixel`、简单 HUD。

| 阶段 | 内容 | 预估 |
|---|---|---|
| M0 | 骨架、adb 封装、设备列表、授权状态、配置向导 | 2 人日 |
| M1 | 档案/自检/校准、截图拾取、基础动作、顺序执行、日志 | 5–6 人日 |
| M2 | 分组/延时抖动、失败策略、暂停急停、存取、HUD | 2–3 人日 |
| **合计** | | **7–9 人日**（含 Spike 结论落地） |

### 16.2 v1.0（累计 20–26 人日）

| 阶段 | 内容 | 预估 |
|---|---|---|
| M3 | 模板、百分比坐标、随机偏移、历史运行、单步/续跑 | 3–4 人日 |
| M4 | scrcpy 投屏（`--no-control`）、CLI + 管道、计划任务、托盘 | 4–5 人日 |
| M5 | 打包 onedir、使用说明、26 条 AC 回归 | 2–3 人日 |
| M6 | v1.1/v1.2：连点、录制、找色、waitStable（逐项 Spike 后再估） | 4–6 人日 |

> 评审结论：原始「7 人日完成 v1.0」偏乐观约 2.5–3.5 倍；AC 回归与设备调参本身需 2–3 天。

---

## 17. 风险与对策

| 风险 | 影响 | 概率 | 对策 |
|---|---|---|---|
| MIUI/HyperOS 未开安全设置或账号配额 | `input` 抛 `INJECT_EVENTS`，主场景失效 | 高 | P0 自检 + 精确引导；固定一台已配置好的设备 |
| 屏幕熄灭/锁屏导致注入被吞且返回 0 | 静默空跑 | 高 | 开跑前 `mWakefulness`/锁屏检查；USB 常连；`stayon usb` |
| 固定坐标脆弱（改版/分辨率/Override） | 点错 | 高 | 三向校验 + 比例保护 + 拒绝运行；v2 找图 |
| `input` 无到位反馈 | 「成功」不可信 | 高 | `assertPixel` 升 P0；关键步骤必须校验 |
| 批处理破坏急停 | 安全事故 | 高 | v1 不实现；v2 用哨兵文件 |
| `motionevent` 仅 10+ 且手势流不连续 | 曲线滑动失效 | 中 | 降 P2；v3 走 scrcpy 控制通道 |
| `getevent` 被 ROM 限制 / 坐标量程不一 | 录制不可用或坐标错 | 中 | 能力探测 + min/max 归一化；失败置灰 |
| scrcpy 默认控制与引擎抢输入 | 双重点击 | 中 | 强制 `--no-control`；仅显示 |
| 计划任务 Session 0 无桌面 | 任务「成功」但没执行 | 中 | 强制 INTERACTIVE_TOKEN + 登录要求 |
| 单实例锁与 CLI 冲突 | 定时任务永远失败 | 高 | 改为管道投递 |
| PyInstaller onefile 冷启动慢 | 体验差 | 中 | onedir + 快捷方式 |
| adb 版本冲突（scrcpy 自带 adb） | server 重启打断运行 | 中 | 统一 adb 路径与版本 |
| 中文输入 | 无法输入 | 中 | 明确不支持；v2 ADBKeyBoard |
| 反作弊/封号 | 账号风险 | 中 | 抖动/偏移/限频；风险自负 |
| 误点造成真实损失 | 不可逆 | 低 | 高危二次确认；禁止自动确认支付弹窗 |

---

## 18. 演进路线

- **v1.0**：MVP + 模板 + 百分比 + 投屏（只读）+ CLI/计划任务 + 托盘。
- **v1.1**：快捷连点；`waitStable`（先定判据）；`findColor`。
- **v1.2**：`getevent` 录制；投屏内取点；旋转换算（90/180/270）。
- **v2**：OpenCV 模板匹配 + 简化条件分支；`uiautomator dump` 控件定位；ADBKeyBoard 中文。
- **v3**：scrcpy 控制通道（高频/多指/曲线）；多设备；OCR。

---

## 19. 待确认的开放问题

1. **目标设备型号**（决定注入权限、`getevent`、Override 行为、`displayId`）。
2. 目标 App 是否 `FLAG_SECURE`？是否检测注入？
3. 无人值守是否接受「必须保持登录 + USB 常连 + 屏幕常亮」？
4. 中文输入是否刚需（决定 v2 优先级）？
5. 是否内置 adb / scrcpy（体积 + 许可 + 版本统一）？
6. 是否需要多设备（若半年内扩，M1 就把引擎实例化，+0.3 人日）？
7. 是否接受「分辨率/旋转不符直接拒绝运行」的硬策略？
8. 游戏场景的封号风险是否接受？

---

## 20. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v0.1 | 2026-11-21 | 初版：目标、场景、架构、坐标策略、脚本模型、验收 |
| v0.2 | 2026-11-21 | 新增竞品参考；引入投屏、连点、HUD、定时/CLI、模板、找色/录制规划 |
| v0.3 | 2026-11-21 | 按可行性评审修订：砍批处理、修 `motionevent`/`show_touches`/性能数字、三向校验、注入与屏幕自检、管道投递、计划任务会话、`assertPixel` P0、越界统一、排期重估 |
| v0.4 | 2026-11-22 | MVP 落地：Python 3.13 + PySide6 实现；回环 socket 替代命名管道；热键自动降级；新增 `MockTransport`、CLI、HUD、可逆控件校准、29 项测试 |

---

## 附录 A：ADB 命令速查

```bash
adb devices -l
adb shell input                                   # 打印 usage，用于零副作用能力探测
adb -s <serial> shell wm size
adb -s <serial> shell wm density
adb -s <serial> shell wm user-rotation            # 版本差异大，需实测
adb -s <serial> shell dumpsys power | grep mWakefulness
adb -s <serial> shell dumpsys window | grep -E "mDreamingLockscreen|mCurrentFocus"
adb -s <serial> exec-out screencap -p
adb -s <serial> exec-out screencap -d 0 -p        # 多屏
adb -s <serial> shell input tap 540 1200
adb -s <serial> shell input swipe 540 1800 540 900 300
adb -s <serial> shell input swipe 300 900 300 900 800          # 长按
adb -s <serial> shell input keyevent KEYCODE_BACK
adb -s <serial> shell input keyevent KEYCODE_WAKEUP
adb -s <serial> shell wm dismiss-keyguard
adb -s <serial> shell input text 'hello%sworld'
adb -s <serial> shell cmd package resolve-activity --brief <pkg>
adb -s <serial> shell am start -n <pkg>/<activity>
adb -s <serial> shell am force-stop <pkg>
adb -s <serial> shell svc power stayon usb
adb -s <serial> shell settings get system show_touches    # 仅查，不用于验证注入
adb -s <serial> shell getevent -pl
adb -s <serial> shell getevent -lt /dev/input/event2
adb -s <serial> shell id                                   # 看是否属 input 组
```

**Windows 注意**：PowerShell 5.1 下 `adb exec-out screencap -p > x.png` 会破坏二进制流；用 `subprocess.run(capture_output=True)` 取 bytes，或 `adb shell screencap -p /sdcard/x.png && adb pull`。

## 附录 B：scrcpy 参数（v1.1，只读模式）

```bash
scrcpy -s <serial> --no-control --no-audio \
       --window-title outoclick-<serial> \
       --max-size 1024 --video-bit-rate 8M
# 录屏证据（可选）
scrcpy -s <serial> --no-control --no-audio --record logs/<runId>/screen.mp4
```

- `--no-control` 必须加：否则 scrcpy 自身会注入点击，与引擎冲突。
- 不加 `--stay-awake`：常亮由应用统一管理（`svc power stayon usb`）。
- 版本要求 ≥2.0，当前验证版本 5.0.1（2026-10）。

## 附录 C：MVP 前置 Spike（先做，再写业务）

| # | 目标 | 通过标准 | 耗时 |
|---|---|---|---|
| S1 | 注入可用性 + 单次开销（≤11 与 12+ 各一台） | 无 `INJECT_EVENTS`；拿到 P50/P95 | 0.5 天 |
| S2 | 急停真实延迟（单动作 vs 批处理） | 证明单动作 ≤300ms+1；批处理不可用 | 0.5 天 |
| S3 | `wm size` Override 下 `screencap` 图幅与命中 | 明确是否一致；确定三向校验规则 | 0.5 天 |
| S4 | 屏幕熄灭/锁屏/无线下的注入行为 | 明确返回码与现象；确定前置检查 | 0.3 天 |
| S5 | `getevent` 权限与坐标量程 | 可读性 + 归一化系数 | 0.3 天 |
| S6 | scrcpy `--no-control` + 窗口定位 | 点窗口设备无响应；能取内容矩形 | 0.5 天 |
| S7 | 计划任务两种 logon 模式 + GUI 常驻 CLI | 锁屏可跑；确认管道方案 | 0.3 天 |
| S8 | PyInstaller onedir 干净机 | 能起、无缺 DLL、≤3s | 0.5 天 |
| **合计** | | | **约 3.4 天** |
