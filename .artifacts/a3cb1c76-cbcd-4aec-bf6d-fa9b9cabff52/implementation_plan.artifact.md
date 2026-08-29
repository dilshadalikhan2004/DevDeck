# DevDeck Pocket — Hardening & Productization Plan

This plan integrates the "Safe Core" architecture with the "Individual Developer Product Design" requirements. It shifts the project from a "hackathon concept" to a "production-ready tool" by focusing on reliability, transactional safety, and authorized pairing.

## User Review Required

> [!IMPORTANT]
> **Transactional Safety Change**: We are moving away from `git checkout` for rollbacks. We will now use a side-by-side snapshot mechanism (`.bak` files or a dedicated cache) to ensure we never discard unrelated user changes.
> **Authorization Barrier**: The relay will no longer accept auto-repairs from unauthorized clients (like the web dashboard). The Android app must be paired and authenticated.

## Proposed Changes

### 1. Infrastructure Hardening (Desktop Bridge)

#### [MODIFY] [relay_server.py](file:///C:/Users/LENOVO/Downloads/receipts-android/relay_server.py)
- **Session Management**: Replace the global `last_command` with an `Incident` object mapping.
- **Diff Engine**: Replace system `patch` calls with a built-in Python-based unified diff applier to ensure Windows compatibility.
- **Pairing Logic**: Implement a one-time pairing secret for WebSocket connections.

#### [NEW] [patch_manager.py](file:///C:/Users/LENOVO/Downloads/receipts-android/patch_manager.py)
- **Snapshot Logic**: Handle per-file backups and verified restores.
- **Integrity Checks**: Implement SHA256 pre-edit hash validation to prevent "stale" patches.

### 2. Android App — Reliability & Authority

#### [MODIFY] [DiagnosticAgent.kt](file:///C:/Users/LENOVO/Downloads/receipts-android/app/src/main/java/com/devdeck/app/ai/DiagnosticAgent.kt)
- **Canary Verification**: Implement a startup check to verify model throughput (TPS) and memory usage before marking the engine "Ready".
- **Grounded Identifier Check**: Hardening the regex and grounding logic to strictly reject hallucinated variables.

#### [MODIFY] [MainActivity.kt](file:///C:/Users/LENOVO/Downloads/receipts-android/app/src/main/java/com/devdeck/app/ui/MainActivity.kt)
- **Permission Modes**: Add UI for "Ask every time", "Allow for session", and "Always allow for project".
- **Pairing UI**: Add a screen to enter the desktop pairing secret.

### 3. Verification & Accuracy

#### [NEW] [benchmark_suite.py](file:///C:/Users/LENOVO/Downloads/receipts-android/benchmark_suite.py)
- **Regression Tests**: A suite of known Python errors (NullAccess, KeyError, etc.) to verify that patches are correctly generated and verified.

## Verification Plan

### Automated Tests
- Run `benchmark_suite.py` to ensure 100% success rate on known heuristic repairs.
- Mock WebSocket pairing to verify that unauthorized `repair` payloads are rejected by the relay.

### Manual Verification
- Deploy to iQOO 15.
- Perform a "Live Pulse" repair and manually trigger a failure to verify the snapshot-based rollback restores the file exactly.
- Verify that thermal/battery warnings appear during long inference sessions.
