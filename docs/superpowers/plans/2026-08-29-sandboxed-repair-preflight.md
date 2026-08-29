# Sandboxed Repair Preflight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify protocol-v2 repair candidates in an isolated copy of the trusted project before writing any live file.

**Architecture:** A focused `SandboxRunner` copies a small trusted project to a temporary directory, applies a candidate file only there, and runs the captured command in that directory. `PatchManager` derives the candidate content, invokes this gate for v2 repairs, then retains the existing live hash check, snapshot, rerun, and rollback flow.

**Tech Stack:** Python 3.11 standard library (`pathlib`, `tempfile`, `shutil`, `subprocess`, `dataclasses`, `unittest`)

**Spec:** `docs/superpowers/specs/2026-08-29-sandboxed-repair-preflight-design.md`

## Global Constraints

- Only protocol-v2 repairs with the captured trusted project root use the sandbox.
- Copy exclusions: `.git`, `__pycache__`, `venv`, `.venv`, `.devdeck`, `*.bak`, `*.devdeck-snapshot`.
- Sandbox timeout: 15 seconds; stdout and stderr: last 2,000 characters each.
- A rejected or timed-out sandbox repair leaves the live file byte-identical and creates no live snapshot.
- Existing live verification and rollback remain mandatory after sandbox success.

---

## File Structure

- Create `sandbox_runner.py`: temporary project copy, candidate write, bounded command execution, cleanup.
- Create `tests/test_sandbox_runner.py`: sandbox behavior tests.
- Modify `patch_manager.py`: candidate derivation and v2 sandbox gate before live snapshots.
- Modify `test_patch_manager.py`: PatchManager sandbox integration tests.
- Modify `relay_server.py`: pass the captured trusted root to PatchManager.
- Modify `tests/test_bridge_protocol.py`: trusted-root relay contract.
- Modify `README.md`: concise, honest two-gate safety description.

### Task 1: Build the Isolated Sandbox Runner

**Files:**
- Create: `sandbox_runner.py`
- Create: `tests/test_sandbox_runner.py`

**Interfaces:**
- Produces: `SandboxResult(passed: bool, exit_code: int | None, stdout: str, stderr: str, timed_out: bool)`.
- Produces: `SandboxRunner(project_root: Path, timeout_seconds: int = 15)`.
- Produces: `verify(relative_file: str, candidate_content: str, command: str) -> SandboxResult`.

- [ ] **Step 1: Write failing behavior tests**

```python
def test_verify_uses_candidate_only_in_temporary_copy(self):
    source = self.project / "src" / "answer.py"
    source.parent.mkdir()
    source.write_text("VALUE = 'original'\n", encoding="utf-8")

    result = SandboxRunner(self.project).verify(
        "src/answer.py", "VALUE = 'candidate'\n",
        "py -3.11 -c \"from src.answer import VALUE; raise SystemExit(VALUE != 'candidate')\"",
    )

    self.assertTrue(result.passed)
    self.assertEqual("VALUE = 'original'\n", source.read_text(encoding="utf-8"))

def test_timeout_is_reported_and_sandbox_is_removed(self):
    runner = SandboxRunner(self.project, timeout_seconds=0.01)
    result = runner.verify("src/answer.py", "VALUE = 1\n", "py -3.11 -c \"import time; time.sleep(1)\"")
    self.assertTrue(result.timed_out)
    self.assertFalse(runner.last_sandbox_path.exists())
```

- [ ] **Step 2: Verify the tests fail**

Run: `py -3.11 -m unittest tests.test_sandbox_runner -v`

Expected: FAIL because `sandbox_runner` does not exist.

- [ ] **Step 3: Implement the runner**

```python
@dataclass(frozen=True)
class SandboxResult:
    passed: bool
    exit_code: int | None
    stdout: str
    stderr: str
    timed_out: bool = False

class SandboxRunner:
    def verify(self, relative_file, candidate_content, command):
        sandbox = Path(tempfile.mkdtemp(prefix="devdeck_sandbox_"))
        self.last_sandbox_path = sandbox
        try:
            shutil.copytree(self.project_root, sandbox, dirs_exist_ok=True, ignore=self._ignore)
            (sandbox / relative_file).write_text(candidate_content, encoding="utf-8")
            result = subprocess.run(command, shell=True, cwd=sandbox, capture_output=True, text=True, timeout=self.timeout_seconds)
            return SandboxResult(result.returncode == 0, result.returncode, result.stdout[-2000:], result.stderr[-2000:])
        except subprocess.TimeoutExpired as error:
            return SandboxResult(False, None, (error.stdout or "")[-2000:], "sandbox timeout", True)
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)
```

- [ ] **Step 4: Verify the runner tests pass**

Run: `py -3.11 -m unittest tests.test_sandbox_runner -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sandbox_runner.py tests/test_sandbox_runner.py
git commit -m "feat: add isolated sandbox runner"
```

### Task 2: Add the PatchManager Sandbox Gate

**Files:**
- Modify: `patch_manager.py`
- Modify: `test_patch_manager.py`

**Interfaces:**
- Consumes: `SandboxRunner.verify(relative_file, candidate_content, command)`.
- Extends: `PatchManager.apply_repair(data, last_command, project_root=None)`.
- Produces: no live snapshot or write when v2 sandbox verification fails.

- [ ] **Step 1: Write failing integration tests**

