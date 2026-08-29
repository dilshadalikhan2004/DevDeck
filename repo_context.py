"""Small, local repository index used to ground phone-side repair prompts."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import math
import re


DEFAULT_IGNORED_DIRECTORIES = {
    ".git", ".gradle", ".idea", ".venv", "__pycache__", "build", "dist",
    "node_modules", "target", "vendor",
}
SOURCE_SUFFIXES = {".py", ".kt", ".java", ".js", ".jsx", ".ts", ".tsx", ".go", ".rs", ".c", ".cpp", ".h"}
SYMBOL_PATTERN = re.compile(
    r"^\s*(?:def|class|fun|interface|object|enum\s+class|public\s+(?:static\s+)?(?:class|interface)|"
    r"(?:export\s+)?(?:async\s+)?function|func|struct)\s+([A-Za-z_]\w*)",
    re.MULTILINE,
)
IDENTIFIER_PATTERN = re.compile(r"\b[A-Za-z_]\w*\b")
PYTHON_IMPORT_PATTERN = re.compile(r"^\s*from\s+[\w.]+\s+import\s+(.+)$|^\s*import\s+(.+)$", re.MULTILINE)
IMPORT_NAME_PATTERN = re.compile(r"\b([A-Za-z_]\w*)\b")


@dataclass(frozen=True)
class SymbolLocation:
    name: str
    path: str
    line: int


@dataclass(frozen=True)
class EvidencePack:
    text: str
    estimated_tokens: int
    allowed_symbols: frozenset[str]


@dataclass
class RepositoryIndex:
    root: Path
    symbols: dict[str, list[SymbolLocation]]
    file_imports: dict[str, set[str]]

    @classmethod
    def build(cls, root: str | Path) -> "RepositoryIndex":
        project_root = Path(root).resolve()
        symbols: dict[str, list[SymbolLocation]] = {}
        file_imports: dict[str, set[str]] = {}
        for path in project_root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in SOURCE_SUFFIXES:
                continue
            if any(part in DEFAULT_IGNORED_DIRECTORIES for part in path.relative_to(project_root).parts):
                continue
            relative = path.relative_to(project_root).as_posix()
            try:
                source = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            for match in SYMBOL_PATTERN.finditer(source):
                line = source.count("\n", 0, match.start()) + 1
                symbols.setdefault(match.group(1), []).append(SymbolLocation(match.group(1), relative, line))
            file_imports[relative] = _extract_imports(source)
        return cls(root=project_root, symbols=symbols, file_imports=file_imports)


def build_evidence_pack(
    index: RepositoryIndex,
    error_text: str,
    target_file: str | None,
    target_line: int | None,
    token_budget: int = 650,
) -> EvidencePack:
    """Return the smallest evidence set that can prove which existing symbols may be used."""
    if token_budget <= 0:
        raise ValueError("token_budget must be positive")
    normalized_file = (target_file or "").replace("\\", "/").lstrip("./")
    query_symbols = set(IDENTIFIER_PATTERN.findall(error_text))
    query_symbols.update(index.file_imports.get(normalized_file, set()))
    chunks: list[str] = []
    if normalized_file:
        target = index.root / normalized_file
        if target.is_file():
            chunks.append(_file_window(target, normalized_file, target_line))

    allowed = set(index.file_imports.get(normalized_file, set()))
    for name in sorted(query_symbols):
        locations = index.symbols.get(name, [])
        if not locations:
            continue
        allowed.add(name)
        location = locations[0]
        chunks.append(_symbol_window(index.root / location.path, location))

    selected: list[str] = []
    used_chars = 0
    max_chars = token_budget * 4
    for chunk in chunks:
        separator = 2 if selected else 0
        if used_chars + separator + len(chunk) > max_chars:
            continue
        selected.append(chunk)
        used_chars += separator + len(chunk)
    text = "\n\n".join(selected)
    return EvidencePack(text=text, estimated_tokens=math.ceil(len(text) / 4), allowed_symbols=frozenset(allowed))


def _extract_imports(source: str) -> set[str]:
    names: set[str] = set()
    for match in PYTHON_IMPORT_PATTERN.finditer(source):
        imported = match.group(1) or match.group(2) or ""
        for name in IMPORT_NAME_PATTERN.findall(imported):
            if name not in {"as", "import"}:
                names.add(name)
    return names


def _file_window(path: Path, relative_path: str, line_number: int | None, radius: int = 4) -> str:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    center = max(1, min(line_number or 1, max(1, len(lines))))
    start = max(1, center - radius)
    end = min(len(lines), center + radius)
    body = "\n".join(f"{number}: {lines[number - 1]}" for number in range(start, end + 1))
    return f"FILE {relative_path} lines {start}-{end}:\n{body}"


def _symbol_window(path: Path, location: SymbolLocation) -> str:
    return _file_window(path, location.path, location.line, radius=6)
