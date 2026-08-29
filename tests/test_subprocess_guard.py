import unittest

from devdeck import parse_run_command
from subprocess_guard import isolated_env, run_command_isolated


class ParseRunCommandTest(unittest.TestCase):
    def test_strips_repeated_run_keywords(self):
        self.assertEqual(
            "python -m unittest tests.test_sandbox_runner",
            parse_run_command(["run", "run", "python", "-m", "unittest", "tests.test_sandbox_runner"]),
        )

    def test_joins_unquoted_arguments(self):
        self.assertEqual(
            "python hang.py --flag",
            parse_run_command(["python", "hang.py", "--flag"]),
        )

    def test_empty_after_only_run_keywords(self):
        self.assertEqual("", parse_run_command(["run", "RUN"]))

    def test_watch_outcome_complete_is_success(self):
        from devdeck import _watch_outcome
        self.assertEqual(
            "success",
            _watch_outcome({"type": "repair_success", "incident_id": "inc-1"}, "inc-1"),
        )
        self.assertEqual(
            "failed",
            _watch_outcome(
                {"type": "pipeline_event", "incident_id": "inc-1", "stage": "verifying", "phase": "failed", "message": "nope"},
                "inc-1",
            ),
        )

    def test_cli_invocation_error_only_for_run_token(self):
        from devdeck import is_cli_invocation_error
        self.assertTrue(is_cli_invocation_error("run python -m unittest"))
        self.assertFalse(is_cli_invocation_error("python -m unittest tests/unit/test_receipts.py"))

    def test_unittest_file_path_becomes_discover(self):
        import tempfile
        from pathlib import Path
        from devdeck import normalize_watched_command

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            test_file = root / "tests" / "unit" / "test_receipts.py"
            test_file.parent.mkdir(parents=True)
            test_file.write_text("ok\n", encoding="utf-8")
            rewritten = normalize_watched_command(
                "python -m unittest tests/unit/test_receipts.py",
                root,
            )
            self.assertIn("discover", rewritten)
            self.assertIn(' -s "tests/unit"', rewritten)
            self.assertIn(' -p "test_receipts.py"', rewritten)
            self.assertEqual(
                rewritten,
                normalize_watched_command(rewritten, root),
            )


class UnittestLocationTest(unittest.TestCase):
    def test_unittest_import_error_uses_test_file_from_command(self):
        import tempfile
        from pathlib import Path
        from devdeck import build_incident_payload

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            test_file = root / "tests" / "unit" / "test_receipts.py"
            test_file.parent.mkdir(parents=True)
            test_file.write_text("import unittest\nclass T(unittest.TestCase):\n    def test_a(self):\n        self.assertTrue(True)\n", encoding="utf-8")
            stderr = (
                "ERROR: unit (unittest.loader._FailedTest.unit)\n"
                "----------------------------------------------------------------------\n"
                "ImportError: Failed to import test module: unit\n"
                'Traceback (most recent call last):\n'
                '  File "C:\\Python\\Lib\\unittest\\loader.py", line 162, in loadTestsFromName\n'
                "    module = __import__(module_name)\n"
                "ModuleNotFoundError: No module named 'tests.unit'\n"
            )
            payload = build_incident_payload(
                "python -m unittest tests/unit/test_receipts.py",
                stderr,
                root,
            )
            self.assertEqual("tests/unit/test_receipts.py", payload["error_file"])
            self.assertFalse(payload.get("validation_error"))
            self.assertGreaterEqual(payload.get("error_line") or 0, 1)


class IsolatedSubprocessTimeoutTest(unittest.TestCase):
    def test_hung_process_fails_with_exit_124_within_timeout(self):
        import sys
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as tmp:
            hung = Path(tmp) / "hang.py"
            hung.write_text("import time\nwhile True:\n    time.sleep(0.05)\n", encoding="utf-8")
            exit_code, _out, err, timed_out, duration_ms = run_command_isolated(
                f'"{sys.executable}" hang.py',
                cwd=tmp,
                timeout_seconds=1,
                env=isolated_env(tmp),
            )
            self.assertTrue(timed_out)
            self.assertEqual(124, exit_code)
            self.assertLess(duration_ms, 15_000)
            self.assertIn("timed out", err.lower())

    def test_blocking_stdin_gets_eof_instead_of_hanging(self):
        import sys
        import tempfile
        from pathlib import Path

        with tempfile.TemporaryDirectory() as tmp:
            waiter = Path(tmp) / "wait.py"
            waiter.write_text("input()\n", encoding="utf-8")
            exit_code, _out, err, timed_out, duration_ms = run_command_isolated(
                f'"{sys.executable}" wait.py',
                cwd=tmp,
                timeout_seconds=8,
                env=isolated_env(tmp),
            )
            self.assertFalse(timed_out)
            self.assertNotEqual(124, exit_code)
            self.assertLess(duration_ms, 5000)
            self.assertIn("EOF", err)


if __name__ == "__main__":
    unittest.main()