```python
def test_failed_sandbox_repair_never_changes_live_file(self):
    original = "def value():\n    return 'original'\n"
    self.target.write_text(original, encoding="utf-8")
    success, error, _, snapshot = self.manager.apply_repair(
        self.protocol_v2_repair(code="return 'candidate'"),
        "py -3.11 -c \"raise SystemExit(1)\"", str(self.project),
    )
    self.assertFalse(success)
    self.assertIn("Sandbox verification failed", error)
    self.assertIsNone(snapshot)
    self.assertEqual(original.encode(), self.target.read_bytes())

def test_passing_sandbox_repair_enters_live_flow(self):
    success, error, _, snapshot = self.manager.apply_repair(
        self.protocol_v2_repair(code="return 'candidate'"),
        "py -3.11 -c \"raise SystemExit(0)\"", str(self.project),
    )
    self.assertTrue(success, error)
    self.assertIsNotNone(snapshot)
    self.assertIn("candidate", self.target.read_text(encoding="utf-8"))
```

- [ ] **Step 2: Verify the integration tests fail**

Run: `py -3.11 -m unittest test_patch_manager.TestPatchManager.test_failed_sandbox_repair_never_changes_live_file test_patch_manager.TestPatchManager.test_passing_sandbox_repair_enters_live_flow -v`

Expected: FAIL because `apply_repair` has no `project_root` parameter.

- [ ] **Step 3: Implement candidate derivation and the gate**

```python
def apply_repair(self, data, last_command, project_root=None):
    file_path = data.get("file", "")
    candidate = self._candidate_content(data, file_path)
    if candidate is None:
        return False, "Unable to construct candidate repair", file_path, None
    if data.get("protocol_version") == 2 and project_root:
        relative_file = Path(file_path).resolve().relative_to(Path(project_root).resolve()).as_posix()
        result = SandboxRunner(Path(project_root)).verify(relative_file, candidate, last_command)
        if not result.passed:
            return False, self._sandbox_failure_message(result), file_path, None
    return self._apply_live_repair(data, last_command, file_path, candidate)
```

Keep the existing SHA-256 check, snapshot, write, live rerun, and rollback in `_apply_live_repair`. Use the same derived candidate for sandbox and live writes.

- [ ] **Step 4: Verify PatchManager tests pass**

Run: `py -3.11 -m unittest test_patch_manager -v`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add patch_manager.py test_patch_manager.py
git commit -m "feat: preflight protocol repairs in sandbox"
```

### Task 3: Propagate the Captured Root Through the Relay

**Files:**
- Modify: `relay_server.py`
- Modify: `tests/test_bridge_protocol.py`

**Interfaces:**
- Consumes: `repair_for_incident(payload, incident_store) -> tuple[RepairRequest, dict, Path]`.
- The v2 repair call uses only `incident_data["project_root"]`, never a client-supplied root.

- [ ] **Step 1: Write a failing trusted-root contract test**

```python
def test_validated_repair_exposes_captured_project_root(self):
    repair, incident, target = repair_for_incident(payload, {incident_id: incident_data})
    self.assertEqual(project.resolve(), Path(incident["project_root"]))
    self.assertEqual(project / "src" / "example.py", target)
```

- [ ] **Step 2: Verify the contract test fails**

Run: `py -3.11 -m unittest tests.test_bridge_protocol.BridgeProtocolTest.test_validated_repair_exposes_captured_project_root -v`

Expected: FAIL until a complete v2 incident fixture and relay root propagation exist.

- [ ] **Step 3: Use the captured root for sandbox dispatch**

```python
repair, incident_data, target_path = repair_for_incident(data, incidents)
data = {**data, "file": str(target_path)}
success, error_msg, file_path, transaction_id = patch_manager.apply_repair(
    data, cmd_to_rerun, project_root=incident_data["project_root"]
)
```

- [ ] **Step 4: Verify the complete Python suite**

Run: `py -3.11 -m unittest discover -s tests -v; py -3.11 -m unittest -v test_git_transaction.py test_patch_manager.py test_errors.py`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add relay_server.py tests/test_bridge_protocol.py
git commit -m "feat: verify repairs against trusted sandbox roots"
```

### Task 4: Document and Verify the Feature

**Files:**
- Modify: `README.md`

**Interfaces:**
- Documents the local temporary-directory sandbox and the retained live rollback gate.

- [ ] **Step 1: Verify the README has no existing sandbox claim**

Run: `rg -n "sandbox|temporary local project copy|two-gate" README.md`

Expected: no existing matching safety description.

- [ ] **Step 2: Add this concise safety description**

```markdown
### Two-gate repair verification

DevDeck first tests a candidate repair in a temporary local copy of the trusted project. Only a passing candidate reaches the working copy, where DevDeck repeats verification and can restore its snapshot if the live environment differs. This is a local temporary-directory sandbox for small projects, not a container or virtual machine.
```

- [ ] **Step 3: Run final verification**

Run: `py -3.11 -m unittest discover -s tests -v; py -3.11 -m unittest -v test_git_transaction.py test_patch_manager.py test_errors.py; gradlew.bat assembleDebug --no-daemon; git diff --check`

Expected: Python tests pass, Android debug build succeeds, and the diff has no whitespace errors.

- [ ] **Step 4: Commit and push**

```bash
git add README.md
git commit -m "docs: explain two-gate repair verification"
git push
```

## Self-Review

- Spec coverage: Task 1 implements temporary copying, exclusions, timeouts, output capping, and cleanup. Task 2 makes sandbox success mandatory before live snapshots and writes. Task 3 supplies the trusted root from the incident. Task 4 documents the local-sandbox boundary and runs complete verification.
- Placeholder scan: no deferred behavior or unnamed interfaces remain.
- Type consistency: `SandboxResult`, `SandboxRunner.verify`, `PatchManager.apply_repair(..., project_root=None)`, and `repair_for_incident(...)->(..., Path)` use the same names throughout.
