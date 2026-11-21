from app.core.profile import build_profile, calibrate_point, validate_for_run
from app.core.transport import MOCK_SWITCH_CENTER, MockTransport


def test_build_profile_from_mock():
    transport = MockTransport()
    profile, report = build_profile(transport)
    assert report.ok, report.issues
    assert profile.ref_size == (1080, 2400)
    assert profile.serial == transport.serial
    assert profile.orientation == 0
    assert not profile.calibrated


def test_calibration_detects_toggle_change():
    transport = MockTransport()
    result = calibrate_point(transport, *MOCK_SWITCH_CENTER, wait_ms=10, min_changed_ratio=0.02)
    assert result.ok, result.detail
    assert transport.switch_on is True


def test_validate_for_run_ok():
    transport = MockTransport()
    profile, _ = build_profile(transport)
    profile.calibrated = True
    report = validate_for_run(profile, transport)
    assert report.ok, report.issues
