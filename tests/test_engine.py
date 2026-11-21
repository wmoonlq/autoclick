import json
import time

from app.core.engine import Engine, EngineEvent
from app.core.logger import RunLogger
from app.core.profile import build_profile
from app.core.script_model import demo_script, new_step, Script
from app.core.transport import MockTransport


def make(monkeypatch, tmp_path, transport=None, script_builder=None):
    monkeypatch.setenv("OUTOCLICK_DATA", str(tmp_path))
    transport = transport or MockTransport()
    profile, report = build_profile(transport)
    profile.calibrated = True
    assert report.ok, report.issues
    script = script_builder(profile) if script_builder else demo_script(profile)
    return transport, profile, script


def run(transport, profile, script, timeout=15):
    events: list[EngineEvent] = []
    logger = RunLogger(script.name, script.id)
    engine = Engine(transport, script, profile, logger=logger, on_event=events.append)
    engine.start()
    engine.join(timeout)
    end = [e for e in events if e.kind == "run_end"]
    assert end, "engine did not emit run_end"
    return engine, logger, events, end[0]


def test_demo_script_runs_ok(monkeypatch, tmp_path):
    transport, profile, script = make(monkeypatch, tmp_path)
    engine, logger, events, end = run(transport, profile, script)
    assert end.payload["status"] == "ok", end.payload
    summary = end.payload["summary"]
    assert summary["fail"] == 0
    assert summary["assertPass"] == 2
    assert transport.switch_on is True
    assert 0 in transport.rows_hit
    assert transport.done is True
    log = logger.jsonl_path.read_text(encoding="utf-8").strip().splitlines()
    assert len(log) >= 6
    assert json.loads(log[0])["event"] == "run_start"
    assert json.loads(log[-1])["event"] == "run_end"


def test_stop_interrupts_wait(monkeypatch, tmp_path):
    def builder(profile):
        return Script(name="long wait", steps=[new_step("wait", label="wait", ms=5000)],
                      device_profile_id=profile.id)

    transport, profile, script = make(monkeypatch, tmp_path, script_builder=builder)
    events = []
    logger = RunLogger(script.name, script.id)
    engine = Engine(transport, script, profile, logger=logger, on_event=events.append)
    started = time.monotonic()
    engine.start()
    time.sleep(0.3)
    engine.stop()
    engine.join(5)
    elapsed = time.monotonic() - started
    end = [e for e in events if e.kind == "run_end"][0]
    assert end.payload["status"] == "stopped"
    assert elapsed < 3.0


def test_assert_failure_continue(monkeypatch, tmp_path):
    def builder(profile):
        script = Script(name="assert fail", device_profile_id=profile.id)
        step = new_step("assertPixel", 540, 960, label="expect red")
        step["color"] = "#FF0000"
        step["tolerance"] = 0
        step["retries"] = 0
        step["onError"] = "continue"
        script.steps = [step, new_step("tap", 540, 2170, label="continue")]
        return script

    transport, profile, script = make(monkeypatch, tmp_path, script_builder=builder)
    engine, logger, events, end = run(transport, profile, script)
    summary = end.payload["summary"]
    assert end.payload["status"] == "failed"
    assert summary["fail"] == 1
    assert summary["ok"] == 1
    assert transport.done is True
    shots = list(logger.dir.glob("fail_*.png"))
    assert shots


def test_invalid_coordinate_aborts_before_any_tap(monkeypatch, tmp_path):
    def builder(profile):
        return Script(name="bad", steps=[new_step("tap", 5000, 5000)],
                      device_profile_id=profile.id)

    transport, profile, script = make(monkeypatch, tmp_path, script_builder=builder)
    engine, logger, events, end = run(transport, profile, script)
    assert end.payload["status"] == "error"
    assert transport.tap_count == 0
