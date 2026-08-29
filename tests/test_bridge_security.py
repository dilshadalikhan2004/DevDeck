import tempfile
import unittest
from pathlib import Path

from bridge_security import canonical_project_root, resolve_project_file, sha256_file


class BridgeSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.project_path = Path(self.temp_dir.name) / "project"
        self.project_path.mkdir()
        self.source_file = self.project_path / "src" / "example.py"
        self.source_file.parent.mkdir()
        self.source_file.write_text("print('before')\n", encoding="utf-8")
        self.project = canonical_project_root(self.project_path)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_resolves_an_existing_file_inside_the_trusted_project(self):
        resolved = resolve_project_file(self.project, "src/example.py")

        self.assertEqual(self.source_file.resolve(), resolved)

    def test_rejects_path_traversal_outside_the_trusted_project(self):
        with self.assertRaisesRegex(ValueError, "outside trusted project root"):
            resolve_project_file(self.project, "../outside.py")

    def test_hash_changes_when_file_contents_change(self):
        original_hash = sha256_file(self.source_file)
        self.source_file.write_text("print('after')\n", encoding="utf-8")

        self.assertNotEqual(original_hash, sha256_file(self.source_file))
        self.assertEqual(64, len(original_hash))


if __name__ == "__main__":
    unittest.main()
