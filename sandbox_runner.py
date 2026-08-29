"""Temporary local-project sandbox for repair preflight verification."""

from dataclasses import dataclass
from pathlib import Path
import shutil
import subprocess
import tempfile


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
            result = subprocess.run(
                command,
                shell=True,
                cwd=sandbox,
                capture_output=True,
                text=True,
                timeout=self.timeout_seconds,
            )
            return SandboxResult(
                result.returncode == 0,
                result.returncode,
                self._tail(result.stdout),
                self._tail(result.stderr),
            )
        except subprocess.TimeoutExpired as error:
            return SandboxResult(False, None, self._tail(error.stdout), "sandbox timeout", True)
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)
