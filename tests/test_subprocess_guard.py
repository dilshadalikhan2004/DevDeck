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
