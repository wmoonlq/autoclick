import pytest

from app.core.coords import (
    check_aspect,
    from_percent,
    to_device,
    to_percent,
)
from app.core.errors import OutOfBounds, ResolutionMismatch


def test_same_resolution_is_identity():
    assert to_device(540, 1200, 1080, 2400, 1080, 2400) == (540, 1200)


def test_scale_down_720x1600():
    assert to_device(540, 1200, 1080, 2400, 720, 1600) == (360, 800)


def test_scale_up_rounds():
    assert to_device(1, 1, 720, 1600, 1080, 2400) == (2, 2)


def test_aspect_mismatch_rejected():
    with pytest.raises(ResolutionMismatch):
        to_device(100, 100, 1080, 2400, 1080, 1920)


def test_negative_coordinate_rejected():
    with pytest.raises(OutOfBounds):
        to_device(-1, 10, 1080, 2400, 1080, 2400)


def test_out_of_bounds_never_clamped():
    with pytest.raises(OutOfBounds):
        to_device(1080, 100, 1080, 2400, 1080, 2400)


def test_percent_roundtrip():
    rx, ry = to_percent(540, 1200, 1080, 2400)
    assert (rx, ry) == (0.5, 0.5)
    assert from_percent(rx, ry, 1080, 2400) == (540, 1200)


def test_check_aspect_tolerance():
    assert check_aspect(1080, 2400, 540, 1200) == 0.0
    with pytest.raises(ResolutionMismatch):
        check_aspect(1000, 2000, 1080, 2400)
