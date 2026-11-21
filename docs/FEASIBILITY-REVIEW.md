# 可行性评审记录 · outoclick SPEC v0.2

| 项 | 内容 |
|---|---|
| 评审日期 | 2026-11-21 |
| 评审对象 | `SPEC.md` v0.2 |
| 评审方式 | 独立审查子 agent（`.opencode/agents/spec-reviewer.md`），只读、不执行命令、联网核实 |
| 结论 | **有条件可行**，整体风险 **中高** |
| 处置 | 已按评审修订为 SPEC v0.3；遗留项见文末 |

> 说明：评审中的平台行为多数给出了出处，但目标机型未定，凡标注「待实测」的条目仍需按 SPEC 附录 C 的 Spike 在真机上确认。

---

## 1. 结论

有条件可行——核心路径（免 Root、`adb shell input` 固定坐标回放）成立，但规格中至少有 1 条会导致 P0 验收必然失败的设计（批处理 × 急停）、6 条事实性错误假设（motionevent 版本、show_touches 校准、3–8 次/秒、getevent 坐标、scrcpy 控制冲突、计划任务会话），整体风险等级：中高；7 人日工时估算偏乐观约 2.5–3.5 倍。

## 2. 分级问题清单

### [阻断]

**B1. §11.2 的「批处理」与 §8.6/§15 的三条 P0 验收直接互斥，按现规格实现必然失败**

- 问题：把连续步骤合并成一条 `adb shell "a; sleep 0.3; b"` 后，主机侧取消（`cancel_event`）无法中断设备端已在执行的 shell 脚本；规格自己在 §11.5 承认「批处理粒度 = 一次 adb shell 返回」。
- 依据：§11.2、§11.5、FR-5.4/5.5/5.8、FR-7.2、AC-08。机制：`adb shell` 只在远端进程结束时返回；`sh -c "a; b"` 退出码仅反映最后一条命令；杀掉主机 adb 客户端不会终止设备端进程。
- 影响：AC-08 必挂；20 步 × 0.3s 批最长 6–9s 才停；失败步定位、逐步日志、暂停全部失真。
- 验证：`adb shell 'for i in $(seq 20); do input tap 500 500; sleep 0.3; done'` 运行中按 F12 / kill 主机进程，用另一台手机录屏数停止后仍发生的点击数。
- 建议：v1 默认关闭批处理；若保留，改为设备端哨兵文件方案，急停 = `adb shell touch /data/local/tmp/oc.stop`，单批 ≤3 步，每条 `|| exit N`。

### [高]

**H1. `input motionevent` 在 Android 7/8/9 上不存在，且即使存在也不构成一条合法手势流**

- 依据：AOSP `Input.java`：7.1.2 / 8.0 / 8.1 / 9.0 无 `motionevent`，Android 10.0.0_r1 起才有；`draganddrop` 是 8.0 起。`InputShellCommand` 中 `final long now = SystemClock.uptimeMillis()`，每次调用 downTime 都等于当时时刻，跨进程拼出的 DOWN→MOVE→UP 不是同一手势流，InputDispatcher 会做一致性校验并可能丢弃。
- 影响：AC-21「轨迹为曲线」不可保证；分段多时每段付一次 `input` 启动成本，首个 MOVE 若延迟 >500ms 会先触发长按。
- 建议：`curve` 降为 P2 实验，默认直线；曲线/高频改用 scrcpy control socket 或 v2 minitouch。

**H2. MIUI/HyperOS 的 `INJECT_EVENTS` 门槛会直接否决无人值守主场景**

- 依据：未开「USB 调试（安全设置）」时 `input` 抛 `SecurityException: Injecting input events requires ... INJECT_EVENTS`；该开关需小米账号、部分机型需 SIM 并重启；存在账号级开启配额；海外版/未登录账号可能无法开启。
- 影响：S1 每日签到可能整体失效；原规格仅一句「需额外开」，未量化。
- 建议：注入可用性自检提升 P0；文档单列前置条件与配额；固定一台已配置设备。

**H3. 用 `show_touches` 校准注入落点 = 方法无效**

- 依据：`show_touches` 只显示物理触摸（scrcpy 文档明确 "it only shows physical touches"）；`input tap` 注入点在 InputDispatcher 之上，不产生 `/dev/input` 事件。
- 影响：原 §9.6 与 FR-8.3 无法验证「坐标→落点」，而这是产品地基。
- 建议：改用开发者选项「指针位置」（需实测）或「可逆控件验证」：点设置开关 → 截图比对状态。

