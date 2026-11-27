# Android v1 测试策略与 TDD 方案

- 日期：2026-11-27
- 适用版本：outoclick Android `0.7.x`
- 基线：`产品功能示意图/数据实体建模.md`（v4）、`产品功能示意图/测试用例-v1.md`
- 目标：用可判定证据替代“看起来能用”，先把两个老问题锁死：
  1. **点击到底有没有落到目标 App**（不是只看 `ok_count`）；
  2. **方向/校准是否还会误判**。

---

## 1. 测试原则（TDD）

每个 bug / 新能力都走同一循环：

```
Red    先写一个可复现、可判定的失败测试（单测 / 探针 CSV / dumpsys 断言）
Green  用最小改动让它通过
Refactor 清理代码，保持测试全绿
```

约束：

- **没有测试证据，不宣布修复**。
- 点击类问题必须以探针 App 的 CSV 为准，不以截图或 `ok_count` 为准。
- 方向类问题必须能复现“绑定时方向”和“运行时方向”的差异，不能只靠肉眼。
- 每个用例记录：命令、CSV、截图、logcat、dumpsys、设备信息。
- 先跑 P0，再跑 P1；P0 不通过不进入发版。

---

## 2. 测试分层

| 层级 | 内容 | 工具 | 运行环境 |
|---|---|---|---|
| L1 单元测试 | 时间轴、随机化、方向策略、快照/校验、纯逻辑 | JUnit | JVM，秒级 |
| L2 组件/集成 | SQLite 仓库、迁移、配额/级联、状态机、会话对账 | AndroidJUnit / adb + sqlite3 | 模拟器/真机 |
| L3 端到端 | 真实无障碍注入、探针 CSV、悬浮球、目标守卫、生命周期 | 探针 App + adb + PowerShell | 真机优先 |
| L4 兼容/探索 | Android 7.1/10/13/14、厂商保活、锁屏/息屏、通知 | 真机/云手机 | 设备矩阵 |

测试金字塔：L1 多、L2 适中、L3 少而关键、L4 按版本回归。

---

## 3. 环境与设备矩阵

| 项 | 值 |
|---|---|
| 主测设备 | 用户提供的真机（型号/Android 版本/分辨率待登记） |
| 快速迭代 | Pixel 5 模拟器 Android 17 / API 37 |
| 兼容 | Android 7.1（云手机/旧机）、10、13、14 |
| 主机 | Windows + JDK 21 + Android SDK 36 + Gradle 8.13 + adb |
| 连接 | USB 优先；无线调试只用于人工探索，不作为时序测试 |

设备登记（测试前填写）：

```
型号：
Android 版本 / API：
ROM / 厂商：
分辨率 / density：
是否 Root / 云手机：
USB 调试：是/否
无障碍受限设置：已允许/未允许
电池优化：已白名单/未
锁屏：无/有（测试期间关闭）
```

---

## 4. 探针 App（真实点击证据）

模块：`android/probe`，包名 `com.outoclick.probe`。

能力：

- 全屏 3×3 格子（A1..C3），每个格子接收真实触摸/无障碍注入事件。
- 每次 `DOWN / UP / CANCEL` 写入 CSV：
  - `elapsed_ms`：探针收到事件的墙钟时间（`elapsedRealtime`）
  - `event_time`：`MotionEvent.eventTime`
  - `action` / `cell` / `x` / `y` / `down_time`
  - `duration_ms`：探针观察到的按住时长
  - `rotation` / `width` / `height`
- 状态栏显示：当前 `rotation`、`Configuration.orientation`、屏幕尺寸、CSV 路径。
- 控件：清空、刷新、竖屏、横屏、传感器、CSV 路径。
- 底部输入框：验证 Gboard 弹出时悬浮球是否还在、会话是否误暂停。

CSV 路径：

```
/sdcard/Android/data/com.outoclick.probe/files/events.csv
```

拉取：

```powershell
adb pull /sdcard/Android/data/com.outoclick.probe/files/events.csv
```

判定口径：

- 点击次数 = CSV 中 `UP` 行数。
- 落点误差 ≤ 1px（关闭抖动时）。
- 时长误差 ≤ 15ms（press 60–90ms 档）。
- 暂停/停止后不得新增 `UP` 行（允许最多 1 个在途动作）。

---

## 5. 测试数据

用调试种子/手工创建固定方案（真机首轮可手工建，稳定后脚本化）：

