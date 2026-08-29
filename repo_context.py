"""Local Project Brain and Context Receipt engine for DevDeck transparent repair runtime."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
import json
import math
import os
import re


DEFAULT_IGNORED_DIRECTORIES = {
    ".git", ".gradle", ".idea", ".venv", "__pycache__", "build", "dist",
    "node_modules", "target", "vendor", ".devdeck", ".worktrees",
}
SOURCE_SUFFIXES = {".py", ".kt", ".java", ".js", ".jsx", ".ts", ".tsx", ".go", ".rs", ".c", ".cpp", ".h"}
TEST_PATTERNS = (
    "test_*.py", "*_test.py", "*Test.kt", "*Test.java", "*Tests.kt", "*Tests.java",
    "*.test.js", "*.test.ts", "*.spec.js", "*.spec.ts",
)
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
    kind: str = "function"


@dataclass(frozen=True)
class ContextReceiptItem:
    file: str
    line_start: int
    line_end: int
    symbols: list[str]
    estimated_tokens: int
    reasons: list[str]

    def to_dict(self) -> dict:
        return {
            "file": self.file,
            "line_start": self.line_start,
            "line_end": self.line_end,
            "symbols": self.symbols,
            "estimated_tokens": self.estimated_tokens,
            "reasons": self.reasons,
        }


@dataclass(frozen=True)
class ContextReceipt:
    items: list[ContextReceiptItem]
    total_files: int
    total_symbols: int
    total_tokens: int
    allowed_symbols: frozenset[str]
    evidence_text: str

    def to_dict(self) -> dict:
        return {
            "total_files": self.total_files,
            "total_symbols": self.total_symbols,
            "total_tokens": self.total_tokens,
            "allowed_symbols": sorted(self.allowed_symbols),
            "items": [item.to_dict() for item in self.items],
        }


@dataclass(frozen=True)
class EvidencePack:
    text: str
    estimated_tokens: int
    allowed_symbols: frozenset[str]
    receipt: ContextReceipt | None = None


@dataclass
class ProjectBrain:
    root: Path
    symbols: dict[str, list[SymbolLocation]]
    file_imports: dict[str, set[str]]
    import_graph: dict[str, set[str]]  # imported_module -> set of files that import it
    tests_discovered: list[str]
    source_files_count: int
    total_symbols_count: int
    project_rules: str = ""

    @classmethod
    def build(cls, root: str | Path) -> "ProjectBrain":
        project_root = Path(root).resolve()
        symbols: dict[str, list[SymbolLocation]] = {}
        file_imports: dict[str, set[str]] = {}
        import_graph: dict[str, set[str]] = {}
        tests_discovered: list[str] = []
        source_count = 0
        total_symbols = 0

        # Read project conventions/rules if present
        rules_path = project_root / ".devdeck" / "rules.md"
        project_rules = ""
        if rules_path.is_file():
            try:
                project_rules = rules_path.read_text(encoding="utf-8", errors="replace").strip()
            except OSError:
                pass

        for path in project_root.rglob("*"):
            if not path.is_file():
                continue
            rel_parts = path.relative_to(project_root).parts
            if any(part in DEFAULT_IGNORED_DIRECTORIES for part in rel_parts):
                continue
            
            rel_path = path.relative_to(project_root).as_posix()
            
            # Test discovery
            if any(path.match(pattern) for pattern in TEST_PATTERNS):
                tests_discovered.append(rel_path)

            if path.suffix.lower() not in SOURCE_SUFFIXES:
                continue

            source_count += 1
            try:
                source = path.read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue

            for match in SYMBOL_PATTERN.finditer(source):
                line = source.count("\n", 0, match.start()) + 1
                sym_name = match.group(1)
                kind = "class" if "class" in match.group(0) else "function"
                symbols.setdefault(sym_name, []).append(SymbolLocation(sym_name, rel_path, line, kind))
                total_symbols += 1

            imports = _extract_imports(source)
            file_imports[rel_path] = imports
            for imp in imports:
                import_graph.setdefault(imp, set()).add(rel_path)

        return cls(
            root=project_root,
            symbols=symbols,
            file_imports=file_imports,
            import_graph=import_graph,
            tests_discovered=sorted(tests_discovered),
            source_files_count=source_count,
            total_symbols_count=total_symbols,
            project_rules=project_rules,
        )

    def summary(self) -> dict:
        return {
            "root": str(self.root),
            "files_indexed": self.source_files_count,
            "symbols_indexed": self.total_symbols_count,
            "tests_discovered": len(self.tests_discovered),
            "tests": self.tests_discovered[:10],
            "rules_active": bool(self.project_rules),
        }


# Backward compatibility alias
RepositoryIndex = ProjectBrain


def build_evidence_pack(
    index: ProjectBrain | RepositoryIndex,
    error_text: str,
    target_file: str | None,
    target_line: int | None,
    token_budget: int = 650,
) -> EvidencePack:
    """Return the smallest evidence set with an explainable Context Receipt."""
    if token_budget <= 0:
        raise ValueError("token_budget must be positive")

    normalized_file = (target_file or "").replace("\\", "/").lstrip("./")
    query_symbols = set(IDENTIFIER_PATTERN.findall(error_text))
    direct_imports = index.file_imports.get(normalized_file, set())
    query_symbols.update(direct_imports)

    receipt_items: list[ContextReceiptItem] = []
    chunk_strings: list[str] = []
    allowed_symbols_set: set[str] = set(direct_imports)

    # 1. Target file window (Failing code context)
    if normalized_file:
        target_path = index.root / normalized_file
        if target_path.is_file():
            f_text, s_line, e_line = _file_window_info(target_path, normalized_file, target_line, radius=5)
            symbols_in_window = [
                s.name for s_list in index.symbols.values() for s in s_list
                if s.path == normalized_file and s_line <= s.line <= e_line
            ]
            tokens = math.ceil(len(f_text) / 4)
            reasons = [
                f"Contains failing target statement at line {target_line or 'unknown'}",
                "Immediate stack frame context",
            ]
            receipt_items.append(
                ContextReceiptItem(
                    file=normalized_file,
                    line_start=s_line,
                    line_end=e_line,
                    symbols=symbols_in_window,
                    estimated_tokens=tokens,
                    reasons=reasons,
                )
            )
            chunk_strings.append(f_text)

    # 2. Query symbols and connected definitions
    for name in sorted(query_symbols):
        locations = index.symbols.get(name, [])
        if not locations:
            continue
        allowed_symbols_set.add(name)
        location = locations[0]
        f_text, s_line, e_line = _file_window_info(index.root / location.path, location.path, location.line, radius=6)
        tokens = math.ceil(len(f_text) / 4)
        
        reasons = []
        if name in direct_imports:
            reasons.append(f"Imported directly by {normalized_file}")
        if name in error_text:
            reasons.append(f"Referenced in stack trace or error message")
        if location.path in index.import_graph.get(name, set()):
            reasons.append(f"Exported symbol referenced by callers")
        if not reasons:
            reasons.append(f"Connected repository symbol definition for '{name}'")

        receipt_items.append(
            ContextReceiptItem(
                file=location.path,
                line_start=s_line,
                line_end=e_line,
                symbols=[name],
                estimated_tokens=tokens,
                reasons=reasons,
            )
        )
        chunk_strings.append(f_text)

    # Budget selection
    selected_chunks: list[str] = []
    final_receipt_items: list[ContextReceiptItem] = []
    used_chars = 0
    max_chars = token_budget * 4

    for chunk, item in zip(chunk_strings, receipt_items):
        sep = 2 if selected_chunks else 0
        if used_chars + sep + len(chunk) > max_chars:
            continue
        selected_chunks.append(chunk)
        final_receipt_items.append(item)
        used_chars += sep + len(chunk)

    combined_text = "\n\n".join(selected_chunks)
    total_tokens = math.ceil(len(combined_text) / 4)
    total_files = len({item.file for item in final_receipt_items})
    total_symbols = len({s for item in final_receipt_items for s in item.symbols})

    receipt = ContextReceipt(
        items=final_receipt_items,
        total_files=total_files,
        total_symbols=total_symbols,
        total_tokens=total_tokens,
        allowed_symbols=frozenset(allowed_symbols_set),
        evidence_text=combined_text,
    )

    return EvidencePack(
        text=combined_text,
        estimated_tokens=total_tokens,
        allowed_symbols=frozenset(allowed_symbols_set),
        receipt=receipt,
    )


def _extract_imports(source: str) -> set[str]:
    names: set[str] = set()
    for match in PYTHON_IMPORT_PATTERN.finditer(source):
        imported = match.group(1) or match.group(2) or ""
        for name in IMPORT_NAME_PATTERN.findall(imported):
            if name not in {"as", "import"}:
                names.add(name)
    return names


def _file_window_info(path: Path, relative_path: str, line_number: int | None, radius: int = 4) -> tuple[str, int, int]:
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return f"FILE {relative_path}:\n[unreadable]", 1, 1

    center = max(1, min(line_number or 1, max(1, len(lines))))
    start = max(1, center - radius)
    end = min(len(lines), center + radius)
    body = "\n".join(f"{number}: {lines[number - 1]}" for number in range(start, end + 1))
    return f"FILE {relative_path} lines {start}-{end}:\n{body}", start, end


def _file_window(path: Path, relative_path: str, line_number: int | None, radius: int = 4) -> str:
    text, _, _ = _file_window_info(path, relative_path, line_number, radius)
    return text


def _symbol_window(path: Path, location: SymbolLocation) -> str:
    return _file_window(path, location.path, location.line, radius=6)
