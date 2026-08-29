# DevDeck Pocket — Individual Developer Product Design

**Status:** Approved direction; implementation plan pending review  
**Target device:** iQOO 15  
**Product principle:** fully offline inference and local-only code transport

## 1. Product definition

DevDeck Pocket is a mobile-first debugging and verified-repair companion for individual developers. A developer runs a command on their computer; a small local bridge captures a failure and sends narrowly scoped context to the paired Android phone. The phone diagnoses the issue using an on-device LLM and deterministic safety logic, then returns a structured repair proposal. The developer approves it or permits it under a scoped repair mode. The bridge validates, applies, reruns the command, and rolls back automatically on failure.

The Android app is the primary product. A VS Code extension is optional convenience; it may surface the same incident and open the relevant file/diff, but cannot create or apply a repair without the paired phone’s authorization.

## 2. Goals and non-goals

### Goals

- Run entirely offline: no cloud model, telemetry, code upload, or remote relay.
- Make the iQOO 15 a first-class on-device AI endpoint using the existing MediaPipe-compatible model runtime.
- Preserve the existing single-line and unified-diff repair path while increasing safety, determinism, and test coverage.
- Support a complete, understandable loop: detect → diagnose → review/authorize → patch → verify → retain or roll back.
- Make Python the production-quality, demo-ready language path. Other language detection remains experimental until language-specific validation exists.
- Provide three explicit repair permissions:
  - Ask every time
  - Allow for this session
  - Always allow for this project

### Non-goals for the MVP

- Cloud inference, SaaS accounts, team sharing, or server-hosted source indexing.
- Arbitrary shell-command generation or execution from model output.
- Multi-file autonomous repairs.
- General-purpose IDE replacement.
- A claim of broad Android compatibility before iQOO 15 validation.

## 3. Architecture

```text
Terminal / optional VS Code extension
              │ USB/ADB or local Wi-Fi
              ▼
Desktop Bridge (localhost only)
  - failure capture and source slicing
  - project-root enforcement
  - patch validation, backup, verification, rollback
              │ paired, authenticated local WebSocket
              ▼
iQOO 15 Android app
  - model runtime + heuristic safety net
  - incident/diff/permission UI
  - trusted-project and session authority
  - local history and model diagnostics
```

### Authority boundaries

- The phone is the repair authority: only it can authorize a patch.
- The bridge is an execution agent: it cannot invent patches or run model-authored commands.
- The extension is a viewer/control surface: it cannot bypass the phone or bridge safeguards.
- Both phone and bridge operate on the local network only; pairing binds a specific phone installation to a bridge instance.

## 4. Primary user flow

1. User pairs phone and computer through a one-time local pairing secret or QR code.
2. User selects/trusts a local project root on the bridge and configures the repair permission mode in the app.
3. User runs a command through `devdeck.py` or the extension. On nonzero exit, the bridge captures the last relevant error lines, target path/line, original content fingerprint, and a small source window.
4. The app runs on-device inference. If the model is unavailable or the output fails safety checks, the existing deterministic heuristic engine is used when it can form a precise repair.
5. App shows the error, diagnosis, structured diff, confidence/risk level, and proposed verification command.
6. Depending on permission mode, the user approves or the app authorizes an eligible patch automatically.
7. The bridge confirms the expected file content still matches, validates and applies the patch transactionally, runs syntax checks, reruns the original failing command, and reports success or rollback.
8. The app stores a local audit event for every proposal, approval decision, verification outcome, and rollback.

## 5. Android product scope

### Screens

1. **Pair and status:** bridge connection, pairing state, active model, trusted project, thermal/battery warning, and reconnect controls.
2. **Live incident:** trace, source context, root cause, structured patch/diff, confidence, and repair controls.
3. **Permission prompt:** Ask once / session / trusted project choices; scope and safety constraints are plainly displayed.
4. **History:** locally stored incident timeline with proposed/applied/rejected/verified/rolled-back states and restore details.
5. **Model and project settings:** supported MediaPipe model paths, canary verification, tokens/sec, local project rules, trusted-project revocation, and diagnostics.

### iQOO 15 operational requirements

- Maintain the existing on-device MediaPipe LLM integration and model-manager UI.
- Warm up the model only after explicit user action or active pairing; expose cold/warm state.
- Surface thermal, low-battery, and memory-pressure state before long inference.
- Keep inference cancelable and never block normal phone navigation.
- Validate a supported model with a canary prompt before marking it ready.

## 6. Repair contract and safety

The model must return data, never instructions for arbitrary execution. The bridge accepts only a versioned structured repair payload:

