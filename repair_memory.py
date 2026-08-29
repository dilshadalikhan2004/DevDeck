"""Auditable Repair Memory and Safe Autonomy Ladder for DevDeck."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from pathlib import Path
import json
import os


class AutonomyLevel(str, Enum):
    SUGGEST_ONLY = "suggest_only"
    APPROVE_EACH = "approve_each"
    AUTO_APPLY_VERIFIED_LOW_RISK = "auto_apply_verified_low_risk"
    FULL_AUTONOMOUS = "full_autonomous"


@dataclass
class AutonomyPolicy:
    level: AutonomyLevel = AutonomyLevel.APPROVE_EACH

    @classmethod
    def from_string(cls, val: str) -> "AutonomyPolicy":
        val_lower = val.lower().replace("-", "_")
        for level in AutonomyLevel:
            if level.value == val_lower or level.name.lower() == val_lower:
                return cls(level=level)
        if "low" in val_lower or "risk" in val_lower:
            return cls(level=AutonomyLevel.AUTO_APPLY_VERIFIED_LOW_RISK)
        if "suggest" in val_lower:
            return cls(level=AutonomyLevel.SUGGEST_ONLY)
        if "auto" in val_lower:
            return cls(level=AutonomyLevel.FULL_AUTONOMOUS)
        return cls(level=AutonomyLevel.APPROVE_EACH)

    def should_auto_apply(self, trust_score: int, sandbox_passed: bool) -> bool:
        if self.level == AutonomyLevel.FULL_AUTONOMOUS:
            return True
        if self.level == AutonomyLevel.AUTO_APPLY_VERIFIED_LOW_RISK:
            return sandbox_passed and trust_score >= 85
        return False


@dataclass
class RepairMemory:
    root: Path

    @property
    def memory_dir(self) -> Path:
        d = self.root / ".devdeck" / "memory"
        d.mkdir(parents=True, exist_ok=True)
        return d

    @property
    def verified_repairs_file(self) -> Path:
        return self.memory_dir / "verified_repairs.json"

    @property
    def audit_log_file(self) -> Path:
        return self.memory_dir / "audit_log.json"

    @property
    def policy_file(self) -> Path:
        return self.root / ".devdeck" / "policy.json"

    def get_policy(self) -> AutonomyPolicy:
        if self.policy_file.is_file():
            try:
                data = json.loads(self.policy_file.read_text(encoding="utf-8"))
                return AutonomyPolicy.from_string(data.get("policy", "approve_each"))
            except Exception:
                pass
        return AutonomyPolicy(level=AutonomyLevel.APPROVE_EACH)

    def set_policy(self, level: AutonomyLevel) -> None:
        self.policy_file.parent.mkdir(parents=True, exist_ok=True)
        self.policy_file.write_text(
            json.dumps({"policy": level.value, "updated_at": datetime.now().isoformat()}, indent=2),
            encoding="utf-8"
        )

    def log_incident(
        self,
        incident_id: str,
        command: str,
        error_file: str,
        error_line: int,
        error_text: str,
        context_receipt: dict | None,
        candidate_patch: dict | None,
        repair_proof: dict | None,
        trust_breakdown: dict | None,
        status: str,
    ) -> None:
        """Appends complete incident record to immutable audit log for replay."""
        record = {
            "incident_id": incident_id,
            "timestamp": datetime.now().isoformat(),
            "command": command,
            "error_file": error_file,
            "error_line": error_line,
            "error_text": error_text,
            "context_receipt": context_receipt,
            "candidate_patch": candidate_patch,
            "repair_proof": repair_proof,
            "trust_breakdown": trust_breakdown,
            "status": status,
        }

        records = self.get_audit_log()
        # Update existing record or prepend new
        idx = next((i for i, r in enumerate(records) if r.get("incident_id") == incident_id), None)
        if idx is not None:
            records[idx] = record
        else:
            records.insert(0, record)

        if len(records) > 200:
            records = records[:200]

        self.audit_log_file.write_text(json.dumps(records, indent=2), encoding="utf-8")

    def save_verified_repair(
        self,
        incident_id: str,
        error_type: str,
        file_path: str,
        original_line: str,
        fix_code: str | None,
        diff_text: str | None,
        trust_score: int,
    ) -> None:
        """Saves only successful, verified repairs as learned local project memory."""
        repairs = self.get_verified_repairs()
        entry = {
            "incident_id": incident_id,
            "timestamp": datetime.now().isoformat(),
            "error_type": error_type,
            "file_path": file_path,
            "original_line": original_line,
            "fix_code": fix_code,
            "diff_text": diff_text,
            "trust_score": trust_score,
        }
        repairs.insert(0, entry)
        if len(repairs) > 100:
            repairs = repairs[:100]

        self.verified_repairs_file.write_text(json.dumps(repairs, indent=2), encoding="utf-8")

    def get_audit_log(self) -> list[dict]:
        if not self.audit_log_file.is_file():
            return []
        try:
            return json.loads(self.audit_log_file.read_text(encoding="utf-8"))
        except Exception:
            return []

    def get_verified_repairs(self) -> list[dict]:
        if not self.verified_repairs_file.is_file():
            return []
        try:
            return json.loads(self.verified_repairs_file.read_text(encoding="utf-8"))
        except Exception:
            return []

    def get_incident_by_id(self, incident_id: str) -> dict | None:
        records = self.get_audit_log()
        for r in records:
            if r.get("incident_id") == incident_id:
                return r
        return None
