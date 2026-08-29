import tempfile
import unittest
from pathlib import Path

from file_transaction import FileTransaction


class FileTransactionTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.target = Path(self.temp_dir.name) / "target.py"
        self.original = b"print('original')\r\n"
        self.target.write_bytes(self.original)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_rollback_restores_byte_identical_source(self):
        transaction = FileTransaction.create(self.target)
        self.target.write_bytes(b"print('changed')\n")

        transaction.rollback()

        self.assertEqual(self.original, self.target.read_bytes())

    def test_commit_keeps_new_source_and_removes_snapshot(self):
        transaction = FileTransaction.create(self.target)
        self.target.write_bytes(b"print('changed')\n")

        transaction.commit()

        self.assertEqual(b"print('changed')\n", self.target.read_bytes())
        self.assertFalse(transaction.snapshot_path.exists())


if __name__ == "__main__":
    unittest.main()
