# outoclick 构建进度

- 更新：2026-11-22
- 主交付物：**Android 独立 App**（`android/`）
- 仓库：https://github.com/wmoonlq/autoclick

## 已完成

| 模块 | 内容 | 状态 |
|---|---|---|
| Android 工程 | Gradle 8.13 + AGP 8.9.2 + Kotlin 2.0.21，minSdk 26 / compile+target 36 | 完成 |
| 脚本模型 | 点击/长按/滑动/等待，延时、循环、启用开关，JSON 本机存储 | 完成 |
| 手势执行 | `AccessibilityService.dispatchGesture`，10s 超时保护 | 完成 |
| 悬浮控制 | 悬浮球、开始/暂停/停止、状态、前台通知 | 完成 |
| 取点 | 可拖动十字准星，记录点击/滑动起点终点 | 完成 |
| 界面 | 权限引导、脚本列表、步骤编辑、剪贴板导入导出 | 完成 |
| 构建 | `app-debug.apk`（约 3.3 MB） | 完成 |
| 实机验证 | Pixel 5 模拟器 Android 17 / API 37：安装、无障碍手势、悬浮球、取点、运行、暂停/停止 | 通过 |
| 文档 | Android README + 三张截图；PC 原型归档到 `docs/PC-PROTOTYPE.md` | 完成 |

## 验证记录

- `adb install -r app-debug.apk` → Success
- 无障碍服务绑定：`capabilities=32`（可执行手势）
- 悬浮球 + 面板 + 准星取点 → 正常
- 准星记录 → 脚本 JSON 新增 `TAP (540,1170)` → 正常
- 运行 4 步脚本 → 面板显示 `1/1 轮 · 2/4` → 完成
- 暂停/停止按钮 → 状态切换正常
- `logcat -s AndroidRuntime:E` → 无崩溃

## 构建命令

```powershell
$env:JAVA_HOME='D:\JDKSS\Jdks\jdk-21.0.12.1+1'
$env:ANDROID_HOME='E:\AndroidSdk'
cd E:\outoclick\android
E:\gradle\gradle-8.13\bin\gradle.bat assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

## 已知限制

- 固定坐标；改版/换分辨率需重新取点
- 仅竖屏；无图像识别；无录制
- debug 签名，正式分发需自建 keystore
- 厂商省电策略可能杀后台，建议加电池白名单

## 历史

- 2026-11-21：先做了 Windows + ADB 的 PC 原型（`app/`、`SPEC.md`），已归档，不再作为主交付物。
