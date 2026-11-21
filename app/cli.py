from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from .core import ipc
from .core.config import ConfigStore
from .core.device_manager import DeviceManager, DeviceEntry
from .core.engine import Engine, EngineEvent
from .core.errors import OutoclickError
from .core.logger import RunLogger
from .core.paths import ensure_dirs, scripts_dir
from .core.profile import ProfileStore, build_profile
from .core.script_model import Script, demo_script, list_scripts, unique_path
from .core.transport import MockTransport


def _pick_entry(manager: DeviceManager, serial: str | None, mock: bool) -> DeviceEntry:
    entries = manager.list_devices(include_mock=True)
    if mock:
        for e in entries:
            if e.mock:
                return e
        raise SystemExit("模拟设备不可用")
    usable = [e for e in entries if not e.mock and e.info.usable]
    if serial:
        for e in usable:
            if e.info.serial == serial:
                return e
        raise SystemExit(f"未找到设备：{serial}")
    if not usable:
        raise SystemExit("没有可用的 Android 设备（adb devices 为空或未授权）")
    return usable[0]


def cmd_list_devices(manager: DeviceManager) -> int:
    entries = manager.list_devices()
    if not entries:
        print("没有可用设备")
        return 1
    for entry in entries:
        info = entry.info
        tag = "MOCK" if entry.mock else ("OK" if info.usable else info.state.upper())
        print(f"[{tag:>12}] {info.label}  android={info.android or '?'}")
    if not manager.adb_ready:
        print("\nadb: 未找到。安装 Android Platform-Tools 后重试，或在设置中指定 adb 路径。")
    elif manager.last_error:
        print(f"\nadb: {manager.last_error}")
    return 0


def cmd_selftest(manager: DeviceManager, serial: str | None, mock: bool) -> int:
    entry = _pick_entry(manager, serial, mock)
    transport = manager.transport_for(entry)
    print(f"设备：{entry.info.label}")
    info = transport.device_info()
    print(f"  机型     : {info.model}  Android {info.android}")
    cap = transport.injection_check()
    print(f"  注入能力 : {'OK' if cap.ok else 'FAIL'}  {cap.detail}")
    print(f"  SDK      : {cap.sdk}")
    print(f"  唤醒状态 : {transport.awake()}")
    print(f"  锁屏状态 : {transport.locked()}")
    wm_w, wm_h, raw = transport.wm_size()
    print(f"  wm size  : {wm_w}x{wm_h}  ({raw})")
    print(f"  density  : {transport.wm_density()}")
    shot = transport.screencap()
    from .core.imageops import png_size

    sw, sh = png_size(shot)
    print(f"  截图尺寸 : {sw}x{sh}")
    print(f"  旋转     : {transport.rotation()}")
    consistent = (wm_w, wm_h) == (sw, sh)
    print(f"  三向一致 : {'OK' if consistent else 'FAIL（禁止运行）'}")
    return 0 if cap.ok and consistent else 2


def _load_script(arg: str) -> Script:
    path = Path(arg)
    if not path.exists():
        for candidate in list_scripts(scripts_dir()):
            if arg in (candidate.stem, candidate.name):
                path = candidate
                break
        else:
            raise SystemExit(f"脚本不存在：{arg}")
    return Script.load(path)


def _profile_for(script: Script, transport) -> "object":
    store = ProfileStore()
    profiles = store.load()
    for profile in profiles:
        if profile.id == script.device_profile_id:
            return profile
    profile, _report = build_profile(transport)
    profile.calibrated = transport.kind == "mock"
    store.upsert(profile)
    script.device_profile_id = profile.id
    if script.path:
        script.save(script.path)
    return profile


