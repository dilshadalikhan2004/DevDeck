# DevDeck Pocket Individual Product Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the existing DevDeck Pocket prototype into a safe, fully offline, mobile-first debugging and verified-repair product for individual developers on iQOO 15.

**Architecture:** Keep the existing Android MediaPipe app and Python bridge, but migrate implicit trust and global state to paired sessions, trusted project roots, incident-bound repairs, a versioned protocol, file-level snapshots, and structured lifecycle events. The phone authorizes repairs; the optional VS Code extension is viewer-only.

**Tech Stack:** Kotlin, Android SDK 34, ViewBinding, MediaPipe GenAI, OkHttp, Python 3, asyncio, websockets, unittest, Node.js, VS Code Extension API.

**Spec:** `docs/superpowers/specs/2026-08-29-devdeck-pocket-individual-product-design.md`

## Global Constraints

- All inference, context, history, and telemetry stay offline; no cloud service or remote logging.
- iQOO 15 is the release-validation device; min SDK 26 and target/compile SDK 34 stay unchanged.
- Preserve v1 `single_line` and `diff` compatibility during migration; protocol v2 is required for persistent auto-apply.
- Python is the only production-quality repair language. Other detected languages are experimental and never auto-applied.
- The bridge accepts only phone-authorized patch data; model output must never become a shell command.
- Every edit must use a canonical path under a trusted root, an expected SHA-256 content hash, and a file-level snapshot.
- No failed validation, timeout, unauthorized request, stale file, or malformed message may leave an edit on disk.
- Do not use `git checkout`, `git reset`, `git stash`, or the operating-system `patch` executable for repair transactions.
- Use `python -m unittest`; preserve unrelated worktree changes and stage only task files.

## File Structure

| Area | Files | Responsibility |
|---|---|---|
| Bridge boundaries | `bridge_security.py`, `bridge_protocol.py`, `pairing.py` | root confinement, repair validation, pairing |
| Bridge writes | `file_transaction.py`, `patch_manager.py` | snapshots, validation, patching, rollback |
| Bridge runtime | `devdeck.py`, `relay_server.py` | incident capture, authenticated sessions, event stream |
| Android domain | `model/RepairEnvelope.kt`, `RepairPermission.kt`, `PairingManager.kt`, `RepairAuditEvent.kt` | protocol, authorization, audit state |
| Android app | `MainActivity.kt`, `DiagnosticAgent.kt`, layouts | pairing, repair review, offline analysis |
| Optional editor | `vscode-extension/*` | paired incident viewer only |
| Validation | `tests/*`, Android unit/instrumented tests, `benchmarks/python/*` | regression, security, end-to-end benchmark |

---

### Task 1: Establish a non-regression baseline

**Files:** Create `tests/__init__.py`, `tests/test_existing_repair_contract.py`; modify `test_patch_manager.py`, `test_git_transaction.py`, `app/src/test/java/com/receipts/app/ai/HeuristicFlaggingEngineTest.kt`.

**Produces:** a repeatable baseline command for the legacy repair flow.

- [ ] Add legacy payload test:

```python
def test_legacy_single_line_payload_round_trips():
    payload = {"type":"repair", "patch_type":"single_line", "file":"example.py", "line":4, "code":"return value"}
    self.assertEqual(json.loads(json.dumps(payload))["patch_type"], "single_line")
```

- [ ] Add an Android heuristic regression for `AttributeError: 'NoneType'` and assert the existing guard repair.
- [ ] Run `python -m unittest test_patch_manager.py test_git_transaction.py` and `./gradlew.bat testDebugUnitTest assembleDebug`; record the passing baseline.
- [ ] Commit: `git add -- tests test_patch_manager.py test_git_transaction.py app/src/test/java/com/receipts/app/ai/HeuristicFlaggingEngineTest.kt` then `git commit -m "test: establish DevDeck repair regression baseline"`.

### Task 2: Add trusted-project path and fingerprint primitives

**Files:** Create `bridge_security.py`, `tests/test_bridge_security.py`.

**Produces:** `ProjectRoot`, `canonical_project_root(path)`, `resolve_project_file(project, relative_path)`, `sha256_file(path)`.

- [ ] Write failing tests that reject `../outside.py` and confirm a hash changes after source content changes.
- [ ] Run `python -m unittest tests.test_bridge_security -v`; expect missing-module failure.
- [ ] Implement:

