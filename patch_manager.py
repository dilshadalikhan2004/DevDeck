import os
import subprocess
import tempfile
import shutil
import hashlib
import re
from pathlib import Path
from datetime import datetime
from sandbox_runner import SandboxRunner


class PatchManager:
    def __init__(self, backup_dir=".devdeck/snapshots"):
        self.backup_dir = Path(backup_dir)
        self.backup_dir.mkdir(parents=True, exist_ok=True)

    def apply_repair(self, data, last_command=None, project_root=None):
        patch_type = data.get("patch_type", "single_line")
        file_path = data.get("file", "")

        if not os.path.exists(file_path):
            rel_path = os.path.basename(file_path)
            if os.path.exists(rel_path):
                file_path = rel_path
            else:
                return False, f"File not found: {file_path}", None, None

        if data.get("protocol_version") == 2 and project_root:
            candidate, error = self._candidate_content(data, file_path)
            if error:
                return False, error, file_path, None
            if not last_command:
                return False, "Sandbox verification requires the incident command", file_path, None
            try:
                relative_file = Path(file_path).resolve().relative_to(Path(project_root).resolve()).as_posix()
            except ValueError:
                return False, "Repair target is outside the sandbox project root", file_path, None
            result = SandboxRunner(project_root).verify(relative_file, candidate, last_command)
            if not result.passed:
                detail = "sandbox timeout" if result.timed_out else f"exit code {result.exit_code}"
                return False, f"Sandbox verification failed: {detail}", file_path, None

        if patch_type == "single_line":
            return self.apply_single_line_repair(data, last_command, file_path)
        elif patch_type == "diff":
            return self.apply_diff_patch(data, last_command, file_path)
        else:
            return False, f"Unknown patch_type: {patch_type}", None, None

    def _candidate_content(self, data, file_path):
        try:
            original = Path(file_path).read_text(encoding="utf-8")
        except OSError as error:
            return None, str(error)

        if data.get("patch_type", "single_line") == "single_line":
            line_num = data.get("line")
            new_code = data.get("code", "")
            lines = original.splitlines(keepends=True)
            if not line_num or not new_code or not 1 <= line_num <= len(lines):
                return None, "Invalid single-line payload"
            old_line = lines[line_num - 1]
            indent = old_line[:len(old_line) - len(old_line.lstrip())]
            lines[line_num - 1] = f"{indent}{new_code.strip()}\n"
            return "".join(lines), None

        if data.get("patch_type") == "diff":
            patched = self._apply_unified_diff(original, data.get("diff_text", ""))
            if patched is None:
                return None, "Built-in diff application failed"
            return patched, None

        return None, f"Unknown patch_type: {data.get('patch_type')}"

    def apply_single_line_repair(self, data, last_command, file_path):
        line_num = data.get("line")
        new_code = data.get("code", "")
        expected_sha = data.get("expected_sha256")

        if not line_num or not isinstance(new_code, str):
            return False, "Invalid single-line payload", file_path, None

        # 1. Integrity Check (SHA256)
        if expected_sha:
            current_sha = self._calculate_sha256(file_path)
            if current_sha != expected_sha:
                return False, "Integrity check failed: file changed since diagnosis.", file_path, None

        # 2. Create Snapshot
        snapshot_id = self._create_snapshot(file_path)

        try:
            with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                lines = f.readlines()

            if not (1 <= line_num <= len(lines)):
                self._restore_snapshot(file_path, snapshot_id)
                return False, f"Line {line_num} out of range (1..{len(lines)})", file_path, snapshot_id

            old_line = lines[line_num - 1]
            indent = old_line[:len(old_line) - len(old_line.lstrip())]
            lines[line_num - 1] = f"{indent}{new_code.strip()}\n"

            # Check compile / syntax before writing
            full_text = "".join(lines)
            ok, err = self.dry_run_compile_check(file_path, full_text)
            if not ok:
                self._restore_snapshot(file_path, snapshot_id)
                return False, f"Syntax check failed: {err}", file_path, snapshot_id

            with open(file_path, "w", encoding="utf-8") as f:
                f.writelines(lines)

            # 3. Verify with Rerun
            if last_command:
                if not self.rerun_command(last_command):
                    self._restore_snapshot(file_path, snapshot_id)
                    return False, "Rerun failed. Restored snapshot.", file_path, snapshot_id

            return True, None, file_path, snapshot_id
        except Exception as e:
            self._restore_snapshot(file_path, snapshot_id)
            return False, str(e), file_path, snapshot_id

    def apply_diff_patch(self, data, last_command, file_path):
        diff_text = data.get("diff_text")
        expected_sha = data.get("expected_sha256")

        if not diff_text:
            return False, "No diff_text provided", file_path, None

        # 1. Integrity Check
        if expected_sha:
            current_sha = self._calculate_sha256(file_path)
            if current_sha != expected_sha:
                return False, "Integrity check failed: file changed since diagnosis.", file_path, None

        # 2. Create Snapshot
        snapshot_id = self._create_snapshot(file_path)

        try:
            with open(file_path, "r", encoding="utf-8", errors="replace") as f:
                original = f.read()

            patched = self._apply_unified_diff(original, diff_text)
            if patched is None:
                self._restore_snapshot(file_path, snapshot_id)
                return False, "Built-in diff application failed", file_path, snapshot_id

            ok, err = self.dry_run_compile_check(file_path, patched)
            if not ok:
                self._restore_snapshot(file_path, snapshot_id)
                return False, f"Syntax check failed: {err}", file_path, snapshot_id

            with open(file_path, "w", encoding="utf-8") as f:
                f.write(patched)

            if last_command:
                if not self.rerun_command(last_command):
                    self._restore_snapshot(file_path, snapshot_id)
                    return False, "Rerun failed after patch. Restored snapshot.", file_path, snapshot_id

            return True, None, file_path, snapshot_id
        except Exception as e:
            self._restore_snapshot(file_path, snapshot_id)
            return False, str(e), file_path, snapshot_id

    def _calculate_sha256(self, file_path):
        sha256_hash = hashlib.sha256()
        with open(file_path, "rb") as f:
            for byte_block in iter(lambda: f.read(4096), b""):
                sha256_hash.update(byte_block)
        return sha256_hash.hexdigest()

    def _create_snapshot(self, file_path):
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        file_name = Path(file_path).name
        snapshot_path = self.backup_dir / f"{file_name}.{timestamp}.bak"
        shutil.copy2(file_path, snapshot_path)
        print(f"[Snapshot] Created: {snapshot_path}")
        return str(snapshot_path)

    def _restore_snapshot(self, file_path, snapshot_path):
        if snapshot_path and os.path.exists(snapshot_path):
            shutil.copy2(snapshot_path, file_path)
            print(f"[Snapshot] Restored {file_path} from {snapshot_path}")
        else:
            print(f"[Snapshot] Warning: No snapshot found to restore {file_path}")

    def _apply_unified_diff(self, original: str, diff_text: str) -> str | None:
        """Robust pure-Python unified diff applier with multi-hunk support."""
        if not diff_text.strip():
            return original

        # Normalize line endings
        original_has_crlf = "\r\n" in original
        lines = original.splitlines(keepends=True)
        diff_lines = diff_text.splitlines()

        # Find all hunk headers
        hunk_header_re = re.compile(r"^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@")

        result = []
        i = 0  # original line pointer (0-indexed)
        j = 0  # diff lines pointer

        # Skip prelude comments / file header lines until first @@
        while j < len(diff_lines) and not diff_lines[j].startswith("@@"):
            j += 1

        if j >= len(diff_lines):
            return None

        while j < len(diff_lines):
            line = diff_lines[j]
            match = hunk_header_re.match(line)
            if not match:
                j += 1
                continue

            old_start = int(match.group(1))
            target_idx = max(0, old_start - 1)

            # Copy unchanged original lines leading up to this hunk
            while i < target_idx and i < len(lines):
                result.append(lines[i])
                i += 1

            j += 1
            while j < len(diff_lines) and not diff_lines[j].startswith("@@"):
                dl = diff_lines[j]
                if not dl:
                    # Empty context line
                    if i < len(lines):
                        result.append(lines[i])
                        i += 1
                elif dl.startswith(" "):
                    if i < len(lines):
                        result.append(lines[i])
                        i += 1
                elif dl.startswith("-"):
                    if not dl.startswith("---"):
                        if i < len(lines):
                            i += 1  # Skip removed line from original
                elif dl.startswith("+"):
                    if not dl.startswith("+++"):
                        new_content = dl[1:] + ("\r\n" if original_has_crlf else "\n")
                        result.append(new_content)
                j += 1

        # Copy any remaining original lines after the last hunk
        while i < len(lines):
            result.append(lines[i])
            i += 1

        return "".join(result)

    def dry_run_compile_check(self, file_path: str, content: str):
        ext = os.path.splitext(file_path)[1].lower()
        if ext == ".py":
            with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False, encoding="utf-8") as tmp:
                tmp.write(content)
                tmp_path = tmp.name
            try:
                result = subprocess.run(
                    ["python", "-m", "py_compile", tmp_path],
                    capture_output=True,
                    text=True,
                    timeout=5
                )
                return (result.returncode == 0, result.stderr if result.returncode != 0 else "")
            except Exception as e:
                return False, str(e)
            finally:
                if os.path.exists(tmp_path):
                    try:
                        os.unlink(tmp_path)
                    except OSError:
                        pass
        elif ext in [".js", ".ts", ".kt", ".java"]:
            if content.count("{") != content.count("}"):
                return False, "Unmatched braces in source"
            if content.count("(") != content.count(")"):
                return False, "Unmatched parentheses in source"
            return True, ""
        return True, ""

    def rerun_command(self, command: str, timeout_seconds: int = 15) -> bool:
        print(f"[PatchManager] Rerunning command: {command}")
        try:
            env = os.environ.copy()
            py_paths = [str(self.project_root.resolve())]
            if (self.project_root / "src").is_dir():
                py_paths.append(str((self.project_root / "src").resolve()))
            if env.get("PYTHONPATH"):
                py_paths.append(env["PYTHONPATH"])
            env["PYTHONPATH"] = os.pathsep.join(py_paths)
            env["PYTHONUNBUFFERED"] = "1"
            env["CI"] = "1"
            env["DEBIAN_FRONTEND"] = "noninteractive"

            result = subprocess.run(
                command,
                cwd=str(self.project_root),
                shell=True,
                capture_output=True,
                text=True,
                input="",
                env=env,
                timeout=timeout_seconds,
            )
            success = result.returncode == 0
            print(f"[PatchManager] Rerun {'SUCCESS (Exit 0)' if success else f'FAILED (Exit {result.returncode})'}")
            return success
        except subprocess.TimeoutExpired:
            print(f"[PatchManager] Rerun TIMEOUT (>{timeout_seconds}s)")
            return False
        except Exception as e:
            print(f"[PatchManager] Rerun error: {e}")
            return False


_pm = PatchManager()


def _apply_unified_diff(original: str, diff_text: str) -> str:
    return _pm._apply_unified_diff(original, diff_text)


def _dry_run_syntax_check(target_path: Path | str) -> tuple[bool, str]:
    path = Path(target_path)
    if not path.is_file():
        return True, ""
    content = path.read_text(encoding="utf-8", errors="replace")
    return _pm.dry_run_compile_check(str(path), content)

