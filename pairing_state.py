"""Persistent, local-only one-time enrollment state for paired devices."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import secrets
import uuid


@dataclass(frozen=True)
class Enrollment:
    laptop_id: str
    endpoint: str
    certificate_fingerprint: str
    token: str
    expires_at: str


class PairingRegistry:
    """Stores token hashes only; the enrollment token itself exists only in the QR payload."""

    def __init__(self, state_path: str | Path):
        self.state_path = Path(state_path)
        self._state = self._load()

    def create_enrollment(
        self,
        endpoint: str,
        certificate_fingerprint: str,
        ttl_seconds: int = 300,
    ) -> Enrollment:
        token = secrets.token_urlsafe(32)
        expires = datetime.now(timezone.utc) + timedelta(seconds=max(0, ttl_seconds))
        self._state["pending"] = {
            "token_hash": _hash_token(token),
            "endpoint": endpoint,
            "certificate_fingerprint": certificate_fingerprint,
            "expires_at": expires.isoformat(),
        }
        self._save()
        return Enrollment(
            laptop_id=self._state["laptop_id"],
            endpoint=endpoint,
            certificate_fingerprint=certificate_fingerprint,
            token=token,
            expires_at=expires.isoformat(),
        )

    def consume_enrollment(self, token: str, device_public_key: str) -> bool:
        pending = self._state.get("pending")
        if not pending or not device_public_key or pending.get("token_hash") != _hash_token(token):
            return False
        expires_at = datetime.fromisoformat(pending["expires_at"])
        if expires_at <= datetime.now(timezone.utc):
            self._state.pop("pending", None)
            self._save()
            return False
        self._state.setdefault("devices", {})[device_public_key] = {
            "paired_at": datetime.now(timezone.utc).isoformat()
        }
        self._state.pop("pending", None)
        self._save()
        return True

    def is_paired(self, device_public_key: str) -> bool:
        return device_public_key in self._state.get("devices", {})

    def _load(self) -> dict:
        if self.state_path.is_file():
            return json.loads(self.state_path.read_text(encoding="utf-8"))
        return {"laptop_id": str(uuid.uuid4()), "devices": {}}

    def _save(self) -> None:
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path = self.state_path.with_suffix(".tmp")
        temporary_path.write_text(json.dumps(self._state, sort_keys=True), encoding="utf-8")
        temporary_path.replace(self.state_path)


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()