```python
@dataclass(frozen=True)
class ProjectRoot:
    path: Path
    project_id: str

def resolve_project_file(project, relative_path):
    candidate = (project.path / relative_path).resolve(strict=True)
    if candidate == project.path or project.path not in candidate.parents or not candidate.is_file():
        raise ValueError("repair target is outside trusted project root")
    return candidate
```

- [ ] Implement `sha256_file` using `hashlib.sha256` and 64 KiB chunks.
- [ ] Rerun the test file; expect PASS.
- [ ] Commit: `git add -- bridge_security.py tests/test_bridge_security.py tests/__init__.py` then `git commit -m "feat: add trusted project path and file hash checks"`.

### Task 3: Replace destructive Git rollback with file-level transactions

**Files:** Create `file_transaction.py`, `tests/test_file_transaction.py`; modify `git_transaction_engine.py`, `test_git_transaction.py`.

**Produces:** `FileTransaction.create(target)`, `commit()`, `rollback()`, `original_sha256`.

- [ ] Write tests that modify a file after `create`, verify `rollback` restores byte-identical source, and verify `commit` keeps the new source while deleting the snapshot.
- [ ] Run `python -m unittest tests.test_file_transaction -v`; expect missing-module failure.
- [ ] Implement `FileTransaction` with `tempfile.mkstemp(dir=target.parent)`, `shutil.copy2`, and `Path.unlink(missing_ok=True)`.
- [ ] Change `GitTransactionEngine.rollback_transaction` to raise `RuntimeError("Git rollback is disabled for repairs; use FileTransaction.rollback")`; retain Git only for non-destructive metadata.
- [ ] Run `python -m unittest tests.test_file_transaction test_git_transaction.py -v`; expect PASS.
- [ ] Commit: `git add -- file_transaction.py tests/test_file_transaction.py git_transaction_engine.py test_git_transaction.py` then `git commit -m "feat: use file snapshots for safe repair rollback"`.

### Task 4: Define protocol v2 and incident-bound state

**Files:** Create `bridge_protocol.py`, `tests/test_bridge_protocol.py`; modify `devdeck.py`, `relay_server.py`.

**Produces:** `Incident` and `RepairRequest` records with incident ID, project ID, relative path, expected SHA-256, patch type, confidence, and protocol version.

- [ ] Write tests rejecting protocol versions other than 2, a single-line payload containing `diff_text`, a diff payload containing `code`, and a non-64-character expected hash.
- [ ] Run `python -m unittest tests.test_bridge_protocol -v`; expect missing-module failure.
- [ ] Implement strict `RepairRequest.from_dict`; validate `type == "repair"`, version 2, `patch_type in {"single_line", "diff"}`, and the exact required fields.
- [ ] Update `devdeck.py` to create `incident_id = str(uuid.uuid4())`, canonical project metadata, a project-relative error file, and an original hash when the target exists.
- [ ] Replace global `last_command` in `relay_server.py` with `incidents: dict[str, Incident]`; reject a repair that references an absent incident.
- [ ] Run protocol/security tests; expect PASS.
- [ ] Commit: `git add -- bridge_protocol.py tests/test_bridge_protocol.py devdeck.py relay_server.py` then `git commit -m "feat: add incident-bound repair protocol v2"`.

### Task 5: Harden PatchManager with pure-Python diff application

**Files:** Modify `patch_manager.py`, `test_patch_manager.py`; create `tests/test_patch_manager_security.py`.

**Consumes:** `ProjectRoot`, `RepairRequest`, `FileTransaction`.

**Produces:** `PatchManager.apply_repair(project, repair, command) -> tuple[bool, str | None]`.

- [ ] Write tests for: stale expected hash rejects before write; traversal rejects; verification command failure restores original source; malformed unified diff leaves source unchanged; valid diff works without invoking a `patch` executable.
- [ ] Run `python -m unittest tests.test_patch_manager_security -v`; expect old-interface failure.
- [ ] Resolve source using `resolve_project_file`; compare `sha256_file(target)` against `repair.expected_sha256` before snapshotting.
- [ ] Create a `FileTransaction`, build patched content in memory, run Python validation using `python -m py_compile` on a temporary `.py`, write only after validation, rerun the captured command, then commit or rollback.
- [ ] Implement hunk parsing with a regex for `@@ -start,count +start,count @@`; exact-match context/deleted lines, preserve line endings, reject mismatched hunks and more than 20 changed lines.
- [ ] Remove `subprocess.run(['patch', ...])` and all basename fallbacks.
- [ ] Run `python -m unittest test_patch_manager.py tests.test_patch_manager_security -v`; expect PASS.
- [ ] Commit: `git add -- patch_manager.py test_patch_manager.py tests/test_patch_manager_security.py` then `git commit -m "feat: harden project-scoped patch verification and rollback"`.

