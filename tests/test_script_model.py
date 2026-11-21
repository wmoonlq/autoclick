import pytest

from app.core.errors import ScriptVersionError
from app.core.profile import DeviceProfile
from app.core.script_model import Script, demo_script, new_step


def profile():
    return DeviceProfile(
        id="dp_test",
        serial="mock",
        ref_width=1080,
        ref_height=2400,
        calibrated=True,
    )


def test_demo_script_valid():
    script = demo_script(profile())
    issues = script.validate(profile(), for_run=True)
    errors = [i for i in issues if i.level == "error"]
    assert not errors, [str(e) for e in errors]


def test_out_of_bounds_is_save_error():
    script = demo_script(profile())
    script.steps.append(new_step("tap", 2000, 100))
    issues = script.validate(profile())
    assert any("超出档案范围" in i.message for i in issues)


def test_non_ascii_text_is_warning_on_save_error_on_run():
    script = demo_script(profile())
    step = new_step("text", label="中文")
    step["content"] = "你好"
    script.steps.append(step)
    save_issues = script.validate(profile(), for_run=False)
    run_issues = script.validate(profile(), for_run=True)
    assert any(i.level == "warning" and "ASCII" in i.message for i in save_issues)
    assert any(i.level == "error" and "ASCII" in i.message for i in run_issues)


def test_unknown_major_rejected():
    with pytest.raises(ScriptVersionError):
        Script.from_dict({"schemaVersion": 99, "steps": []})


def test_unknown_fields_ignored():
    script = Script.from_dict({"schemaVersion": 1, "steps": [], "futureField": 1})
    assert script.warnings and "futureField" in script.warnings[0]


def test_group_expansion_counts():
    script = demo_script(profile())
    group = new_step("group", label="g", repeat=3)
    group["steps"] = [new_step("tap", 10, 10), new_step("wait")]
    script.steps.append(group)
    assert script.expanded_count() == len(demo_script(profile()).steps) + 6


def test_duplicate_step_id_detected():
    script = demo_script(profile())
    script.steps.append(dict(script.steps[1]))
    issues = script.validate(profile())
    assert any("id 重复" in i.message for i in issues)


def test_roundtrip_dict():
    script = demo_script(profile())
    restored = Script.from_dict(script.to_dict())
    assert restored.id == script.id
    assert len(restored.steps) == len(script.steps)
    assert restored.coord_mode == "absolute"
