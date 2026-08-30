from pathlib import Path
import unittest
from unittest.mock import patch, MagicMock
import tempfile
import shutil

from devdeck import extract_repo_slug, link_repository, sync_repository
from repo_context import ProjectBrain


class TestRepoLinkAndSync(unittest.TestCase):

    def test_extract_repo_slug(self):
        self.assertEqual(extract_repo_slug("https://github.com/facebook/react.git"), "react")
        self.assertEqual(extract_repo_slug("git@github.com:torvalds/linux.git"), "linux")
        self.assertEqual(extract_repo_slug("https://gitlab.com/group/subgroup/core-api"), "core-api")
        self.assertEqual(extract_repo_slug("C:\\Users\\dev\\project_alpha"), "project_alpha")

    @patch("subprocess.run")
    @patch("shutil.which")
    def test_link_repository_shallow_clone(self, mock_which, mock_run):
        mock_which.return_value = "C:\\Program Files\\Git\\cmd\\git.exe"
        mock_run.return_value = MagicMock(returncode=0, stdout="Cloning...", stderr="")

        with tempfile.TemporaryDirectory() as tmpdir:
            target_dir = Path(tmpdir) / "test_repo"
            # Target directory doesn't exist yet
            with patch("devdeck.scan_repository") as mock_scan:
                result_path = link_repository("https://github.com/acme/commerce-service.git", target_dir)
                
                # Check git clone was called with --depth=1 and zero credentials in command
                mock_run.assert_called_once()
                called_cmd = mock_run.call_args[0][0]
                self.assertIn("clone", called_cmd)
                self.assertIn("--depth=1", called_cmd)
                self.assertEqual(called_cmd[-2], "https://github.com/acme/commerce-service.git")
                self.assertEqual(result_path, target_dir)
                mock_scan.assert_called_once_with(target_dir)

    def test_sync_repository_indexes_real_tree(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            (root / "app.py").write_text("def process_payment(amount):\n    return True\n", encoding="utf-8")
            (root / "test_app.py").write_text("def test_process():\n    assert True\n", encoding="utf-8")

            with patch("devdeck.send_event"):
                brain = ProjectBrain.build(root)
                summary = brain.summary()
                self.assertEqual(summary["files_indexed"], 2)
                self.assertIn("process_payment", summary["sample_symbols"])
                self.assertEqual(summary["tests_discovered"], 1)


if __name__ == "__main__":
    unittest.main()