| 方案 | 内容 | 用途 |
|---|---|---|
| P1 | A1/B2/C3 各 1 次，press 75ms，pointGap 1000ms，loop 3 | 顺序、点位间隔、轮次 |
| P2 | A1 点击 3 次，clickGap 120ms，press 75ms，loop 1 | 连击时序 |
| P3 | 四个角 + 中心点，jitter 开/关 | 边界、抖动 clamp |
| P4 | 目标应用 = probe，loop 99 | 暂停/继续/长按停止/看门狗 |
| P5 | 错误方向：绑定竖屏后把 probe 切横屏 | 方向校准 |
| P6 | 目标应用 = 另一个普通 App | 前台守卫 |

所有时序用例关闭或固定 jitter；抖动用例单独统计分布。

---

## 6. 核心测试矩阵

### 6.1 方向（老问题）

| ID | 用例 | 步骤 | 通过标准 |
|---|---|---|---|
| ORI-01 | 未校准 | 清除目标应用校准值 | `UNCALIBRATED`，提示校准 |
| ORI-02 | 竖屏绑定 + 竖屏运行 | 绑定后直接运行 | 允许 |
| ORI-03 | 竖屏绑定 + 横屏运行 | 把 probe 切横屏 | `ROTATION` 拒绝 |
| ORI-04 | 比例微变 | 1080×2340 → 1080×2280 | 5% 容差内允许 |
| ORI-05 | 比例大变 | 1080×2340 → 1080×1600 | `DISPLAY` 拒绝 |
| ORI-06 | 180° 倒置 | rotation 0 → 2 | `ROTATION_180` 拒绝 |
| ORI-10 | 主页面绑定 + 横屏目标 | 在 outoclick 主页面绑定 probe，再切 probe 横屏 | 目标前台自动校准，允许运行 |
| ORI-11 | 主动旋转后校准 | 运行前把 probe 转横屏 → 边栏「校准方向」→ 运行 | 校准后允许 |
| ORI-12 | 旧数据校准 | 0.7.0 前绑定的方案 | 校准一次后不再误报 |

### 6.2 点击/时序（老问题）

| ID | 用例 | 步骤 | 通过标准 |
|---|---|---|---|
| CLICK-01 | 单点单击 | P1 跑 1 轮 | CSV 1 个 UP，落点 A1 ±1px，duration 60–90ms |
| CLICK-02 | 连击 | P2 | 3 个 UP，clickGap 120±15ms |
| CLICK-03 | 点位间隔 | P1 单轮 | B2 相对 A1 的 down 间隔 1075±15ms |
| CLICK-04 | 多轮 | P1 loop 3 | 9 个 UP，顺序 A1→B2→C3 重复 |
| CLICK-05 | 工具条启动第一下 | 编辑工具条点「开始运行」 | CSV 第一下落在目标，不落到自己的 overlay |
| CLICK-06 | 悬浮球启动第一下 | 停止状态单击悬浮球 | 同上 |
| CLICK-07 | 暂停不补点 | 运行中暂停 5s | 暂停期间无新 UP；继续后从下一步继续 |
| CLICK-08 | 长按停止 | 运行中长按悬浮球 | 2s 内无新 UP，会话 STOPPED |

### 6.3 悬浮球/边栏/遮挡

| ID | 用例 | 通过标准 |
|---|---|---|
| BALL-01 | 主页面/桌面不显示球 | `dumpsys window` 无 outoclick overlay |
| BALL-02 | 目标应用前台显示球 | 仅 1 个球窗口 |
| BALL-03 | 运行中单击暂停 / 暂停单击继续 | 会话 PAUSED/RUNNING，CSV 无暂停期点击 |
| BALL-04 | 停止状态长按边栏 | 3×2 格子出现；格子可点 |
| BALL-05 | 编辑边栏 | 添加/复用/删除/保存/开始/完成可用 |
| BALL-06 | 输入法弹出 | 球不消失、会话不误暂停 |
| TOUCH-01 | 主页面触摸 | 服务运行中，主页面滚动/点按正常 |

### 6.4 目标守卫/生命周期