**H4. 「screencap 尺寸 == 触控物理像素」在 Override/多屏/挖孔场景存在反例**

- 依据：有资料称 `wm size` 后 screencap 与 input 都用新尺寸；也有实测称 screencap 仍是物理分辨率。两者冲突 → 随版本/厂商而异。折叠屏/背屏可能抓错屏，需 `-d displayId`。
- 影响：整体比例偏移，静默点错。
- 建议：三向一致性硬校验，任一不一致拒绝运行；`-d displayId` 纳入 screencap/input/getevent。

**H5. 无人值守在三种常见状态下静默失效**

- 依据：`stayon usb` 与 scrcpy `--stay-awake` 只在插电/USB 生效；屏幕熄灭后注入被吞且不报错；重启/更新后停锁屏，`wm dismiss-keyguard` 对安全锁屏无效。
- 建议：S1 强制 USB 常连；开跑前检查 `mWakefulness` 与锁屏；不满足明确失败。

**H6. FR-5.10 单实例互斥 与 FR-10.1/AC-19 定时 CLI 冲突**

- 影响：GUI 常驻时 8:00 的计划任务被文件锁拒绝 → 每日签到永远失败且用户不可见。
- 建议：改为「单引擎 + 命名管道投递」；CLI 检测到已有实例则投递并等待结果码。

**H7. Windows 计划任务的会话模型与规格假设不符**

- 依据：`TASK_LOGON_SERVICE_ACCOUNT` 在 Session 0 运行，无交互窗口站，GUI/Qt/HUD/RegisterHotKey 不可用；只有 `TASK_LOGON_INTERACTIVE_TOKEN` 能出窗。锁屏（未注销）可运行；注销/睡眠不可。
- 建议：注册时强制 INTERACTIVE_TOKEN + WakeToRun + 30–60s 延迟 + StartIn；UI 明示「必须保持登录、锁屏可跑」。

**H8. scrcpy 参数基本成立，但缺 `--no-control` 会让「显示通道」变成「控制通道」**

- 依据：`--window-title` / `--always-on-top` / `--stay-awake` / `--max-size` / `--video-bit-rate` / `--no-audio` 均存在；scrcpy 默认开启鼠标/键盘控制，需 `--no-control`。当前版本 5.0.1（2026-10），`--no-audio` 需 ≥2.0，`--video-bit-rate` 是 2.0 起的名字。
- 影响：点镜像窗口 = scrcpy 注入一次 + 记录一次 = 双重点击。
- 建议：附录 B 补 `--no-control`；坐标映射用内容矩形而非窗口矩形；v1 不做窗口内取点。

**H9. 「没有到位反馈」使失败策略、重试与失败率 KPI 形同虚设**

- 依据：`input tap` 在屏幕熄灭、目标不接收、注入被丢弃时仍返回 0；原 §11.4 失败来源只有退出码/超时/掉线/assertPixel。
- 影响：`retries` 对 tap/swipe 无意义；KPI-5 无分母；「强校验」退化。
- 建议：`assertPixel` 升 P0；定义「成功 = 命令返回 0 + 可选校验通过」；KPI-5 改为指令异常率 + 校验步通过率。

### [中]

**M1. §11.2 节拍数字自相矛盾**：`sleep 0.3` + `input` 成本 → 实际 2.2–3.1 次/秒，不可能 3–8/s；「±20ms」是 sleep 精度，非动作间隔精度。Android 12 起 `input` 迁入 system_server，单次 40–80ms；≤11 为 300–700ms。

**M2. FR-9.2「间隔 50ms」与自身性能结论互斥**；FR-6.4「<100ms 二次确认」同理。建议连点下限 150ms（≤11）/ 60ms（12+）。

**M3. `getevent` 录制缺两个必要环节**：①触控面板坐标 ≠ 显示像素，必须用 `gotevent -p` 的 ABS_MT min/max 归一化；②`getevent` 看不到注入事件，点投屏窗口录制将录不到东西。权限本身比预期乐观（读通常可行，写才被 SELinux 拦）。

**M4. `coordMode` 归属错误**：坐标在脚本、开关在档案 → 切 percent 必然 schema 迁移。应在脚本级预留。

**M5. 越界行为四处不一致**：保存拒绝 / 运行裁剪 / clamp 默认 / 拒绝执行。应收敛为「保存拒绝、运行拒绝、永不裁剪」。

**M6. 旋转定义不可实现**：MVP 只 0/90 与 180/270 表冲突；90/270 顺逆时针未定义；orientation 读取命令各版本不同。建议 v1 只支持 0。

