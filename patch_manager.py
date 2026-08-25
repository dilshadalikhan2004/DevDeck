import os
import subprocess
import tempfile
from pathlib import Path
from git_transaction_engine import GitTransactionEngine, FallbackBackupManager

class PatchManager:
    def __init__(self):
        self.git_engine = GitTransactionEngine()
        self.fallback_backup = FallbackBackupManager()

    def apply_repair(self, data, last_command):
        patch_type = data.get("patch_type", "single_line")
        file_path = data.get("file", "")

        if not os.path.exists(file_path):
            rel_path = os.path.basename(file_path)
            if os.path.exists(rel_path):
                file_path = rel_path
            else:
                return False, f"File not found: {file_path}", None, None

        if patch_type == "single_line":
            return self.apply_single_line_repair(data, last_command, file_path)
        elif patch_type == "diff":
            return self.apply_diff_patch(data, last_command, file_path)
        else:
            return False, f"Unknown patch_type: {patch_type}", None, None

    def apply_single_line_repair(self, data, last_command, file_path):
        line_num = data.get("line")
        new_code = data.get("code", "")

        if not line_num or not new_code:
            return False, "Invalid single-line payload", file_path, None

        if "\n" in new_code.strip() or "\r" in new_code.strip():
            return False, "Multi-line fix in single-line mode rejected", file_path, None

        if self.git_engine.is_git_repo():
            transaction_id = self.git_engine.create_transaction(file_path)
        else:
            transaction_id = self.fallback_backup.create_backup(file_path)

        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                lines = f.readlines()

            if not (1 <= line_num <= len(lines)):
                self._rollback(file_path, transaction_id)
                return False, f"Line {line_num} out of range", file_path, transaction_id

            old_line = lines[line_num - 1]
            indent = old_line[:len(old_line) - len(old_line.lstrip())]
            lines[line_num - 1] = f"{indent}{new_code.strip()}\n"

            with open(file_path, 'w', encoding='utf-8') as f:
                f.writelines(lines)

            if last_command:
                if not self.rerun_command(last_command):
                    self._rollback(file_path, transaction_id)
                    return False, "Rerun failed", file_path, transaction_id

            self._commit(transaction_id)
            return True, None, file_path, transaction_id
        except Exception as e:
            self._rollback(file_path, transaction_id)
            return False, str(e), file_path, transaction_id

    def apply_diff_patch(self, data, last_command, file_path):
        diff_text = data.get("diff_text")
        if not diff_text:
            return False, "No diff_text", file_path, None

        if self.git_engine.is_git_repo():
            transaction_id = self.git_engine.create_transaction(file_path)
        else:
            transaction_id = self.fallback_backup.create_backup(file_path)

        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                original = f.read()

            patched = self._apply_diff_to_string(original, diff_text)
            if patched is None:
                self._rollback(file_path, transaction_id)
                return False, "Diff application failed", file_path, transaction_id

            ok, err = self.dry_run_compile_check(file_path, patched)
            if not ok:
                self._rollback(file_path, transaction_id)
                return False, f"Syntax check failed: {err}", file_path, transaction_id

            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(patched)

            if last_command:
                if not self.rerun_command(last_command):
                    self._rollback(file_path, transaction_id)
                    return False, "Rerun failed after patch", file_path, transaction_id

            self._commit(transaction_id)
            return True, None, file_path, transaction_id
        except Exception as e:
            self._rollback(file_path, transaction_id)
            return False, str(e), file_path, transaction_id

    def _apply_diff_to_string(self, original: str, diff_text: str):
        with tempfile.NamedTemporaryFile(mode='w', suffix='.tmp', delete=False, encoding='utf-8') as tmp:
            tmp.write(original)
            tmp_path = tmp.name

        with tempfile.NamedTemporaryFile(mode='w', suffix='.patch', delete=False, encoding='utf-8') as pf:
            patch_content = f"--- a/temp\n+++ b/temp\n{diff_text}\n"
            pf.write(patch_content)
            patch_path = pf.name

        try:
            result = subprocess.run(
                ['patch', tmp_path, patch_path],
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode != 0:
                print(f"[PatchManager] patch command failed: {result.stderr}")
                return None

            with open(tmp_path, 'r', encoding='utf-8') as f:
                return f.read()
        except Exception as e:
            print(f"[PatchManager] Error applying diff: {e}")
            return None
        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)
            if os.path.exists(patch_path):
                os.unlink(patch_path)

    def dry_run_compile_check(self, file_path: str, content: str):
        ext = os.path.splitext(file_path)[1].lower()
        if ext == '.py':
            with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False, encoding='utf-8') as tmp:
                tmp.write(content)
                tmp_path = tmp.name
            try:
                result = subprocess.run(
                    ['python', '-m', 'py_compile', tmp_path],
                    capture_output=True,
                    text=True,
                    timeout=3
                )
                return (result.returncode == 0, result.stderr if result.returncode != 0 else "")
            finally:
                if os.path.exists(tmp_path):
                    os.unlink(tmp_path)
        elif ext in ['.js', '.ts', '.kt', '.java']:
            if content.count('{') != content.count('}'):
                return False, "Unmatched braces"
            return True, ""
        return True, ""

    def rerun_command(self, command: str) -> bool:
        print(f"[PatchManager] Rerunning: {command}")
        try:
            result = subprocess.run(
                command,
                shell=True,
                capture_output=True,
                text=True,
                timeout=8
            )
            success = result.returncode == 0
            print(f"[PatchManager] Rerun {'SUCCESS' if success else 'FAILED'}")
            return success
        except subprocess.TimeoutExpired:
            print("[PatchManager] Rerun TIMEOUT")
            return False
        except Exception as e:
            print(f"[PatchManager] Rerun error: {e}")
            return False

    def _commit(self, transaction_id):
        if self.git_engine.is_git_repo():
            self.git_engine.commit_transaction(transaction_id)
        else:
            self.fallback_backup.commit(transaction_id)

    def _rollback(self, file_path, transaction_id):
        if self.git_engine.is_git_repo():
            self.git_engine.rollback_transaction(file_path, transaction_id)
        else:
            self.fallback_backup.rollback(file_path, transaction_id)
