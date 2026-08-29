import unittest
import tempfile
import shutil
import os
from patch_manager import PatchManager

class TestPatchManagerRobustness(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp()
        self.snapshots_dir = os.path.join(self.test_dir, "snapshots")
        self.patch_mgr = PatchManager(backup_dir=self.snapshots_dir)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_multi_hunk_unified_diff(self):
        original = "line 1\nline 2\nline 3\nline 4\nline 5\nline 6\nline 7\nline 8\n"
        diff = "@@ -2,2 +2,2 @@\n-line 2\n+line 2 MODIFIED\n line 3\n@@ -6,2 +6,2 @@\n line 6\n-line 7\n+line 7 MODIFIED\n"
        result = self.patch_mgr._apply_unified_diff(original, diff)
        expected = "line 1\nline 2 MODIFIED\nline 3\nline 4\nline 5\nline 6\nline 7 MODIFIED\nline 8\n"
        self.assertEqual(result, expected)

    def test_rollback_on_failed_rerun(self):
        test_file = os.path.join(self.test_dir, "broken.py")
        with open(test_file, "w", encoding="utf-8") as f:
            f.write("def calc():\n    return 1\n")

        data = {
            "patch_type": "single_line",
            "file": test_file,
            "line": 2,
            "code": "return 2"
        }

        failing_cmd = "python -c \"import sys; sys.exit(1)\""
        success, err, _, _ = self.patch_mgr.apply_repair(data, last_command=failing_cmd)

        self.assertFalse(success)
        self.assertIn("Rerun failed", err)

        with open(test_file, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertEqual(content, "def calc():\n    return 1\n")

    def test_syntax_error_prevention(self):
        test_file = os.path.join(self.test_dir, "syntax_check.py")
        with open(test_file, "w", encoding="utf-8") as f:
            f.write("def func():\n    return 42\n")

        data = {
            "patch_type": "single_line",
            "file": test_file,
            "line": 2,
            "code": "return (invalid syntax def"
        }

        success, err, _, _ = self.patch_mgr.apply_repair(data, last_command=None)
        self.assertFalse(success)
        self.assertIn("Syntax check failed", err)

if __name__ == "__main__":
    unittest.main()
