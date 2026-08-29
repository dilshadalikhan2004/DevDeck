"""Temporary local-project sandbox for repair preflight verification."""

from dataclasses import dataclass
from pathlib import Path
import shutil
import tempfile

from subprocess_guard import isolated_env, run_command_isolated


@dataclass(frozen=True)
class SandboxResult:
    passed: bool
    exit_code: int | None
    stdout: str
    stderr: str
    timed_out: bool = False


class SandboxRunner:
    """Runs a candidate repair in a disposable copy of a trusted project."""

    _EXCLUDED_NAMES = {".git", "__pycache__", "venv", ".venv", ".devdeck"}

    def __init__(self, project_root: str | Path, timeout_seconds: float = 15):
        self.project_root = Path(project_root).resolve(strict=True)
        self.timeout_seconds = timeout_seconds
        self.last_sandbox_path: Path | None = None

    @classmethod
    def _ignore(cls, _directory: str, names: list[str]) -> set[str]:
        return {
            name for name in names
            if name in cls._EXCLUDED_NAMES
            or name.endswith(".bak")
            or name.endswith(".devdeck-snapshot")
        }

    @staticmethod
    def _tail(value: str | bytes | None) -> str:
        if isinstance(value, bytes):
            value = value.decode("utf-8", errors="replace")
        return (value or "")[-2000:]

    def verify(self, relative_file: str, candidate_content: str, command: str) -> SandboxResult:
        sandbox = Path(tempfile.mkdtemp(prefix="devdeck_sandbox_"))
        self.last_sandbox_path = sandbox
        try:
            shutil.copytree(self.project_root, sandbox, dirs_exist_ok=True, ignore=self._ignore)
            candidate = (sandbox / relative_file).resolve()
            if sandbox not in candidate.parents or not candidate.is_file():
                return SandboxResult(False, None, "", "sandbox target is outside copied project")
            candidate.write_text(candidate_content, encoding="utf-8")
            exit_code, stdout, stderr, timed_out, _ = run_command_isolated(
                command,
                cwd=sandbox,
                timeout_seconds=self.timeout_seconds,
                env=isolated_env(sandbox),
            )
            if timed_out:
                return SandboxResult(False, 124, self._tail(stdout), self._tail(stderr) or "sandbox timeout", True)
            return SandboxResult(
                exit_code == 0,
                exit_code,
                self._tail(stdout),
                self._tail(stderr),
            )
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)
