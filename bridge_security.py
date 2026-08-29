"""Trusted-project path confinement and content fingerprint helpers."""

from dataclasses import dataclass
import hashlib
from pathlib import Path


@dataclass(frozen=True)
class ProjectRoot:
    path: Path
    project_id: str


def canonical_project_root(path: str | Path) -> ProjectRoot:
    root = Path(path).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("trusted project root must be a directory")

    project_id = hashlib.sha256(str(root).encode("utf-8")).hexdigest()
    return ProjectRoot(path=root, project_id=project_id)


def resolve_project_file(project: ProjectRoot, relative_path: str | Path) -> Path:
    candidate = (project.path / relative_path).resolve()
    if candidate == project.path or project.path not in candidate.parents:
        raise ValueError("repair target is outside trusted project root")
    if not candidate.is_file():
        raise ValueError("repair target must be an existing file")
    return candidate


def sha256_file(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        while chunk := source.read(64 * 1024):
            digest.update(chunk)
    return digest.hexdigest()