### Task 6: Require local pairing before bridge use

**Files:** Create `pairing.py`, `tests/test_pairing.py`; modify `relay_server.py`, `devdeck.py`, `README.md`.

**Produces:** expiring `PairingRecord`, `PairingStore.create(ttl_seconds)`, `PairingStore.verify(pairing_id, secret)` and authenticated WebSocket sessions.

- [ ] Write tests proving a random `token_urlsafe(32)` secret validates only for its matching ID and expires at its TTL.
- [ ] Run `python -m unittest tests.test_pairing -v`; expect missing-module failure.
- [ ] Implement memory-only pairing records using `secrets.token_urlsafe`; never write the bridge secret to logs.
- [ ] Require a `hello` message with pairing credentials before the relay accepts incident or repair JSON; close unauthenticated clients with WebSocket code 4001.
- [ ] Change default relay host from `0.0.0.0` to `127.0.0.1`; document explicit ADB reverse or local-Wi-Fi override.
- [ ] Run pairing tests and an async handshake test that confirms the dashboard cannot submit repair JSON without authenticating.
- [ ] Commit: `git add -- pairing.py tests/test_pairing.py relay_server.py devdeck.py README.md` then `git commit -m "feat: require local paired bridge sessions"`.

### Task 7: Add Android protocol, pairing, and permission domain objects

**Files:** Create `model/RepairPermission.kt`, `TrustedProject.kt`, `PairingManager.kt`, `RepairEnvelope.kt`, `RepairAuditEvent.kt`, `RepairEnvelopeTest.kt`; modify `MainActivity.kt`.

**Produces:** `ASK_EVERY_TIME`, `SESSION`, `TRUSTED_PROJECT`; SharedPreferences pairing storage; exact protocol-v2 JSON.

- [ ] Write unit test asserting a `RepairEnvelope` contains `protocol_version = 2`, incident ID, project ID, relative file path, hash, patch type, confidence, and exactly one patch representation.
- [ ] Run `./gradlew.bat testDebugUnitTest --tests "com.devdeck.app.model.RepairEnvelopeTest"`; expect compile failure until classes exist.
- [ ] Implement immutable data classes and JSON serialization. Reject construction of a single-line envelope with a diff or a diff envelope with replacement code.
- [ ] Implement `PairingManager` in the existing `devdeck` SharedPreferences namespace; expose `save`, `clear`, `isPaired`, and non-null credential accessors.
- [ ] Update WebSocket connection setup to send protocol-v2 `hello` before processing incidents and show a visible pairing-required status on rejection.
- [ ] Update repair sending to use `RepairEnvelope`; retain legacy JSON only behind an explicit demo-only setting defaulting off.
- [ ] Run `./gradlew.bat testDebugUnitTest assembleDebug`; expect PASS.
- [ ] Commit: `git add -- app/src/main/java/com/devdeck/app/model app/src/main/java/com/devdeck/app/ui/MainActivity.kt app/src/test/java/com/devdeck/app/model/RepairEnvelopeTest.kt` then `git commit -m "feat: add paired repair protocol and permission state"`.

### Task 8: Build the mobile repair permission and review flow

**Files:** Create `res/layout/dialog_repair_permission.xml`, `RepairPermissionFlowTest.kt`; modify `activity_main.xml`, `MainActivity.kt`.

**Produces:** explicit approval UI and constrained auto-authorization.

