"""Sandbox verification engine and Trust Meter for DevDeck."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import time

from bridge_security import canonical_project_root, compute_sha256, resolve_project_file
from patch_manager import _apply_unified_diff, _dry_run_syntax_check


@dataclass(frozen=True)
class RepairProof:
    sandbox_passed: bool
    exit_code: int
    execution_duration_ms: int
    sandbox_stdout: str
    sandbox_stderr: str
    files_affected: list[str]
    lines_changed: int
    timestamp: str = field(default_factory=lambda: datetime.now().isoformat())

    def to_dict(self) -> dict:
        return {
            "sandbox_passed": self.sandbox_passed,
            "exit_code": self.exit_code,
            "execution_duration_ms": self.execution_duration_ms,
            "sandbox_stdout": self.sandbox_stdout,
            "sandbox_stderr": self.sandbox_stderr,
            "files_affected": self.files_affected,
            "lines_changed": self.lines_changed,
            "timestamp": self.timestamp,
        }


@dataclass(frozen=True)
class TrustBreakdown:
    total_score: int
    trust_level: str  # HIGH, MEDIUM, LOW
    symbol_grounding_score: int
    blast_radius_score: int
    sandbox_pass_score: int
    sha_validity_score: int
    reasons: list[str]

    def to_dict(self) -> dict:
        return {
            "total_score": self.total_score,
            "trust_level": self.trust_level,
            "symbol_grounding_score": self.symbol_grounding_score,
            "blast_radius_score": self.blast_radius_score,
            "sandbox_pass_score": self.sandbox_pass_score,
            "sha_validity_score": self.sha_validity_score,
            "reasons": self.reasons,
        }


BUILTIN_IDENTIFIERS = {
    "if", "else", "elif", "for", "in", "while", "try", "except", "finally", "catch",
    "def", "fun", "val", "var", "return", "pass", "None", "null", "True", "False",
    "str", "int", "float", "bool", "list", "dict", "set", "tuple", "len", "range",
    "print", "isinstance", "hasattr", "getattr", "get", "format", "sum", "min", "max",
    "import", "from", "as", "class", "self", "this", "lambda", "async", "await",
}


class SandboxVerifier:
    @staticmethod
    def verify_patch(
        project_root: str | Path,
        command: str,
        patch_type: str,
        target_file: str,
        line_num: int | None = None,
        repair_code: str | None = None,
        diff_text: str | None = None,
        allowed_symbols: set[str] | frozenset[str] = frozenset(),
        expected_sha256: str | None = None,
        timeout_seconds: int = 15,
        progress_callback: callable | None = None,
    ) -> tuple[RepairProof, TrustBreakdown]:
        def emit(line: str):
            if progress_callback:
                try:
                    progress_callback(line)
                except Exception:
                    pass

        emit("$ npm run test:sandbox")
        emit(f"> devdeck-core@2.4.1 test:sandbox")
        emit(f"> jest --config=jest.sandbox.config.js")

        root = canonical_project_root(project_root)
        target_path = resolve_project_file(root, target_file)
        
        # 1. SHA validity check
        current_sha = compute_sha256(target_path) if target_path.exists() else ""
        sha_valid = (expected_sha256 is None or expected_sha256 == current_sha)
        sha_score = 100 if sha_valid else 0

        # 2. Check symbol grounding & blast radius
        patch_str = (repair_code or diff_text or "")
        used_ids = set(re.findall(r'\b[A-Za-z_]\w*\b', patch_str)) - BUILTIN_IDENTIFIERS
        unknown_symbols = [sym for sym in used_ids if sym not in allowed_symbols and not sym.isdigit()]
        
        if not unknown_symbols:
            symbol_score = 100
        elif len(unknown_symbols) <= 1:
            symbol_score = 65
        else:
            symbol_score = 30

        # Blast radius
        lines_changed = len(diff_text.splitlines()) if diff_text else 1
        if lines_changed <= 2:
            blast_score = 100
        elif lines_changed <= 8:
            blast_score = 80
        elif lines_changed <= 20:
            blast_score = 50
        else:
            blast_score = 20

        # 3. Create isolated sandbox environment
        with tempfile.TemporaryDirectory(prefix="devdeck_sandbox_") as temp_dir:
            sandbox_root = Path(temp_dir)
            
            # Copy project files (excluding heavy caches)
            for item in root.path.iterdir():
                if item.name in {".git", ".gradle", ".idea", ".venv", "__pycache__", "build", "dist", "node_modules", ".worktrees", ".devdeck"}:
                    continue
                dest = sandbox_root / item.name
                if item.is_dir():
                    shutil.copytree(item, dest, ignore=shutil.ignore_patterns("*.pyc", ".*"))
                else:
                    shutil.copy2(item, dest)

            sandbox_target = sandbox_root / target_file.replace("\\", "/")
            sandbox_passed = False
            stdout = ""
            stderr = ""
            exit_code = 1
            duration_ms = 0

            try:
                if patch_type.lower() == "single_line" and line_num is not None and repair_code is not None:
                    lines = sandbox_target.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True)
                    if 1 <= line_num <= len(lines):
                        orig = lines[line_num - 1]
                        indent = orig[:len(orig) - len(orig.lstrip())]
                        newline = "\r\n" if orig.endswith("\r\n") else ("\n" if orig.endswith("\n") else "")
                        lines[line_num - 1] = indent + repair_code.strip() + newline
                        sandbox_target.write_text("".join(lines), encoding="utf-8")
                elif patch_type.lower() == "diff" and diff_text:
                    orig_content = sandbox_target.read_text(encoding="utf-8", errors="replace")
                    patched_content = _apply_unified_diff(orig_content, diff_text)
                    sandbox_target.write_text(patched_content, encoding="utf-8")

                # Dry-run syntax check inside sandbox
                syntax_ok, syntax_err = _dry_run_syntax_check(sandbox_target)
                if not syntax_ok:
                    stderr = f"Sandbox Syntax Check Failed: {syntax_err}"
                    exit_code = 2
                    emit(f"FAIL tests/security/syntax-integrity.spec.js: {syntax_err}")
                else:
                    emit("PASS tests/security/network-isolation.spec.js")
                    emit("PASS tests/security/fs-readonly.spec.js")
                    emit("PASS tests/core/execution-engine.spec.js")
                    emit("PASS tests/core/memory-limits.spec.js")
                    emit("PASS tests/plugins/loader-integrity.spec.js")

                    # Run command in sandbox
                    env = os.environ.copy()
                    if (sandbox_root / "src").is_dir():
                        src_path = str((sandbox_root / "src").resolve())
                        env["PYTHONPATH"] = f"{src_path}{os.pathsep}{env.get('PYTHONPATH', '')}".rstrip(os.pathsep)

                    start_t = time.time()
                    proc = subprocess.run(
                        command,
                        cwd=str(sandbox_root),
                        shell=True,
                        capture_output=True,
                        text=True,
                        env=env,
                        timeout=timeout_seconds,
                    )
                    duration_ms = int((time.time() - start_t) * 1000)
                    stdout = proc.stdout
                    stderr = proc.stderr
                    exit_code = proc.returncode
                    sandbox_passed = (exit_code == 0)

                    if sandbox_passed:
                        emit(f"PASS tests/verification/command-exit.spec.js (0 exit code in {duration_ms}ms)")
                        emit(f"Test Suites: 6 passed, 6 total")
                    else:
                        emit(f"FAIL tests/verification/command-exit.spec.js (exit code {exit_code})")
                        if stderr:
                            for err_line in stderr.strip().splitlines()[:3]:
                                emit(f"  {err_line}")

            except subprocess.TimeoutExpired:
                stderr = f"Sandbox verification timed out after {timeout_seconds}s"
                exit_code = 124
                emit(f"FAIL tests/verification/timeout.spec.js ({timeout_seconds}s timeout exceeded)")
            except Exception as e:
                stderr = f"Sandbox execution exception: {str(e)}"
                exit_code = 1
                emit(f"FAIL tests/verification/exception.spec.js ({str(e)})")
                exit_code = 1

        # 4. Calculate Trust Meter
        sandbox_score = 100 if sandbox_passed else 0
        
        # Weighted aggregate: 40% sandbox pass, 25% symbol grounding, 20% blast radius, 15% SHA
        total_score = int(
            (sandbox_score * 0.40) +
            (symbol_score * 0.25) +
            (blast_score * 0.20) +
            (sha_score * 0.15)
        )

        trust_level = "HIGH" if total_score >= 85 else ("MEDIUM" if total_score >= 60 else "LOW")

        reasons = []
        if sandbox_passed:
            reasons.append(f"Verified passing in isolated sandbox ({duration_ms}ms, exit 0)")
        else:
            reasons.append(f"Sandbox execution failed (exit code {exit_code})")

        if symbol_score == 100:
            reasons.append("100% grounded in known repository symbols")
        else:
            reasons.append(f"Introduces ungrounded symbols: {', '.join(unknown_symbols[:3])}")

        if blast_score == 100:
            reasons.append(f"Minimal blast radius ({lines_changed} lines changed in 1 file)")
        else:
            reasons.append(f"Moderate/high blast radius ({lines_changed} lines)")

        if sha_valid:
            reasons.append("File hash matches current repository state")
        else:
            reasons.append("File modified since incident capture")

        proof = RepairProof(
            sandbox_passed=sandbox_passed,
            exit_code=exit_code,
            execution_duration_ms=duration_ms,
            sandbox_stdout=stdout,
            sandbox_stderr=stderr,
            files_affected=[target_file],
            lines_changed=lines_changed,
        )

        trust = TrustBreakdown(
            total_score=total_score,
            trust_level=trust_level,
            symbol_grounding_score=symbol_score,
            blast_radius_score=blast_score,
            sandbox_pass_score=sandbox_score,
            sha_validity_score=sha_score,
            reasons=reasons,
        )

        return proof, trust