def cmd_run(manager: DeviceManager, script_arg: str, serial: str | None, mock: bool, quiet: bool) -> int:
    script = _load_script(script_arg)

    existing = ipc.send_command({"cmd": "ping"})
    if existing and existing.get("ok"):
        print("检测到已打开的 outoclick 窗口，投递任务…")
        before = ipc.send_command({"cmd": "status"}) or {}
        token = int(before.get("token", 0) or 0)
        response = ipc.send_command({"cmd": "run", "script": str(script.path or script_arg)})
        if not response or not response.get("ok"):
            print("投递失败：", response)
            return 1
        summary = ipc.wait_for_run(timeout=3600, after_token=token)
        if not summary:
            print("等待运行结果超时")
            return 1
        print(f"结果：{summary.get('state')}  成功 {summary.get('ok')} / 失败 {summary.get('fail')}")
        return 0 if summary.get("state") == "ok" else 1

    entry = _pick_entry(manager, serial, mock)
    transport = manager.transport_for(entry)
    profile = _profile_for(script, transport)
    logger = RunLogger(script.name, script.id)
    print(f"运行：{script.name}  设备：{entry.info.label}  日志：{logger.dir}")

    def on_event(event: EngineEvent) -> None:
        if quiet:
            return
        if event.kind == "step_end":
            p = event.payload
            mark = {"ok": "OK  ", "fail": "FAIL", "skip": "SKIP"}.get(p.get("status"), "?")
            detail = p.get("error") or p.get("detail") or ""
            print(f"  [{mark}] {p.get('stepId'):<8} {p.get('type'):<12} {p.get('latencyMs')}ms {detail}"[:160])
        elif event.kind == "log":
            print(f"  ! {event.payload.get('message')}")
        elif event.kind == "run_end":
            s = event.payload["summary"]
            print(
                f"结束：{event.payload['status']}  成功 {s['ok']} / 失败 {s['fail']} / 跳过 {s['skip']}  "
                f"耗时 {s['elapsedMs'] / 1000:.1f}s  校验 {s['assertPass']}/{s['assertTotal']}"
            )

    engine = Engine(transport, script, profile, logger=logger, on_event=on_event)
    engine.start()
    engine.join()
    return 0 if engine.state.value == "Idle" and not engine.last_error else 1


def cmd_make_demo() -> int:
    ensure_dirs()
    transport = MockTransport()
    profile, report = build_profile(transport)
    profile.calibrated = True
    ProfileStore().upsert(profile)
    script = demo_script(profile)
    path = unique_path(scripts_dir(), script.name)
    script.save(path)
    print(f"已生成演示脚本：{path}")
    if not report.ok:
        print("注意：档案一致性检查未通过：", "; ".join(report.issues))
    return 0


def cmd_status() -> int:
    status = ipc.send_command({"cmd": "status"})
    if not status:
        print("GUI 未运行（无实例监听 127.0.0.1:47821）")
        return 1
    print(f"GUI 运行中：state={status.get('state')}  script={status.get('script')}")
    last = status.get("last")
    if last:
        print(
            f"上次运行：{last.get('state')}  成功 {last.get('ok')} / 失败 {last.get('fail')}  "
            f"耗时 {last.get('elapsedMs', 0) / 1000:.1f}s"
        )
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="outoclick", description="Android 固定坐标自动点击器（MVP）")
    parser.add_argument("--adb", help="adb.exe 路径")
    parser.add_argument("--mock", action="store_true", help="使用内置模拟设备")
    parser.add_argument("--device", help="设备 serial")
    parser.add_argument("--list-devices", action="store_true", help="列出设备")
    parser.add_argument("--selftest", action="store_true", help="设备自检")
    parser.add_argument("--run", metavar="SCRIPT", help="运行脚本（路径 / 文件名 / id）")
    parser.add_argument("--make-demo", action="store_true", help="生成模拟设备演示脚本")
    parser.add_argument("--status", action="store_true", help="查看 GUI/运行状态")
    parser.add_argument("--stop", action="store_true", help="请求 GUI 停止当前运行")
    parser.add_argument("--quiet", action="store_true", help="只输出结果")
    return parser


def _setup_console() -> None:
    """Force UTF-8 on legacy Windows consoles (GBK codepage garbles Chinese)."""
    if sys.platform == "win32":
        try:
            import ctypes

            ctypes.windll.kernel32.SetConsoleOutputCP(65001)
            ctypes.windll.kernel32.SetConsoleCP(65001)
        except Exception:  # noqa: BLE001
            pass
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            pass


def main(argv: list[str] | None = None) -> int:
    _setup_console()
    ensure_dirs()
    args = build_parser().parse_args(argv)
    config = ConfigStore().load()
    manager = DeviceManager(args.adb or config.adb_path)

    if args.list_devices:
        return cmd_list_devices(manager)
    if args.selftest:
        return cmd_selftest(manager, args.device, args.mock)
    if args.make_demo:
        return cmd_make_demo()
    if args.status:
        return cmd_status()
    if args.stop:
        response = ipc.send_command({"cmd": "stop"})
        print("已请求停止" if response and response.get("ok") else "GUI 未运行")
        return 0 if response else 1
    if args.run:
        return cmd_run(manager, args.run, args.device, args.mock, args.quiet)

    build_parser().print_help()
    return 0


if __name__ == "__main__":
    sys.exit(main())
