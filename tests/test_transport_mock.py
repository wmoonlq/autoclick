from app.core.imageops import load_image, png_size, sample_pixel
from app.core.transport import (
    MOCK_H,
    MOCK_ROW_CENTER,
    MOCK_SWITCH_CENTER,
    MOCK_W,
    ROW_HIT,
    SWITCH_ON,
    MockTransport,
)


def test_mock_screen_size():
    t = MockTransport()
    data = t.screencap()
    assert png_size(data) == (MOCK_W, MOCK_H)
    img = load_image(data)
    assert img.size == (MOCK_W, MOCK_H)


def test_mock_tap_toggles_switch():
    t = MockTransport()
    before = sample_pixel(load_image(t.screencap()), *MOCK_SWITCH_CENTER)
    t.tap(*MOCK_SWITCH_CENTER)
    after = sample_pixel(load_image(t.screencap()), *MOCK_SWITCH_CENTER)
    assert before != after
    assert after == SWITCH_ON


def test_mock_row_tap():
    t = MockTransport()
    t.tap(*MOCK_ROW_CENTER)
    assert 0 in t.rows_hit
    assert sample_pixel(load_image(t.screencap()), *MOCK_ROW_CENTER) == ROW_HIT


def test_mock_reports():
    t = MockTransport()
    assert t.injection_check().ok
    assert t.awake() is True
    assert t.locked() is False
    assert t.wm_size()[:2] == (MOCK_W, MOCK_H)
    assert t.rotation() == 0


def test_input_usage_probe():
    t = MockTransport()
    result = t.shell("input")
    assert "tap" in result.text


def test_launch_app_resolution():
    t = MockTransport()
    t.shell("cmd package resolve-activity --brief com.demo.app")
    result = t.launch_app("com.demo.app")
    assert result.ok