| ID | 用例 | 通过标准 |
|---|---|---|
| GUARD-01 | 运行中按 Home | `PAUSED / TARGET_MISMATCH`，球隐藏 |
| GUARD-02 | 运行中打开系统设置 | `STOPPED / TARGET_MISMATCH` |
| GUARD-03 | 并发启动 | 第二个方案被拒绝 |
| LIFE-01 | 运行中 force-stop | 重启后 `STOPPED / KILLED` |
| LIFE-02 | 息屏/锁屏 | PAUSED，无点击落到锁屏 |
| LIFE-03 | 通知暂停/继续/停止 | 会话语义正确 |
| PERM-01 | 拒绝通知权限 | 允许运行，提示用球停止 |
| PERM-02 | 未开无障碍/悬浮窗 | 阻止启动并引导 |
| DB-01 | 杀进程重启 | 方案/步骤/会话持久化 |
| DB-02 | 删除被引用按钮 | 二次确认 + 级联重排 |

---

## 7. 执行命令

```powershell
# L1 单测
cd E:\outoclick\android
E:\gradle\gradle-8.13\bin\gradle.bat :app:testDebugUnitTest --offline

# 构建 app + 探针
E:\gradle\gradle-8.13\bin\gradle.bat :app:assembleDebug :probe:assembleDebug --offline

# 安装
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r probe\build\outputs\apk\debug\probe-debug.apk

# 打开探针
adb shell am start -n com.outoclick.probe/.ProbeActivity

# 拉 CSV
adb pull /sdcard/Android/data/com.outoclick.probe/files/events.csv

# 证据
adb logcat -d > logcat.txt
adb shell dumpsys window windows > windows.txt
adb shell dumpsys accessibility > accessibility.txt
```

---

## 8. 证据归档

每次测试建立：

```
test-runs/YYYYMMDD-HHMM/
  device.txt          # 型号/Android/分辨率
  events.csv          # 探针原始事件
  screenshots/*.png
  logcat.txt
  windows.txt
  result.md           # 用例 ID、通过/失败、差异
```

`test-runs/` 默认不提交（加入 `.gitignore`），只提交汇总报告。

---

## 9. 真机交接清单

测试前请提供/完成：

1. 手机型号、Android 版本、ROM、分辨率/密度。
2. 开启开发者选项 + USB 调试；USB 连接本机，`adb devices` 能看到设备并授权。
3. 允许安装未知来源；安装 outoclick + probe。
4. 授予悬浮窗、通知；开启无障碍（Android 13+ 先在应用信息里「允许受限设置」）。
5. 关闭锁屏/自动息屏（或设为最长），关闭电池优化；测试期间保持亮屏。
6. 说明“老问题”的具体表现：是点击没反应、点偏、方向报错、球消失，还是主页面卡触摸；最好给出大致时间点，便于对 logcat。
7. 如果是云手机/远程设备，说明控制方式（ADB 是否通、能否安装 APK、能否拉文件）。

---

## 10. 第一轮 TDD 计划（本轮先做）

1. `OrientationPolicy` 纯逻辑 + 8 条 JVM 单测（已加入 `OrientationPolicyTest`）。
2. `Guards` 改为调用 `OrientationPolicy`，保证方向判定不再散落在 Android 代码里。
3. 探针 App 模块（已加入 `android/probe`），验证：
   - CSV 能记录 DOWN/UP、坐标、时长；
   - 手动 `adb input tap` 的落点/时长可读；
   - 竖屏/横屏/传感器切换可用。
4. 真机到位后按顺序跑：
   - `CLICK-01/05/06`（先证明点击真的到了 probe）
   - `ORI-10/11/12`（方向校准）
   - `BALL-01/04/06`（显示边界与边栏）
   - `GUARD-01`、`LIFE-01`
5. 任何失败先补测试，再改代码；不直接“猜修”。

---

## 11. 退出标准

- P0 用例全部通过，且有 CSV/截图/dumpsys 证据。
- 点击落点误差 ≤1px、时序误差 ≤15ms（关闭抖动）。
- 方向：绑定/运行/校准三条路径都可复现。
- 主页面无 overlay，触摸正常；目标应用前台球可见。
- 暂停/继续/停止、force-stop 对账、通知控制全部通过。
- 兼容矩阵至少覆盖用户真机 + 一个旧版本 Android。

---

## 12. 已知风险

- 无障碍注入在部分系统页面/安全键盘/支付页可能被拒绝。
- 厂商保活策略差异大，后台/息屏行为必须在真机验证。
- 云手机/模拟器的 `input` 时序与真机不同，性能基线分开记录。
- `dumpsys`/UIAutomator 对 overlay 的可见性有限，必要时以探针 CSV + 截图为主。