- [ ] Write an instrumentation test that renders a repair under `ASK_EVERY_TIME`, taps Apply, and asserts a dialog with `Ask every time`, `Allow for this session`, `Always allow for this project`, and `Cancel` is visible.
- [ ] Run `./gradlew.bat connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.devdeck.app.RepairPermissionFlowTest`; expect failure until device/test configuration and dialog exist.
- [ ] Add the dialog with scope copy stating that path, hash, patch validation, verification, and rollback still run for all choices.
- [ ] Implement `mayAutoApply` with a single `AUTO_APPLY_CONFIDENCE = 0.90f` constant and require trusted-project permission, exact project ID, Python language, and a single-file/single-line repair.
- [ ] Ensure all multi-file, diff, experimental-language, unpaired, or stale incidents require explicit approval.
- [ ] Run Android unit/instrumentation tests and `assembleDebug`; expect PASS.
- [ ] Commit: `git add -- app/src/main/res/layout/dialog_repair_permission.xml app/src/main/res/layout/activity_main.xml app/src/main/java/com/devdeck/app/ui/MainActivity.kt app/src/androidTest/java/com/devdeck/app/RepairPermissionFlowTest.kt` then `git commit -m "feat: add scoped mobile repair authorization"`.

### Task 9: Refactor and test structured on-device response parsing

**Files:** Modify `ai/DiagnosticAgent.kt`, `model/DiagnosticResult.kt`; create `ai/RepairResponseParser.kt`, `RepairResponseParserTest.kt`.

**Produces:** `DiagnosticResult.confidence`, `DiagnosticResult.incidentId`, and a focused parser that accepts only bounded, grounded repairs.

- [ ] Write tests for a valid fix, a valid diff, unknown introduced identifier, duplicate original line, malformed markers, more than 20 changed lines, and model output containing a command.
- [ ] Run the parser test target; expect failure until the parser is extracted.
- [ ] Extract parsing from `DiagnosticAgent` into `RepairResponseParser.parse(raw, originalLine, allowedIdentifiers): ParsedRepair`.
- [ ] Require exactly one `<<<FIX>>>...<<<END>>>` or `<<<DIFF>>>...<<<END>>>` block. Reject filenames, shell commands, markdown fences, and anything outside the structured output.
- [ ] Keep the current deterministic heuristic engine as the fallback for every rejected/unavailable model response.
- [ ] Update the prompt to request one structured block only and include only trace, focused source window, original line, and project rules.
- [ ] Run `./gradlew.bat testDebugUnitTest assembleDebug`; expect PASS.
- [ ] Commit: `git add -- app/src/main/java/com/devdeck/app/ai app/src/main/java/com/devdeck/app/model/DiagnosticResult.kt app/src/test/java/com/devdeck/app/ai/RepairResponseParserTest.kt` then `git commit -m "feat: harden on-device repair response validation"`.

### Task 10: Add lifecycle events and local audit history

**Files:** Modify `relay_server.py`, `DiagnosticHistory.kt`; create `tests/test_relay_events.py`, `DiagnosticHistoryTest.kt`.

**Produces:** event types `incident_received`, `repair_authorized`, `patch_validated`, `verification_started`, `repair_verified`, `repair_rolled_back`, `repair_rejected`; locally persisted Android audit events.

- [ ] Write Python event-order test for failed verification: authorization → validation → verification started → rollback.
- [ ] Write Android test asserting rollback reason remains visible in the history summary.
- [ ] Emit a structured event with `incident_id`, timestamp, and reason at each bridge transition; do not use terminal text as the source of state.
- [ ] Store and display `RepairAuditEvent` in the existing private history UI.
- [ ] Run Python event test and Android history unit test; expect PASS.
- [ ] Commit: `git add -- relay_server.py tests/test_relay_events.py app/src/main/java/com/devdeck/app/model app/src/test/java/com/devdeck/app/model/DiagnosticHistoryTest.kt` then `git commit -m "feat: record verified repair lifecycle events"`.

### Task 11: iQOO 15 model readiness and degraded-mode behavior

**Files:** Modify `DiagnosticAgent.kt`, `ModelManager.kt`, `MainActivity.kt`; create `ModelManagerTest.kt`.

**Produces:** model states `MISSING`, `LOADING`, `READY`, `UNSUPPORTED`, `DEGRADED`, cancelable analysis, and truthful user-visible readiness state.