```json
{
  "type": "repair",
  "protocol_version": 2,
  "project_id": "stable-local-project-fingerprint",
  "file": "relative/path.py",
  "expected_sha256": "file-content-hash-before-edit",
  "patch_type": "single_line|diff",
  "line": 42,
  "code": "optional single replacement line",
  "diff_text": "optional unified diff",
  "confidence": 0.0,
  "verification": {"command": "original captured command"}
}
```

Acceptance sequence:

1. Schema/version validation.
2. Paired-device and permission validation.
3. Project identity and canonical-path containment validation.
4. Expected-content hash validation to prevent stale edits.
5. Strict patch limits: one file for automated flow, small diff, no binary content, no traversal paths.
6. Model-output checks: diff parsing, identifier grounding where applicable, and language-aware syntax validation.
7. Transaction snapshot before write.
8. Rerun the captured original command with a timeout.
9. Keep the patch only on success; otherwise roll back and report why.

`Always allow for this project` never bypasses these checks. Multi-file changes and repairs outside the known safe scope always require explicit approval.

## 7. AI accuracy strategy

- Preserve existing behavior as a compatibility baseline; write tests before changing repair protocol/parsing logic.
- Prompt with only the error, targeted source window, failing line, relevant project rules, and optional related test context.
- Use strict markers/JSON output rather than prose and reject malformed output.
- Retain the heuristic engine as a deterministic fallback for known high-confidence error shapes.
- Start with Python benchmarks covering the current repair patterns plus regressions: null access, type mismatch, missing keys, zero division, index errors, syntax/indentation failures, and rejection cases.
- Record repair outcomes locally only: proposed, accepted, rejected, applied, passed verification, failed verification, and rolled back.

## 8. Desktop bridge scope

The existing Python bridge/CLI remains the basis but gains:

- local-only default binding and authenticated paired sessions;
- trusted project registration and project-root confinement;
- canonical relative paths plus pre-edit content hashes;
- versioned repair payload support with backward-compatible handling of the current payload;
- explicit structured status events for each transaction stage;
- non-destructive backup/rollback behavior that preserves user work;
- reliable Python validation using `py_compile` plus rerun of the original command.

The current Git transaction implementation requires hardening before release: rollback must not discard unrelated user edits. The preferred transaction strategy is a file-level snapshot/restore for edited files, with Git metadata used for visibility rather than using broad `git checkout` on a dirty workspace.

## 9. Optional VS Code extension

The extension is a product advantage only when the app is paired:

- starts/surfaces the local bridge session;
- displays current incident, result, and local repair history;
- opens the targeted file and shows the phone-authorized diff;
- deep-links/QR-pairs with DevDeck Pocket;
- cannot send a repair unless the paired phone has authorized it.

No extension is required for the CLI + app workflow.

## 10. Reliability and test strategy

### Automated

- Python unit tests for path containment, payload schema, stale-file hashes, patch limits, backup/rollback, and Python verification.
- End-to-end local relay tests: failure capture → simulated phone authorization → patch outcome event.
- Android unit tests for repair parsing, permissions, project identity, history persistence, and heuristic regressions.
- Instrumented Android tests for incident review, permission selection, and model-unavailable fallback.

### Device validation

- iQOO 15 acceptance run with the installed model: cold start, warm start, reconnect, thermal/low-memory behavior, and offline-only verification.
- Curated Python bug suite: no incorrect write is retained; every failed verification restores the exact original file.
- Demo rehearsal without network access.

## 11. Delivery phases

### Phase A — Safe core (first release gate)

1. Add regression coverage around the existing bridge, patch manager, and heuristic engine.
2. Introduce a shared, versioned repair contract while retaining current payload compatibility.
3. Add project-root checks, file fingerprints, bounded repair policy, and safer file-level rollback.
4. Emit structured bridge lifecycle events.

### Phase B — Mobile-first experience

1. Add pairing/trusted-project/session state and repair permission UI.
2. Add robust incident, patch-review, and local history states.
3. Add iQOO 15 model readiness, warm-up, cancellation, and device-health diagnostics.

### Phase C — Accuracy and demo hardening

1. Build the Python repair benchmark and negative tests.
2. Refine prompts and heuristic fallbacks using only local benchmark results.
3. Validate failure/rollback cases on-device and rehearse the offline demo.

### Phase D — Optional VS Code advantage

1. Complete the extension as a paired-session viewer and editor bridge.
2. Add deep links and phone-authorized diff display.

## 12. Release criteria

The MVP is ready when, on iQOO 15 and without internet:

- pairing/reconnect is reliable over the selected local transport;
- the Android app displays incidents and offers all three permission modes;
- approved Python repairs are constrained to a trusted root and verified end-to-end;
- malformed, stale, invalid, or failing repairs never remain on disk;
- rollback restores user files exactly;
- model-unavailable behavior is clear and deterministic fallback cases work;
- the CLI path works without VS Code, and the extension adds value without authority escalation.

