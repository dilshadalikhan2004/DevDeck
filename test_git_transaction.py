import os
import shutil
import unittest
from git_transaction_engine import GitTransactionEngine, FallbackBackupManager

class TestGitTransactionEngine(unittest.TestCase):
    def setUp(self):
        self.engine = GitTransactionEngine()
        self.backup_mgr = FallbackBackupManager(backup_dir="test_backups")
        self.test_file = "test_file.txt"
        with open(self.test_file, "w") as f:
            f.write("Line 1\nLine 2\nLine 3\n")

    def tearDown(self):
        if os.path.exists(self.test_file):
            os.remove(self.test_file)
        if os.path.exists("test_backups"):
            shutil.rmtree("test_backups")

    def test_backup_and_rollback(self):
        backup_path = self.backup_mgr.create_backup(self.test_file)
        self.assertTrue(os.path.exists(backup_path))

        # Modify file
        with open(self.test_file, "w") as f:
            f.write("Corrupted content\n")

        # Rollback
        self.backup_mgr.rollback(self.test_file, backup_path)
        with open(self.test_file, "r") as f:
            content = f.read()
        self.assertEqual(content, "Line 1\nLine 2\nLine 3\n")
