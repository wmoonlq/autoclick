from __future__ import annotations

import json
import socket
import sys
import threading
import time
from typing import Any, Callable

HOST = "127.0.0.1"
PORT = 47821
DEFAULT_TIMEOUT = 3.0


class IpcError(RuntimeError):
    pass


def send_command(command: dict[str, Any], timeout: float = DEFAULT_TIMEOUT) -> dict[str, Any] | None:
    """Send one JSON command to a running GUI; None when no instance listens."""
    try:
        with socket.create_connection((HOST, PORT), timeout=min(timeout, 1.0)) as sock:
            sock.settimeout(timeout)
            sock.sendall((json.dumps(command, ensure_ascii=False) + "\n").encode("utf-8"))
            buf = b""
            while not buf.endswith(b"\n"):
                chunk = sock.recv(4096)
                if not chunk:
                    break
                buf += chunk
        if not buf.strip():
            return None
        return json.loads(buf.decode("utf-8", "replace"))
    except (ConnectionRefusedError, OSError, TimeoutError):
        return None
    except json.JSONDecodeError:
        return None


class IpcServer:
    """Loopback control socket: enforces a single engine owner (SPEC 7, FR-5.10)."""

    def __init__(self, handler: Callable[[dict[str, Any]], dict[str, Any]]):
        self.handler = handler
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # SO_REUSEADDR on Windows allows a second listener to steal the port;
        # use the exclusive option so the single-instance guarantee holds.
        if sys.platform == "win32" and hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        else:
            self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    def start(self) -> None:
        self._sock.bind((HOST, PORT))
        self._sock.listen(4)
        self._sock.settimeout(0.5)
        self._thread = threading.Thread(target=self._loop, name="outoclick-ipc", daemon=True)
        self._thread.start()

    def close(self) -> None:
        self._stop.set()
        try:
            self._sock.close()
        except OSError:
            pass

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                conn, _ = self._sock.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                conn.settimeout(5.0)
                buf = b""
                while not buf.endswith(b"\n"):
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    buf += chunk
                if not buf.strip():
                    continue
                command = json.loads(buf.decode("utf-8", "replace"))
                response = self.handler(command) or {"ok": True}
            except Exception as exc:  # noqa: BLE001
                response = {"ok": False, "error": str(exc)}
            try:
                conn.sendall((json.dumps(response, ensure_ascii=False) + "\n").encode("utf-8"))
            except OSError:
                pass
            finally:
                try:
                    conn.close()
                except OSError:
                    pass


def wait_for_run(timeout: float = 600.0, after_token: int = 0) -> dict[str, Any] | None:
    """Poll a running GUI until the engine returns to Idle after a new run."""
    deadline = time.monotonic() + timeout
    misses = 0
    while time.monotonic() < deadline:
        status = send_command({"cmd": "status"})
        if not status:
            misses += 1
            if misses >= 5:
                return None
            time.sleep(0.5)
            continue
        misses = 0
        last = status.get("last")
        if (
            status.get("ok")
            and status.get("state") == "Idle"
            and isinstance(last, dict)
            and int(last.get("token", 0)) > after_token
        ):
            return last
        time.sleep(0.5)
    return None
