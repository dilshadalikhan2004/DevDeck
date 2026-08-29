import tempfile
import unittest
from pathlib import Path

from devdeck import build_incident_payload
from repo_context import RepositoryIndex, build_evidence_pack


class RepositoryContextTest(unittest.TestCase):
    def test_index_ignores_generated_directories_and_finds_python_symbols(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "src").mkdir()
            (root / "node_modules").mkdir()
            (root / "src" / "formatters.py").write_text(
                "def format_receipt(receipt):\n    return receipt.total\n",
                encoding="utf-8",
            )
            (root / "src" / "screen.py").write_text(
                "from formatters import format_receipt\n\ndef render(receipt):\n    return format_receipt(receipt)\n",
                encoding="utf-8",
            )
            (root / "node_modules" / "ignored.py").write_text(
                "def should_not_appear(): pass\n", encoding="utf-8"
            )

            index = RepositoryIndex.build(root)

            self.assertIn("format_receipt", index.symbols)
            self.assertIn("render", index.symbols)
            self.assertNotIn("should_not_appear", index.symbols)

    def test_evidence_pack_includes_existing_imported_symbol_within_budget(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "src").mkdir()
            formatter = root / "src" / "formatters.py"
            screen = root / "src" / "screen.py"
            formatter.write_text(
                "def format_receipt(receipt):\n    return receipt.total\n", encoding="utf-8"
            )
            screen.write_text(
                "from formatters import format_receipt\n\ndef render(receipt):\n    return format_receipt(receipt)\n",
                encoding="utf-8",
            )
            index = RepositoryIndex.build(root)

            evidence = build_evidence_pack(
                index=index,
                error_text="NameError: name 'format_receipt' is not defined",
                target_file="src/screen.py",
                target_line=4,
                token_budget=120,
            )

            self.assertIn("format_receipt", evidence.text)
            self.assertIn("src/formatters.py", evidence.text)
            self.assertLessEqual(evidence.estimated_tokens, 120)
            self.assertIn("format_receipt", evidence.allowed_symbols)

    def test_incident_payload_contains_token_bounded_repository_evidence(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "screen.py"
            source.write_text(
                "from formatters import format_receipt\n\ndef render(receipt):\n    return format_receipt(receipt)\n",
                encoding="utf-8",
            )
            (root / "formatters.py").write_text(
                "def format_receipt(receipt):\n    return receipt.total\n", encoding="utf-8"
            )
            payload = build_incident_payload(
                "py screen.py",
                f'File "{source}", line 4\nNameError: name \'format_receipt\' is not defined',
                root,
            )

            self.assertIn("format_receipt", payload["repository_context"])
            self.assertLessEqual(payload["repository_context_tokens"], 650)
            self.assertIn("format_receipt", payload["allowed_symbols"])


if __name__ == "__main__":
    unittest.main()