**M7. `launchApp` 用 `monkey` 不够稳**：无 LAUNCHER 入口会失败。建议 `cmd package resolve-activity --brief` + `am start -n`，monkey 兜底。

**M8. 「能力探测」未定义且给的探测手段有副作用**：`input tap` 无「无害」可言。建议用 `adb shell input` 打印 usage 判断命令集（零副作用），权限用「被拒绝时抛 SecurityException」判定。

**M9. 数据目录两处定义 + 并发无锁 + 前向兼容**：项目内 logs/scripts 与用户数据目录同名；`profiles.json` 无原子写；「未知 schemaVersion 拒绝加载」会使新旧脚本互不兼容。

**M10. `svc power stayon usb` 与 scrcpy `--stay-awake` 抢同一设置**，退出互相覆盖。建议只由应用管理。

**M11. NFR-1 冷启动 ≤3s 与 PyInstaller onefile 冲突**：onefile 解包 1–3s、体积 60–120MB。建议 onedir。

**M12. 状态机图不完整**：缺 `Running→Stopping`、`Paused→Stopping`、`Error→Idle`。

### [低]

- L1 F12 可能被调试器/加速器占用（可配置即可）。
- L2 设备刷新建议 `adb track-devices` 推送而非轮询。
- L3 多 adb 版本（scrcpy 自带 adb）会重启 server、打断运行；统一 `ADB` 路径。
- L4 `FLAG_SECURE` 页面截图全黑，`assertPixel` 会永久失败，需可解释错误。
- L5 HUD「不抢焦点、不占任务栏」需 `Qt::Tool + WindowDoesNotAcceptFocus + WA_TranslucentBackground`，预留 0.5 天。

## 3. 维度评分

| 维度 | 结论 | 关键理由 |
|---|---|---|
| 技术可行性 | 黄 | `input` 广泛可用；`motionevent` 版本与手势流限制；`getevent` 需归一化；MIUI 门槛 |
| 关键假设 | 红 | screencap/触控像素、show_touches、批处理急停、节拍数字均不成立 |
| 架构合理性 | 黄 | Transport 方向对；批处理破坏时序/取消；单实例与 CLI 冲突 |
| 安全与误操作 | 红 | 急停承诺失效；无到位判定；scrcpy 抢输入 |
| 可测试性 | 红 | KPI-2/AC-07/AC-17 判定缺失；AC-21 主观 |
| 范围与排期 | 红 | 7 人日对应 18–26 人日 |
| 运维与交付 | 黄 | 计划任务会话、adb 分发、打包冷启动可控 |
| 合规与风险 | 黄 | ToS/封号风险已声明；小米配额为硬约束 |

## 4. 必做 Spike

| # | 目标 | 通过标准 | 耗时 |
|---|---|---|---|
| S1 | 注入可用性 + 单次开销 | 无异常；P50/P95 两档 | 0.5 天 |
| S2 | 批处理急停延迟 | 证明哨兵方案 ≤300ms / 原方案不可用 | 0.5 天 |
| S3 | `motionevent` 手势有效性 | 是否被识别为一次拖拽 + 最少分段数 | 0.5 天 |
| S4 | 坐标三向校验 | 一致或不一致的规律 | 0.5 天 |
| S5 | `getevent` 权限与量程 | 可读性 + 归一化系数 | 0.3 天 |
| S6 | scrcpy 集成 | 窗口可定位；`--no-control` 后不注入 | 0.5 天 |
| S7 | 计划任务 + 单实例 | 锁屏可跑；CLI 锁冲突现象 | 0.3 天 |
| S8 | 干净机打包 | 能起、无缺 DLL、≤3s | 0.5 天 |

合计约 3.6 人日，建议写业务代码前完成。

## 5. 规格缺陷

**自相矛盾**

1. 批处理 vs 急停/逐步日志/暂停（B1）。
2. 「v1 无图片资源」 vs 首步屏幕指纹模板截图。
3. 「不做手机端 App」 vs v2 ADBKeyBoard 输入法。
4. 「多指 v1/v2 均不承诺」 vs v3 minitouch 与 scrcpy 控制通道（免 Root 可多指，表述错误）。
5. MVP 仅 0/90 vs 180/270 表。
6. 越界四处不一。
7. 数据目录两处定义。
8. 连点 50ms vs 节拍结论 vs <100ms 二次确认。
9. stayon 与 `--stay-awake` 冲突。
10. `coordMode` 在档案、坐标在脚本。

**验收不可判定**

