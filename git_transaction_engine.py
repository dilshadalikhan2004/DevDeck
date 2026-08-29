import os
import subprocess
import shutil
from pathlib import Path
from datetime import datetime

class GitTransactionEngine:
    def __init__(self):
        self._is_git = None

    def is_git_repo(self) -> bool:
        if self._is_git is not None:
            return self._is_git
        try:
            result = subprocess.run(
                ['git', 'rev-parse', '--is-inside-work-tree'],
                capture_output=True,
                text=True,
                timeout=2
            )
            self._is_git = result.returncode == 0
            return self._is_git
        except Exception:
            self._is_git = False
            return False

    def create_transaction(self, file_path: str) -> str:
        if not self.is_git_repo():
            return None
        try:
            result = subprocess.run(
                ['git', 'status', '--porcelain', file_path],
                capture_output=True,
                text=True,
                timeout=2
            )
            has_changes = bool(result.stdout.strip())

            if has_changes:
                stash_msg = f"devdeck_pre_repair_{datetime.now().timestamp()}"
                subprocess.run(
                    ['git', 'stash', 'push', '-u', '-m', stash_msg, file_path],
                    capture_output=True,
                    timeout=5
                )
                print(f"[Git] Stashed: {stash_msg}")
                return stash_msg
            else:
                result = subprocess.run(
                    ['git', 'rev-parse', 'HEAD'],
                    capture_output=True,
                    text=True,
                    timeout=2
                )
                head = result.stdout.strip()
                print(f"[Git] No changes, HEAD: {head[:8]}")
                return head
        except Exception as e:
            print(f"[Git] Transaction creation error: {e}")
            return None

    def commit_transaction(self, transaction_id: str):
        if not transaction_id:
            return
        if transaction_id.startswith("devdeck_pre_repair_"):
            try:
                result = subprocess.run(
                    ['git', 'stash', 'list'],
                    capture_output=True,
                    text=True,
                    timeout=2
                )
                stash_list = result.stdout.splitlines()
                stash_ref = None
                for line in stash_list:
                    if transaction_id in line:
                        stash_ref = line.split(':')[0].strip()
                        break
                if stash_ref:
                    subprocess.run(
                        ['git', 'stash', 'drop', stash_ref],
                        capture_output=True,
                        timeout=5
                    )
                    print(f"[Git] Dropped stash: {stash_ref}")
            except Exception as e:
                print(f"[Git] Commit transaction error: {e}")

    def rollback_transaction(self, file_path: str, transaction_id: str):
        raise RuntimeError("Git rollback is disabled for repairs; use FileTransaction.rollback")

class FallbackBackupManager:
    def __init__(self, backup_dir=".devdeck/backups"):
        self.backup_dir = Path(backup_dir)
        self.backup_dir.mkdir(parents=True, exist_ok=True)

    def create_backup(self, file_path: str) -> str:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        file_name = Path(file_path).name
        backup_path = self.backup_dir / f"{file_name}.{timestamp}.bak"
        shutil.copy2(file_path, backup_path)
        print(f"[Backup] Created: {backup_path}")
        return str(backup_path)

    def commit(self, backup_path: str):
        print(f"[Backup] Kept: {backup_path}")

    def rollback(self, file_path: str, backup_path: str):
        if os.path.exists(backup_path):
            shutil.copy2(backup_path, file_path)
            print(f"[Backup] Restored from: {backup_path}")
        else:
            print(f"[Backup] Warning: backup not found: {backup_path}")
