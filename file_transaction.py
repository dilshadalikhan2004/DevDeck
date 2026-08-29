"""Single-file snapshots for verified repair transactions."""

from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import tempfile

from bridge_security import sha256_file


@dataclass
class FileTransaction:
    target: Path
    snapshot_path: Path
    original_sha256: str

    @classmethod
    def create(cls, target: str | Path) -> "FileTransaction":
        target_path = Path(target).resolve(strict=True)
        if not target_path.is_file():
            raise ValueError("transaction target must be an existing file")

        descriptor, snapshot_name = tempfile.mkstemp(
            dir=target_path.parent,
            prefix=f".{target_path.name}.",
            suffix=".devdeck-snapshot",
        )
        os.close(descriptor)
        snapshot_path = Path(snapshot_name)
        shutil.copy2(target_path, snapshot_path)
        return cls(target_path, snapshot_path, sha256_file(target_path))

    def commit(self) -> None:
        self.snapshot_path.unlink(missing_ok=True)

    def rollback(self) -> None:
        if not self.snapshot_path.is_file():
            raise RuntimeError("repair snapshot is unavailable")
        shutil.copy2(self.snapshot_path, self.target)
        self.snapshot_path.unlink(missing_ok=True)
