"""Shared pipeline stage/event payloads for laptop, relay, and phone UI."""

from __future__ import annotations

STAGES = (
    "crash_detected",
    "context_indexing",
    "sent_to_phone",
    "diagnosing",
    "grounding_check",
    "sandbox_dry_run",
    "awaiting_review",
    "applying",
    "verifying",
    "complete",
    "rolled_back",
)

PHASES = ("started", "completed", "failed", "skipped")


def make_event(
    incident_id: str,
    stage: str,
    phase: str,
    message: str,
    detail: str | None = None,
    **extra,
) -> dict:
    if stage not in STAGES:
        raise ValueError(f"unknown pipeline stage: {stage}")
    if phase not in PHASES:
        raise ValueError(f"unknown pipeline phase: {phase}")
    payload = {
        "type": "pipeline_event",
        "incident_id": incident_id,
        "stage": stage,
        "phase": phase,
        "message": message,
    }
    if detail:
        payload["detail"] = detail
    payload.update(extra)
    return payload


def humanize_sandbox_failure(proof: dict | None, command: str | None = None) -> tuple[str, str]:
    """Return (short message, detail) — never dump a raw stack trace as the message."""
    proof = proof or {}
    exit_code = proof.get("exit_code")
    stderr = (proof.get("sandbox_stderr") or "").strip()
    stdout = (proof.get("sandbox_stdout") or "").strip()
    cmd = command or "the original command"

    if exit_code == 124 or "timed out" in stderr.lower():
        message = "Sandbox dry-run failed: verification timed out"
    elif exit_code == 2 and "syntax" in stderr.lower():
        message = "Sandbox dry-run failed: patched file did not pass syntax check"
    elif isinstance(exit_code, int):
        message = f"Sandbox dry-run failed: test suite exited with code {exit_code}"
    else:
        message = "Sandbox dry-run failed: candidate patch did not verify"

    detail_bits = [f"Command: {cmd}"]
    if exit_code is not None:
        detail_bits.append(f"Exit code: {exit_code}")
    if stderr:
        detail_bits.append(stderr[:400])
    elif stdout:
        detail_bits.append(stdout[:400])
    return message, "\n".join(detail_bits)


def crash_to_dispatch_events(incident_id: str, *, indexing_rebuilt: bool, command: str) -> list[dict]:
    events = [
        make_event(incident_id, "crash_detected", "started", f"Failure captured: {command}"),
        make_event(incident_id, "crash_detected", "completed", "Crash intercepted and scoped"),
    ]
    if indexing_rebuilt:
        events.append(make_event(incident_id, "context_indexing", "started", "Rebuilding repository symbol index"))
        events.append(make_event(incident_id, "context_indexing", "completed", "Index updated incrementally"))
    else:
        events.append(
            make_event(
                incident_id,
                "context_indexing",
                "skipped",
                "Index already current — skipped full re-scan",
            )
        )
    events.append(make_event(incident_id, "sent_to_phone", "started", "Sending scoped incident to paired phone"))
    return events