- [ ] Write a test that a missing model path returns `MISSING` without creating inference, and a failed canary restores the previously selected model path.
- [ ] Run test target; expect failure until `ModelState` is defined.
- [ ] Add `ModelState` and separate file existence, engine initialization, and canary inference; keep current model manager UI but replace optimistic labels with state-driven labels.
- [ ] Add a cancel action to the analysis coroutine and return the diagnosis card/UI to an enabled state on cancellation.
- [ ] Preserve no-network operation and label heuristic-only operation as degraded rather than AI-ready.
- [ ] Run `./gradlew.bat testDebugUnitTest assembleDebug`; expect PASS.
- [ ] Commit: `git add -- app/src/main/java/com/devdeck/app/ai/DiagnosticAgent.kt app/src/main/java/com/devdeck/app/model/ModelManager.kt app/src/main/java/com/devdeck/app/ui/MainActivity.kt app/src/test/java/com/devdeck/app/model/ModelManagerTest.kt` then `git commit -m "feat: add explicit offline model readiness states"`.

### Task 12: Finish the optional VS Code paired-session advantage

**Files:** Modify `vscode-extension/package.json`, `extension.js`, `README.md`; create `vscode-extension/test/extension.test.js`.

**Produces:** `devdeck.openIncident` and `devdeck.showPairing` commands plus a read-only incident/diff panel.

- [ ] Write test asserting the extension registers `devdeck.openIncident` and does not register `devdeck.applyRepair`.
- [ ] Run `npm test --prefix vscode-extension`; expect failure until test configuration exists.
- [ ] Implement a Webview displaying current incident, source location, proposed patch, lifecycle status, and `Review on phone` pairing/deep-link action.
- [ ] Ensure no extension code sends a `repair` payload or calls a shell command.
- [ ] Run extension test and configured lint/package command; expect PASS.
- [ ] Commit: `git add -- vscode-extension/package.json vscode-extension/extension.js vscode-extension/README.md vscode-extension/test/extension.test.js` then `git commit -m "feat: add paired VS Code incident viewer"`.

### Task 13: Create an offline Python repair benchmark

**Files:** Create `benchmarks/python/fixtures/`, `benchmarks/python/manifest.json`, `benchmarks/run_python_benchmark.py`, `tests/test_python_benchmark.py`; modify `README.md`.

**Produces:** a benchmark reporting `verified`, `rejected`, and `rolled_back` statuses while confirming final source is unchanged for the latter two.

- [ ] Add manifest cases `none_attribute`, `zero_division`, `stale_hash`, and `bad_diff` with expected statuses.
- [ ] Write runner test asserting all expected status classes appear and a rejected/rolled-back fixture equals its original content.
- [ ] Run test; expect missing-runner failure.
- [ ] Implement a runner that copies each fixture to a temporary trusted project, executes the relevant bridge/patch scenario, records result, and checks final contents.
- [ ] Run `python -m unittest tests.test_python_benchmark -v` and `python benchmarks/run_python_benchmark.py`; expect all manifest results to match.
- [ ] Commit: `git add -- benchmarks tests/test_python_benchmark.py README.md` then `git commit -m "test: add offline Python repair benchmark"`.

### Task 14: Offline release validation and demo documentation

**Files:** Modify `README.md`, `office-kit-dashboard/index.html`; create `docs/iqoo15-validation.md`, `docs/demo-script.md`.

**Produces:** local setup/runbook, iQOO 15 evidence, and an offline demo that cannot bypass pairing.

- [ ] Update README with pairing, trusted-project registration, model verification, CLI-only use, and optional VS Code viewer setup.
- [ ] Add this exact iQOO 15 checklist: airplane mode; cold model canary; warm diagnosis; Ask Every Time prompt; session reset; external-path rejection; byte-identical rollback; reconnect rejects unpaired client; degraded model state.
- [ ] Update dashboard copy to call it a local monitoring view and remove/disable its staged repair-trigger route until it can authenticate as a non-authorizing viewer.
- [ ] Run `python -m unittest discover -s tests -v`, `python -m unittest test_patch_manager.py test_git_transaction.py -v`, `./gradlew.bat testDebugUnitTest assembleDebug`, and `npm test --prefix vscode-extension`; expect all included targets to PASS.
- [ ] Complete checklist on iQOO 15 in airplane mode and record cold/warm inference timings, benchmark outcomes, and rollback proof in `docs/iqoo15-validation.md`.
- [ ] Commit: `git add -- README.md docs/iqoo15-validation.md docs/demo-script.md office-kit-dashboard/index.html` then `git commit -m "docs: finalize offline DevDeck Pocket release validation"`.

## Completion Gate

Do not call the product release-ready until Task 14’s automated checks are green and the iQOO 15 checklist passes in airplane mode. If a compatible model does not load, the app must show degraded mode and must not claim on-device AI repair is available.

