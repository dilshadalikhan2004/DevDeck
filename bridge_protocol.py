"""Versioned, incident-bound messages accepted by the local bridge."""

from dataclasses import dataclass
import re


SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class Incident:
    incident_id: str
    project_id: str
    command: str


@dataclass(frozen=True)
class RepairRequest:
    incident_id: str
    project_id: str
    file: str
    expected_sha256: str
    patch_type: str
    confidence: float
    line: int | None = None
    code: str | None = None
    diff_text: str | None = None

    @classmethod
    def from_dict(cls, payload: dict) -> "RepairRequest":
        if payload.get("type") != "repair":
            raise ValueError("type must be repair")
        if payload.get("protocol_version") != 2:
            raise ValueError("protocol_version must be 2")

        required = ("incident_id", "project_id", "file", "expected_sha256", "patch_type", "confidence")
        if any(not payload.get(field) for field in required):
            raise ValueError("repair payload is missing required fields")

        expected_sha256 = payload["expected_sha256"]
        if not isinstance(expected_sha256, str) or not SHA256_PATTERN.fullmatch(expected_sha256):
            raise ValueError("expected_sha256 must be a lowercase SHA-256 hex digest")

        patch_type = payload["patch_type"]
        if patch_type not in {"single_line", "diff"}:
            raise ValueError("patch_type must be single_line or diff")

        line = payload.get("line")
        code = payload.get("code")
        diff_text = payload.get("diff_text")
        if patch_type == "single_line":
            if not isinstance(line, int) or line < 1 or not isinstance(code, str) or not code.strip():
                raise ValueError("single_line repairs require a positive line and code")
            if diff_text is not None:
                raise ValueError("single_line repairs must not contain diff_text")
        else:
            if not isinstance(diff_text, str) or not diff_text.strip():
                raise ValueError("diff repairs require diff_text")
            if code is not None:
                raise ValueError("diff repairs must not contain code")
            if line is not None:
                raise ValueError("diff repairs must not contain line")

        confidence = payload["confidence"]
        if not isinstance(confidence, (int, float)) or not 0.0 <= confidence <= 1.0:
            raise ValueError("confidence must be between 0 and 1")

        return cls(
            incident_id=payload["incident_id"],
            project_id=payload["project_id"],
            file=payload["file"],
            expected_sha256=expected_sha256,
            patch_type=patch_type,
            confidence=float(confidence),
            line=line,
            code=code,
            diff_text=diff_text,
        )