- KPI-2 绝对坐标下恒真。
- AC-07/AC-17 的「成功/漂移」无定义。
- AC-21「录屏抽检」无判据且录屏默认关闭。
- AC-14 未给允许失败步数。
- KPI-5 无分母。
- FR-2.3「误差 ≤1px」无测量方法。
- AC-19 未绑定登录/睡眠/USB 前提。

**会导致返工的架构决定**

1. 批处理内建在引擎里。
2. `DeviceTransport` 只抽象单动作，无法表达多动作 + 逐步结果 + 可中断。
3. `core` 若 import PySide6，CLI/无 GUI 不可用。
4. scrcpy 只作显示，未留控制通道接入点。
5. 状态机不完整。

## 6. MVP 裁剪建议

必须砍：批处理、曲线/drag、scrcpy 与窗口取点、HUD 可配置项（保留 F12）、定时任务 UI（改命令）、快捷连点、模板数量、`waitStable`、无线调试。

保留：设备发现/授权、档案 + 校准（换验证方式）、截图拾取、tap/longPress/swipe/wait/key/launchApp/group、逐步延时、F12、JSON 存取、JSONL 日志 + 失败截图、`assertPixel`。

砍完后 S1/S2/S5 全可用，S4 靠 `assertPixel` 覆盖，S3 受中文限制，S6 延后。

## 7. 排期判断

偏乐观约 2.5–3.5 倍，真实量级 18–26 人日：

| 阶段 | 规格 | 评审估计 |
|---|---|---|
| M0 骨架 | 0.5 | 2.0 |
| M1 档案/拾取/动作/日志 | 2 | 5–6 |
| M2 分组/策略/急停/存取 | 1.5 | 3–4 |
| M2.5 投屏/连点/HUD/定时 | 1.5 | 4–5 |
| M3 试跑/历史/曲线/waitStable | 1 | 3–4 |
| M4 打包/回归 | 0.5 | 2–3 |
| 合计 | 7 | 19–24（+20% → 23–29） |

建议改写为「MVP 7–9 人日 / v1.0 20–26 人日」。

## 8. 遗留不确定性（只能实测）

1. 目标机型的 `INJECT_EVENTS`、`getevent`、Override 行为。
2. `dumpsys`/`wm` 输出与旋转角、displayId 数量。
3. `motionevent` 在目标 App 上是否被识别为一次手势。
4. `input` 延迟分布（机型 × USB/WiFi）。
5. 熄屏/锁屏/分屏/悬浮窗下的注入行为。
6. scrcpy 在 125%/150% DPI 下的 client rect、letterbox padding。
7. 计划任务在锁屏、混合睡眠、唤醒后的 adb 可用性。
8. PyInstaller 在干净 Win11 的 VC runtime 与杀软误报。
9. 目标 App 是否 `FLAG_SECURE`。
10. 目标 App/游戏是否检测或丢弃注入事件。

---

## 处置结果（写入 SPEC v0.3）

| 编号 | 处置 |
|---|---|
| B1 | 采纳：v1 删除批处理；v2 仅保留哨兵文件方案 |
| H1 | 采纳：曲线/`motionevent` 降 P2 实验；说明版本与手势流限制 |
| H2 | 采纳：注入自检升 P0；补充 MIUI 前置条件与配额说明 |
| H3 | 采纳：删除 `show_touches` 校准；改可逆控件/指针位置 |
| H4 | 采纳：三向一致性硬校验；`-d displayId` |
| H5 | 采纳：开跑前屏幕/锁屏检查；无人值守强制 USB |
| H6 | 采纳：单引擎 + 命名管道投递 |
| H7 | 采纳：计划任务强制 INTERACTIVE_TOKEN + WakeToRun + StartIn |
| H8 | 采纳：`--no-control`；版本 ≥2.0（实测 5.0.1）；v1 不做窗口取点 |
| H9 | 采纳：`assertPixel` 升 P0；KPI 重定义 |
| M1–M12 | 采纳（M1 性能分档、M2 连点降级、M3 归一化、M4 脚本级 coordMode、M5 越界统一、M6 仅竖屏、M7 am start、M8 零副作用探测、M9 目录统一/原子写、M10 常亮单一管理、M11 onedir、M12 状态机补全） |
| L1–L5 | 部分采纳（L2 track-devices、L3 统一 adb、L4 FLAG_SECURE 提示、L5 HUD 预留 0.5 天） |

**未采纳**

- 「第一个版本彻底不做 HUD」：保留极简 HUD（进度 + 暂停 + 急停），因为急停可达性是安全需求，成本约 0.5 天。
- 「彻底删除无线调试」：保留为人工调试功能，但明确标注不用于无人值守。
