from __future__ import annotations


class OutoclickError(Exception):
    """Base class for all application errors."""


class DeviceError(OutoclickError):
    """Device communication failed."""


class DeviceNotFound(DeviceError):
    pass


class CapabilityError(OutoclickError):
    """A required device capability is missing (input / motionevent / ...)."""


class ResolutionMismatch(OutoclickError):
    """Profile and runtime resolution are not compatible."""


class OutOfBounds(OutoclickError):
    """Coordinate falls outside the device screen."""


class ScriptValidationError(OutoclickError):
    def __init__(self, issues: list[str]):
        self.issues = issues
        super().__init__("; ".join(issues))


class ScriptVersionError(OutoclickError):
    pass
