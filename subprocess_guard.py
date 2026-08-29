"""Non-interactive subprocess execution with Windows process-tree kill on timeout."""

from __future__ import annotations

import os
import subprocess
import sys
import time
import re
from pathlib import Path


def bind_command_to_interpreter(command: str) -> str:
    """Run with this process's Python so Windows `python` store aliases cannot fail the rerun."""
    stripped = command.strip()
    match = re.match(
        r"^(?:python(?:\d+(?:\.\d+)*)?|py(?:\s+-3(?:\.\d+)?)?)\b",
        stripped,
        flags=re.IGNORECASE,
    )
    if not match:
        return stripped
    return f'"{sys.executable}"{stripped[match.end():]}'


def isolated_env(cwd: str | Path | None = None, extra: dict[str, str] | None = None) -> dict[str, str]:
    """Copy the host environment but force non-interactive, sandbox-local PYTHONPATH."""
    env = os.environ.copy()
    py_paths: list[str] = []
    if cwd:
        root = Path(cwd).resolve()
        py_paths.append(str(root))
        if (root / "src").is_dir():
            py_paths.append(str((root / "src").resolve()))
    env["PYTHONPATH"] = os.pathsep.join(py_paths)
    env["PYTHONUNBUFFERED"] = "1"
    env["CI"] = "1"
    env["DEBIAN_FRONTEND"] = "noninteractive"
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    if extra:
        env.update(extra)
    return env


def kill_process_tree(proc: subprocess.Popen) -> None:
    """Kill *proc* and every descendant. Windows ``kill()`` only stops ``cmd.exe``."""
    if proc.poll() is not None:
        return
    if sys.platform == "win32" and proc.pid:
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(proc.pid)],
            capture_output=True,
            timeout=15,
        )
    try:
        proc.kill()
    except Exception:
        pass


def run_command_isolated(
    command: str,
    *,
    cwd: str | Path,
    timeout_seconds: float = 15,
    env: dict[str, str] | None = None,
) -> tuple[int, str, str, bool, int]:
    """Run *command* with stdin closed.

    Returns ``(exit_code, stdout, stderr, timed_out, duration_ms)``.
    Timeouts always fail with exit code 124 after the process tree is killed.
    """
    start = time.time()
    run_env = env if env is not None else isolated_env(cwd)
    bound = bind_command_to_interpreter(command)
    proc = subprocess.Popen(
        bound,
        cwd=str(cwd),
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        stdin=subprocess.DEVNULL,
        env=run_env,
    )
    timed_out = False
    try:
        out_b, err_b = proc.communicate(timeout=timeout_seconds)
        exit_code = proc.returncode if proc.returncode is not None else 1
    except subprocess.TimeoutExpired:
        timed_out = True
        kill_process_tree(proc)
        try:
            out_b, err_b = proc.communicate(timeout=5)
        except Exception:
            out_b, err_b = b"", b""
        exit_code = 124
    duration_ms = int((time.time() - start) * 1000)
    stdout = (out_b.decode("utf-8", errors="replace") if out_b else "")[-2000:]
    stderr = (err_b.decode("utf-8", errors="replace") if err_b else "")[-2000:]
    if timed_out and "timed out" not in stderr.lower():
        stderr = (
            f"Sandbox verification timed out after {timeout_seconds}s — "
            f"possible infinite loop or hung process.\n{stderr}"
        ).strip()
    return exit_code, stdout, stderr, timed_out, duration_ms
