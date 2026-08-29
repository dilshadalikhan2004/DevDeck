import unittest
import os
import hashlib
import sys
import tempfile
from pathlib import Path
from patch_manager import PatchManager

class TestPatchManager(unittest.TestCase):
    def setUp(self):
        self.patch_mgr = PatchManager()
        self.test_file = "temp_test.py"
        with open(self.test_file, "w") as f:
            f.write("def get_token(user):\n    print('test')\n    return user.token\n")

    def tearDown(self):
        if os.path.exists(self.test_file):
            os.remove(self.test_file)

    def test_dry_run_compile_valid(self):
        content = "def test():\n    pass\n"
        ok, err = self.patch_mgr.dry_run_compile_check(self.test_file, content)
        self.assertTrue(ok)

    def test_dry_run_compile_invalid(self):
        content = "def test(\n    pass"  # Missing closing paren
        ok, err = self.patch_mgr.dry_run_compile_check(self.test_file, content)
        self.assertFalse(ok)


class SandboxPreflightPatchManagerTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.project = Path(self.temp_dir.name) / "project"
        self.target = self.project / "src" / "answer.py"
        self.target.parent.mkdir(parents=True)
        self.original = "def value():\n    return 'original'\n"
        self.target.write_text(self.original, encoding="utf-8")
        self.original_bytes = self.target.read_bytes()
        self.manager = PatchManager(backup_dir=self.project / ".snapshots")

    def tearDown(self):
        self.temp_dir.cleanup()

    def _repair(self):
        return {
            "protocol_version": 2,
            "patch_type": "single_line",
            "file": str(self.target),
            "line": 2,
            "code": "return 'candidate'",
            "expected_sha256": hashlib.sha256(self.target.read_bytes()).hexdigest(),
        }

    def test_failed_sandbox_repair_never_changes_live_file(self):
        command = f'"{sys.executable}" -c "raise SystemExit(1)"'

        success, error, _, snapshot = self.manager.apply_repair(
            self._repair(), command, project_root=str(self.project)
        )

        self.assertFalse(success)
        self.assertIn("Sandbox verification failed", error)
        self.assertIsNone(snapshot)
        self.assertEqual(self.original_bytes, self.target.read_bytes())

    def test_passing_sandbox_repair_enters_live_flow(self):
        command = f'"{sys.executable}" -c "raise SystemExit(0)"'

        success, error, _, snapshot = self.manager.apply_repair(
            self._repair(), command, project_root=str(self.project)
        )

        self.assertTrue(success, error)
        self.assertIsNotNone(snapshot)
        self.assertIn("candidate", self.target.read_text(encoding="utf-8"))
