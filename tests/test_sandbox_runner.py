import sys
import tempfile
import unittest
from pathlib import Path

from sandbox_runner import SandboxRunner


class SandboxRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.project = Path(self.temp_dir.name) / "project"
        self.source = self.project / "src" / "answer.py"
        self.source.parent.mkdir(parents=True)
        self.source.write_text("VALUE = 'original'\n", encoding="utf-8")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_verify_runs_candidate_only_in_temporary_project_copy(self):
        command = f'"{sys.executable}" -c "from src.answer import VALUE; raise SystemExit(VALUE != \'candidate\')"'

        result = SandboxRunner(self.project).verify(
            "src/answer.py", "VALUE = 'candidate'\n", command
        )

        self.assertTrue(result.passed)
        self.assertEqual("VALUE = 'original'\n", self.source.read_text(encoding="utf-8"))

    def test_verify_reports_timeout_and_removes_temporary_copy(self):
        runner = SandboxRunner(self.project, timeout_seconds=0.01)
        command = f'"{sys.executable}" -c "import time; time.sleep(1)"'

        result = runner.verify("src/answer.py", "VALUE = 1\n", command)

        self.assertTrue(result.timed_out)
        self.assertFalse(runner.last_sandbox_path.exists())


if __name__ == "__main__":
    unittest.main()
