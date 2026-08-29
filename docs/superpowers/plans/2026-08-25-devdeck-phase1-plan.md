# DevDeck Pocket - Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement multi-line unified diff repairs, the Model Manager UI in the Android app, and Git-assisted transactional rollback with syntax validation on the Python relay server.

**Architecture:**
- Create decoupled classes `git_transaction_engine.py` and `patch_manager.py` to handle the patching and rollback transactions on the host machine.
- Update `DiagnosticAgent.kt` to generate, validate, and parse unified diff strings, while checking safety grounding.
- Create `ModelManager.kt`, `ModelSettingsActivity.kt`, and associated XML resources in the Android app to allow switching model paths and running canary tests dynamically.

**Tech Stack:** Kotlin, Android ViewBinding, MediaPipe LLM Inference SDK, Python 3, asyncio, websockets, Git, system patch/diff command.

**Spec Reference:** `docs/superpowers/specs/2026-08-25-devdeck-phase1-design.md`

## Global Constraints
- **Android Target SDK:** 34
- **Android Min SDK:** 26
- **Model Path Default:** `/data/local/tmp/gemma-2b-it-gpu.bin`
- **WebSocket Protocol:** ws://localhost:8765
- **Git Integration:** Execute git CLI subprocesses and handle non-git fallback using temp file-safe directory backup mechanism under `.devdeck/backups/`.

---

## Task List

### Task 1: Git Transaction Engine

**Files:**
- Create: `git_transaction_engine.py`
- Create: `test_git_transaction.py`

**Interfaces:**
- Produces: 
  - `GitTransactionEngine.is_git_repo() -> bool`
  - `GitTransactionEngine.create_transaction(file_path: str) -> str` (returns transaction_id)
  - `GitTransactionEngine.commit_transaction(transaction_id: str) -> None`
  - `GitTransactionEngine.rollback_transaction(file_path: str, transaction_id: str) -> None`
  - `FallbackBackupManager.create_backup(file_path: str) -> str` (returns backup_path)
  - `FallbackBackupManager.commit(backup_path: str) -> None`
  - `FallbackBackupManager.rollback(file_path: str, backup_path: str) -> None`

- [ ] **Step 1: Write the failing unit tests for git transaction logic**

Create `test_git_transaction.py`:
```python
import os
import shutil
import unittest
from git_transaction_engine import GitTransactionEngine, FallbackBackupManager

class TestGitTransactionEngine(unittest.TestCase):
    def setUp(self):
        self.engine = GitTransactionEngine()
        self.backup_mgr = FallbackBackupManager(backup_dir="test_backups")
        self.test_file = "test_file.txt"
        with open(self.test_file, "w") as f:
            f.write("Line 1\nLine 2\nLine 3\n")

    def tearDown(self):
        if os.path.exists(self.test_file):
            os.remove(self.test_file)
        if os.path.exists("test_backups"):
            shutil.rmtree("test_backups")

    def test_backup_and_rollback(self):
        backup_path = self.backup_mgr.create_backup(self.test_file)
        self.assertTrue(os.path.exists(backup_path))
        
        # Modify file
        with open(self.test_file, "w") as f:
            f.write("Corrupted content\n")
        
        # Rollback
        self.backup_mgr.rollback(self.test_file, backup_path)
        with open(self.test_file, "r") as f:
            content = f.read()
        self.assertEqual(content, "Line 1\nLine 2\nLine 3\n")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m unittest test_git_transaction.py`  
Expected: `ImportError: No module named 'git_transaction_engine'`

- [ ] **Step 3: Write git transaction and fallback backup engines**

Create `git_transaction_engine.py`:
```python
import os
import subprocess
import shutil
from pathlib import Path
from datetime import datetime

class GitTransactionEngine:
    def __init__(self):
        self._is_git = None
    
    def is_git_repo(self) -> bool:
        if self._is_git is not None:
            return self._is_git
        try:
            result = subprocess.run(
                ['git', 'rev-parse', '--is-inside-work-tree'],
                capture_output=True,
                text=True,
                timeout=2
            )
            self._is_git = result.returncode == 0
            return self._is_git
        except Exception:
            self._is_git = False
            return False
    
    def create_transaction(self, file_path: str) -> str:
        if not self.is_git_repo():
            return None
        try:
            result = subprocess.run(
                ['git', 'status', '--porcelain', file_path],
                capture_output=True,
                text=True,
                timeout=2
            )
            has_changes = bool(result.stdout.strip())
            
            if has_changes:
                stash_msg = f"devdeck_pre_repair_{datetime.now().timestamp()}"
                subprocess.run(
                    ['git', 'stash', 'push', '-u', '-m', stash_msg, file_path],
                    capture_output=True,
                    timeout=5
                )
                print(f"[Git] Stashed: {stash_msg}")
                return stash_msg
            else:
                result = subprocess.run(
                    ['git', 'rev-parse', 'HEAD'],
                    capture_output=True,
                    text=True,
                    timeout=2
                )
                head = result.stdout.strip()
                print(f"[Git] No changes, HEAD: {head[:8]}")
                return head
        except Exception as e:
            print(f"[Git] Transaction creation error: {e}")
            return None
    
    def commit_transaction(self, transaction_id: str):
        if not transaction_id:
            return
        if transaction_id.startswith("devdeck_pre_repair_"):
            try:
                result = subprocess.run(
                    ['git', 'stash', 'list'],
                    capture_output=True,
                    text=True,
                    timeout=2
                )
                stash_list = result.stdout.splitlines()
                stash_ref = None
                for line in stash_list:
                    if transaction_id in line:
                        stash_ref = line.split(':')[0].strip()
                        break
                if stash_ref:
                    subprocess.run(
                        ['git', 'stash', 'drop', stash_ref],
                        capture_output=True,
                        timeout=5
                    )
                    print(f"[Git] Dropped stash: {stash_ref}")
            except Exception as e:
                print(f"[Git] Commit transaction error: {e}")
    
    def rollback_transaction(self, file_path: str, transaction_id: str):
        if not transaction_id:
            return
        try:
            subprocess.run(
                ['git', 'checkout', 'HEAD', '--', file_path],
                capture_output=True,
                timeout=3
            )
            print(f"[Git] Rolled back: {file_path}")
            
            if transaction_id.startswith("devdeck_pre_repair_"):
                result = subprocess.run(
                    ['git', 'stash', 'list'],
                    capture_output=True,
                    text=True,
                    timeout=2
                )
                stash_list = result.stdout.splitlines()
                stash_ref = None
                for line in stash_list:
                    if transaction_id in line:
                        stash_ref = line.split(':')[0].strip()
                        break
                if stash_ref:
                    subprocess.run(
                        ['git', 'stash', 'pop', stash_ref],
                        capture_output=True,
                        timeout=5
                    )
                    print(f"[Git] Restored stash: {stash_ref}")
        except Exception as e:
            print(f"[Git] Rollback error: {e}")

class FallbackBackupManager:
    def __init__(self, backup_dir=".devdeck/backups"):
        self.backup_dir = Path(backup_dir)
        self.backup_dir.mkdir(parents=True, exist_ok=True)
    
    def create_backup(self, file_path: str) -> str:
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        file_name = Path(file_path).name
        backup_path = self.backup_dir / f"{file_name}.{timestamp}.bak"
        shutil.copy2(file_path, backup_path)
        print(f"[Backup] Created: {backup_path}")
        return str(backup_path)
    
    def commit(self, backup_path: str):
        print(f"[Backup] Kept: {backup_path}")
    
    def rollback(self, file_path: str, backup_path: str):
        if os.path.exists(backup_path):
            shutil.copy2(backup_path, file_path)
            print(f"[Backup] Restored from: {backup_path}")
        else:
            print(f"[Backup] Warning: backup not found: {backup_path}")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python -m unittest test_git_transaction.py`  
Expected: `Ran 1 test... OK`

- [ ] **Step 5: Commit**

```bash
git add git_transaction_engine.py test_git_transaction.py
git commit -m "feat: add GitTransactionEngine and FallbackBackupManager

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 2: Patch Manager with Diff Support

**Files:**
- Create: `patch_manager.py`
- Create: `test_patch_manager.py`
- Modify: `relay_server.py`

**Interfaces:**
- Consumes: `GitTransactionEngine`, `FallbackBackupManager` from `git_transaction_engine.py`
- Produces: `PatchManager.apply_repair(data: dict, last_command: str) -> tuple[bool, str, str, str]`

- [ ] **Step 1: Write failing tests for PatchManager**

Create `test_patch_manager.py`:
```python
import unittest
import os
from patch_manager import PatchManager

class TestPatchManager(unittest.TestCase):
    def setUp(self):
        self.patch_mgr = PatchManager()
        self.test_file = "temp_test.py"
        with open(self.test_file, "w") as f:
            f.write("def get_token(user):\n    print('test')\n    return user.token\n")

    def tearDown(self):
        if os.path.exists(self.test_file):
            os.remove(self.test_file)

    def test_dry_run_compile_valid(self):
        content = "def test():\n    pass\n"
        ok, err = self.patch_mgr.dry_run_compile_check(self.test_file, content)
        self.assertTrue(ok)

    def test_dry_run_compile_invalid(self):
        content = "def test(\n    pass"  # Missing closing paren
        ok, err = self.patch_mgr.dry_run_compile_check(self.test_file, content)
        self.assertFalse(ok)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python -m unittest test_patch_manager.py`  
Expected: `ImportError: No module named 'patch_manager'`

- [ ] **Step 3: Implement PatchManager**

Create `patch_manager.py`:
```python
import os
import subprocess
import tempfile
from pathlib import Path
from git_transaction_engine import GitTransactionEngine, FallbackBackupManager

class PatchManager:
    def __init__(self):
        self.git_engine = GitTransactionEngine()
        self.fallback_backup = FallbackBackupManager()

    def apply_repair(self, data, last_command):
        patch_type = data.get("patch_type", "single_line")
        file_path = data.get("file", "")
        
        if not os.path.exists(file_path):
            rel_path = os.path.basename(file_path)
            if os.path.exists(rel_path):
                file_path = rel_path
            else:
                return False, f"File not found: {file_path}", None, None

        if patch_type == "single_line":
            return self.apply_single_line_repair(data, last_command, file_path)
        elif patch_type == "diff":
            return self.apply_diff_patch(data, last_command, file_path)
        else:
            return False, f"Unknown patch_type: {patch_type}", None, None

    def apply_single_line_repair(self, data, last_command, file_path):
        line_num = data.get("line")
        new_code = data.get("code", "")
        
        if not line_num or not new_code:
            return False, "Invalid single-line payload", file_path, None
        
        if "\n" in new_code.strip() or "\r" in new_code.strip():
            return False, "Multi-line fix in single-line mode rejected", file_path, None
        
        if self.git_engine.is_git_repo():
            transaction_id = self.git_engine.create_transaction(file_path)
        else:
            transaction_id = self.fallback_backup.create_backup(file_path)

        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                lines = f.readlines()
            
            if not (1 <= line_num <= len(lines)):
                self._rollback(file_path, transaction_id)
                return False, f"Line {line_num} out of range", file_path, transaction_id
            
            old_line = lines[line_num - 1]
            indent = old_line[:len(old_line) - len(old_line.lstrip())]
            lines[line_num - 1] = f"{indent}{new_code.strip()}\n"
            
            with open(file_path, 'w', encoding='utf-8') as f:
                f.writelines(lines)
            
            if last_command:
                if not self.rerun_command(last_command):
                    self._rollback(file_path, transaction_id)
                    return False, "Rerun failed", file_path, transaction_id
            
            self._commit(transaction_id)
            return True, None, file_path, transaction_id
        except Exception as e:
            self._rollback(file_path, transaction_id)
            return False, str(e), file_path, transaction_id

    def apply_diff_patch(self, data, last_command, file_path):
        diff_text = data.get("diff_text")
        if not diff_text:
            return False, "No diff_text", file_path, None

        if self.git_engine.is_git_repo():
            transaction_id = self.git_engine.create_transaction(file_path)
        else:
            transaction_id = self.fallback_backup.create_backup(file_path)

        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                original = f.read()
            
            patched = self._apply_diff_to_string(original, diff_text)
            if patched is None:
                self._rollback(file_path, transaction_id)
                return False, "Diff application failed", file_path, transaction_id

            ok, err = self.dry_run_compile_check(file_path, patched)
            if not ok:
                self._rollback(file_path, transaction_id)
                return False, f"Syntax check failed: {err}", file_path, transaction_id

            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(patched)

            if last_command:
                if not self.rerun_command(last_command):
                    self._rollback(file_path, transaction_id)
                    return False, "Rerun failed after patch", file_path, transaction_id

            self._commit(transaction_id)
            return True, None, file_path, transaction_id
        except Exception as e:
            self._rollback(file_path, transaction_id)
            return False, str(e), file_path, transaction_id

    def _apply_diff_to_string(self, original: str, diff_text: str):
        with tempfile.NamedTemporaryFile(mode='w', suffix='.tmp', delete=False, encoding='utf-8') as tmp:
            tmp.write(original)
            tmp_path = tmp.name
        
        with tempfile.NamedTemporaryFile(mode='w', suffix='.patch', delete=False, encoding='utf-8') as pf:
            patch_content = f"--- a/temp\n+++ b/temp\n{diff_text}\n"
            pf.write(patch_content)
            patch_path = pf.name

        try:
            result = subprocess.run(
                ['patch', tmp_path, patch_path],
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode != 0:
                print(f"[PatchManager] patch command failed: {result.stderr}")
                return None
            
            with open(tmp_path, 'r', encoding='utf-8') as f:
                return f.read()
        except Exception as e:
            print(f"[PatchManager] Error applying diff: {e}")
            return None
        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)
            if os.path.exists(patch_path):
                os.unlink(patch_path)

    def dry_run_compile_check(self, file_path: str, content: str):
        ext = os.path.splitext(file_path)[1].lower()
        if ext == '.py':
            with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False, encoding='utf-8') as tmp:
                tmp.write(content)
                tmp_path = tmp.name
            try:
                result = subprocess.run(
                    ['python', '-m', 'py_compile', tmp_path],
                    capture_output=True,
                    text=True,
                    timeout=3
                )
                return (result.returncode == 0, result.stderr if result.returncode != 0 else "")
            finally:
                if os.path.exists(tmp_path):
                    os.unlink(tmp_path)
        elif ext in ['.js', '.ts', '.kt', '.java']:
            if content.count('{') != content.count('}'):
                return False, "Unmatched braces"
            return True, ""
        return True, ""

    def rerun_command(self, command: str) -> bool:
        print(f"[PatchManager] Rerunning: {command}")
        try:
            result = subprocess.run(
                command,
                shell=True,
                capture_output=True,
                text=True,
                timeout=8
            )
            success = result.returncode == 0
            print(f"[PatchManager] Rerun {'SUCCESS' if success else 'FAILED'}")
            return success
        except subprocess.TimeoutExpired:
            print("[PatchManager] Rerun TIMEOUT")
            return False
        except Exception as e:
            print(f"[PatchManager] Rerun error: {e}")
            return False

    def _commit(self, transaction_id):
        if self.git_engine.is_git_repo():
            self.git_engine.commit_transaction(transaction_id)
        else:
            self.fallback_backup.commit(transaction_id)

    def _rollback(self, file_path, transaction_id):
        if self.git_engine.is_git_repo():
            self.git_engine.rollback_transaction(file_path, transaction_id)
        else:
            self.fallback_backup.rollback(file_path, transaction_id)
```

- [ ] **Step 4: Run tests**

Run: `python -m unittest test_patch_manager.py`  
Expected: `OK`

- [ ] **Step 5: Integrate PatchManager into relay_server.py**

Read `relay_server.py` lines 1-50 and lines 140-215 to understand the repair handling block. Then modify:

```python
# At top of relay_server.py, add:
from patch_manager import PatchManager

# After connected_clients and other globals:
patch_manager = PatchManager()

# Inside async def relay(websocket), in the repair handling block (around line 40-116):
# Replace the entire "if data.get("type") == "repair":" block with:

if data.get("type") == "repair":
    target_file = data.get("file", "")
    patch_type = data.get("patch_type", "single_line")
    print(f"🛠️  [Relay] REPAIR ({patch_type}): {target_file}")
    
    success, error_msg, file_path, transaction_id = patch_manager.apply_repair(data, last_command)
    
    if not success:
        await broadcast({
            "type": "log_stream",
            "log_line": f"❌ [Relay] PATCH FAILED: {error_msg}"
        })
        continue
    
    await broadcast({
        "type": "log_stream",
        "log_line": "✅ [Relay] PATCH APPLIED AND VERIFIED."
    })
    continue
```

- [ ] **Step 6: Test relay_server.py starts without errors**

Run: `python relay_server.py`  
Expected: Server starts on ws://0.0.0.0:8765

Press Ctrl+C to stop.

- [ ] **Step 7: Commit**

```bash
git add patch_manager.py test_patch_manager.py relay_server.py
git commit -m "feat: add PatchManager with diff support and integrate into relay

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 3: Android DiagnosticResult Data Model Update

**Files:**
- Modify: `app/src/main/java/com/devdeck/app/model/DiagnosticResult.kt`

**Interfaces:**
- Produces: `PatchType` enum, `diffText: String?`, `patchType: PatchType`

- [ ] **Step 1: Read current DiagnosticResult.kt**

Already read in previous context.

- [ ] **Step 2: Add PatchType enum and new fields**

Edit `app/src/main/java/com/devdeck/app/model/DiagnosticResult.kt`:

```kotlin
package com.devdeck.app.model

enum class PatchType {
    SINGLE_LINE,
    DIFF
}

data class DiagnosticResult(
    val rootCause: String,
    val location: String,
    val fix: String,
    val isParsed: Boolean = true,
    val rawOutput: String? = null,
    val tokensPerSecond: Float = 0f,
    val memoryUsageMB: Int = 0,
    val repairFile: String? = null,
    val repairLine: Int? = null,
    val repairCode: String? = null,
    val originalLine: String? = null,
    val diffText: String? = null,
    val patchType: PatchType = PatchType.SINGLE_LINE
)
```

- [ ] **Step 3: Run Gradle sync**

Run: `./gradlew build` (or sync in IDE)  
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/devdeck/app/model/DiagnosticResult.kt
git commit -m "feat: add PatchType enum and diffText field to DiagnosticResult

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 4: Update DiagnosticAgent for Diff Generation

**Files:**
- Modify: `app/src/main/java/com/devdeck/app/ai/DiagnosticAgent.kt`

**Interfaces:**
- Consumes: `DiagnosticResult` with new fields
- Produces: `DiagnosticResult` with `patchType = DIFF` and populated `diffText` when multi-line fix is needed

- [ ] **Step 1: Update prompt in analyzeError() to request unified diff format**

Modify the prompt section in `DiagnosticAgent.kt` around line 79-118. Replace the existing prompt with:

```kotlin
val prompt = """
<start_of_turn>user
You are an autonomous code repair engine. Fix the error by generating a unified diff patch.

$ruleContext

STRICT RULES:
1. Output a unified diff between <<<DIFF>>> and <<<END>>> markers
2. Use standard unified diff format: @@ -start,count +start,count @@
3. Include 1-2 context lines around changes
4. Only use existing identifiers: [$originalIds]
5. Maximum 20 lines changed

EXAMPLES:

Example 1:
Error: AttributeError: 'NoneType' object has no attribute 'is_authenticated'
Target: if user.is_authenticated():

<<<DIFF>>>
@@ -1,2 +1,2 @@
 user = db.find_user(user_id)
-if user.is_authenticated():
+if user and user.is_authenticated():
     return user.token
<<<END>>>

Example 2:
Error: ZeroDivisionError: division by zero
Target: avg = total / count

<<<DIFF>>>
@@ -1,1 +1,1 @@
-avg = total / count
+avg = total / count if count != 0 else 0
<<<END>>>

NOW FIX THIS:
Error:
${extractCleanError(errorText)}

$contextSection

Target Line:
$originalLine

<end_of_turn>
<start_of_turn>model
<<<DIFF>>>""".trimIndent()
```

- [ ] **Step 2: Add diff parsing logic in parseResponse()**

After the AI generates the response, parse it for diff format. Modify `parseResponse()` function (around line 182-256):

```kotlin
private fun parseResponse(
    raw: String, 
    tps: Float, 
    mem: Int,
    filePath: String?,
    lineNum: Int?,
    originalLine: String?,
    errorText: String,
    sourceContext: String?
): DiagnosticResult {
    return try {
        // Check if response contains diff markers
        val diffRegex = "<<<DIFF>>>([\\s\\S]*?)(?:<<<END>>>|<end_of_turn>|$)".toRegex()
        val diffMatch = diffRegex.find(raw)
        
        if (diffMatch != null) {
            // Diff mode
            var diffText = diffMatch.groupValues[1].trim()
            
            // Validate diff starts with @@
            if (!diffText.startsWith("@@")) {
                Log.w("DevDeck", "Diff doesn't start with @@ marker, falling back")
                return fallbackHeuristic(errorText, sourceContext, filePath, lineNum, originalLine, tps, mem)
            }
            
            // Extract added lines for grounding check
            val addedLines = diffText.lines()
                .filter { it.startsWith("+") && !it.startsWith("+++") }
                .map { it.substring(1).trim() }
            
            val originalIds = originalLine?.let { extractIdentifiers(it) } ?: emptySet()
            val addedIds = addedLines.flatMap { extractIdentifiers(it) }.toSet()
            val hallucinated = addedIds - originalIds
            
            if (hallucinated.isNotEmpty()) {
                Log.w("DevDeck", "Diff has hallucinated IDs: $hallucinated, falling back")
                return fallbackHeuristic(errorText, sourceContext, filePath, lineNum, originalLine, tps, mem)
            }
            
            // Count changed lines
            val changedCount = diffText.lines().count { it.startsWith("+") || it.startsWith("-") }
            if (changedCount > 20) {
                Log.w("DevDeck", "Diff too large: $changedCount lines")
                return fallbackHeuristic(errorText, sourceContext, filePath, lineNum, originalLine, tps, mem)
            }
            
            // Valid diff
            return DiagnosticResult(
                rootCause = "Multi-line fix generated by on-device AI.",
                location = filePath ?: "Unknown",
                fix = "See diff",
                tokensPerSecond = tps,
                memoryUsageMB = mem,
                repairFile = filePath,
                repairLine = lineNum,
                diffText = diffText,
                patchType = PatchType.DIFF,
                originalLine = originalLine,
                rawOutput = raw
            )
        } else {
            // Try single-line mode (existing logic)
            val fixRegex = "<<<FIX>>>([\\s\\S]*?)(?:<<<END>>>|<end_of_turn>|$)".toRegex()
            val match = fixRegex.find(raw)
            var extractedFix = match?.groupValues?.get(1)?.trim()
            
            extractedFix = extractedFix
                ?.replace(Regex("^```[a-zA-Z]*\\n?"), "")
                ?.replace(Regex("```$"), "")
                ?.trim()
                ?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
            
            val originalIds = originalLine?.let { extractIdentifiers(it) } ?: emptySet()
            val fixIds = extractedFix?.let { extractIdentifiers(it) } ?: emptySet()
            val hallucinated = fixIds - originalIds
            
            val isSingleLine = extractedFix != null && !extractedFix.contains("\n")
            val isValid = extractedFix != null && extractedFix.uppercase() != "UNKNOWN"
            val isGrounded = hallucinated.isEmpty()
            
            if (isSingleLine && isValid && isGrounded) {
                return DiagnosticResult(
                    rootCause = "Single-line fix from AI.",
                    location = filePath ?: "Unknown",
                    fix = extractedFix!!,
                    tokensPerSecond = tps,
                    memoryUsageMB = mem,
                    repairFile = filePath,
                    repairLine = lineNum,
                    repairCode = extractedFix,
                    patchType = PatchType.SINGLE_LINE,
                    originalLine = originalLine,
                    rawOutput = raw
                )
            } else {
                Log.w("DevDeck", "Single-line parse failed, using heuristic")
                return fallbackHeuristic(errorText, sourceContext, filePath, lineNum, originalLine, tps, mem)
            }
        }
    } catch (e: Exception) {
        Log.e("DevDeck", "parseResponse error: ${e.message}")
        return fallbackHeuristic(errorText, sourceContext, filePath, lineNum, originalLine, tps, mem)
    }
}

private fun fallbackHeuristic(
    errorText: String,
    sourceContext: String?,
    filePath: String?,
    lineNum: Int?,
    originalLine: String?,
    tps: Float,
    mem: Int
): DiagnosticResult {
    val heuristic = HeuristicDiagnosticEngine.diagnose(errorText, sourceContext, filePath, lineNum, originalLine)
    return heuristic.copy(tokensPerSecond = tps, memoryUsageMB = mem)
}
```

- [ ] **Step 3: Update imports**

Add at top of `DiagnosticAgent.kt`:
```kotlin
import com.devdeck.app.model.PatchType
```

- [ ] **Step 4: Run Gradle build**

Run: `./gradlew assembleDebug`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/devdeck/app/ai/DiagnosticAgent.kt
git commit -m "feat: add unified diff generation and parsing to DiagnosticAgent

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 5: Update MainActivity to Send Diff Repairs

**Files:**
- Modify: `app/src/main/java/com/devdeck/app/ui/MainActivity.kt`

**Interfaces:**
- Consumes: `DiagnosticResult` with `patchType` and `diffText`
- Produces: JSON payload with `patch_type` = "diff" and `diff_text` field

- [ ] **Step 1: Update sendRepair() function**

Find `sendRepair()` in `MainActivity.kt` (around line 456-475) and replace:

```kotlin
private fun sendRepair(result: com.devdeck.app.model.DiagnosticResult) {
    val json = when (result.patchType) {
        com.devdeck.app.model.PatchType.SINGLE_LINE -> JSONObject().apply {
            put("type", "repair")
            put("patch_type", "single_line")
            put("file", result.repairFile)
            put("line", result.repairLine)
            put("code", result.repairCode)
        }
        com.devdeck.app.model.PatchType.DIFF -> JSONObject().apply {
            put("type", "repair")
            put("patch_type", "diff")
            put("file", result.repairFile)
            put("diff_text", result.diffText)
        }
    }
    
    appendToTerminal("Sending ${result.patchType} repair to laptop...", "sys")
    val sent = webSocket?.send(json.toString()) ?: false
    if (sent) {
        appendToTerminal("Repair payload SENT.", "ok")
        binding.repairButton.text = "Repair sent to laptop"
        binding.repairButton.isEnabled = false
        binding.repairButton.icon = ContextCompat.getDrawable(this, android.R.drawable.checkbox_on_background)
        binding.repairButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E6F5F2"))
        binding.repairButton.setTextColor(Color.parseColor("#0B8A78"))
    } else {
        appendToTerminal("ERROR: Failed to send repair.", "fail")
    }
}
```

- [ ] **Step 2: Run Gradle build**

Run: `./gradlew assembleDebug`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/devdeck/app/ui/MainActivity.kt
git commit -m "feat: update sendRepair to handle diff patch type

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 6: Model Manager Data Classes

**Files:**
- Create: `app/src/main/java/com/devdeck/app/model/ModelConfig.kt`
- Create: `app/src/main/java/com/devdeck/app/model/ModelManager.kt`

**Interfaces:**
- Produces:
  - `ModelConfig` data class
  - `ModelTier` enum
  - `ModelManager.getPredefinedModels() -> List<ModelConfig>`
  - `ModelManager.getCurrentModelPath() -> String`
  - `ModelManager.setModelPath(path: String)`
  - `ModelManager.verifyModel(path: String) -> Triple<Boolean, Float, String?>`

- [ ] **Step 1: Create ModelConfig.kt**

Create `app/src/main/java/com/devdeck/app/model/ModelConfig.kt`:

```kotlin
package com.devdeck.app.model

enum class ModelTier {
    FAST,
    ADVANCED
}

data class ModelConfig(
    val id: String,
    val displayName: String,
    val description: String,
    val filePath: String,
    val sizeGB: Float,
    val estimatedTPS: Int,
    val specialty: String,
    val tier: ModelTier,
    val isActive: Boolean = false
)
```

- [ ] **Step 2: Create ModelManager.kt**

Create `app/src/main/java/com/devdeck/app/model/ModelManager.kt`:

```kotlin
package com.devdeck.app.model

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class ModelManager(private val context: Context) {
    private val prefs: SharedPreferences = 
        context.getSharedPreferences("devdeck", Context.MODE_PRIVATE)
    
    companion object {
        const val PREF_MODEL_PATH = "model_path"
        const val DEFAULT_MODEL_PATH = "/data/local/tmp/gemma-2b-it-gpu.bin"
    }
    
    fun getPredefinedModels(): List<ModelConfig> {
        val currentPath = getCurrentModelPath()
        return listOf(
            ModelConfig(
                id = "gemma-2b-it",
                displayName = "Gemma-2B-IT (Default)",
                description = "Official Google Gemma 2B. Native MediaPipe support.",
                filePath = "/data/local/tmp/gemma-2b-it-gpu.bin",
                sizeGB = 1.3f,
                estimatedTPS = 18,
                specialty = "General instruction-following",
                tier = ModelTier.FAST,
                isActive = currentPath == "/data/local/tmp/gemma-2b-it-gpu.bin"
            ),
            ModelConfig(
                id = "qwen25-coder-15b",
                displayName = "Qwen2.5-Coder-1.5B",
                description = "⚡ Code specialist. Extremely fast on mobile.",
                filePath = "/data/local/tmp/qwen25-coder-15b-gpu.bin",
                sizeGB = 0.9f,
                estimatedTPS = 24,
                specialty = "Code debugging",
                tier = ModelTier.FAST,
                isActive = currentPath == "/data/local/tmp/qwen25-coder-15b-gpu.bin"
            ),
            ModelConfig(
                id = "phi35-mini",
                displayName = "Phi-3.5-mini",
                description = "Microsoft's best small model. Strong reasoning.",
                filePath = "/data/local/tmp/phi35-mini-gpu.bin",
                sizeGB = 2.4f,
                estimatedTPS = 11,
                specialty = "Advanced reasoning",
                tier = ModelTier.ADVANCED,
                isActive = currentPath == "/data/local/tmp/phi35-mini-gpu.bin"
            ),
            ModelConfig(
                id = "custom",
                displayName = "Custom Model",
                description = "Use any MediaPipe-compatible model",
                filePath = currentPath,
                sizeGB = 0f,
                estimatedTPS = 0,
                specialty = "User-provided",
                tier = ModelTier.FAST,
                isActive = !listOf(
                    "/data/local/tmp/gemma-2b-it-gpu.bin",
                    "/data/local/tmp/qwen25-coder-15b-gpu.bin",
                    "/data/local/tmp/phi35-mini-gpu.bin"
                ).contains(currentPath)
            )
        )
    }
    
    fun getCurrentModelPath(): String {
        return prefs.getString(PREF_MODEL_PATH, DEFAULT_MODEL_PATH) ?: DEFAULT_MODEL_PATH
    }
    
    fun setModelPath(path: String) {
        prefs.edit().putString(PREF_MODEL_PATH, path).apply()
    }
    
    fun isModelAvailable(path: String): Boolean {
        return File(path).exists()
    }
    
    suspend fun verifyModel(path: String): Triple<Boolean, Float, String?> {
        if (!isModelAvailable(path)) {
            return Triple(false, 0f, "File not found: $path")
        }
        
        val originalPath = getCurrentModelPath()
        setModelPath(path)
        
        try {
            val agent = com.devdeck.app.ai.DiagnosticAgent(context)
            agent.initModel()
            
            if (!agent.isEngineReady()) {
                setModelPath(originalPath)
                return Triple(false, 0f, "Model failed to load")
            }
            
            val (result, _) = agent.analyzeError(
                "Warmup test",
                "test",
                "test.py",
                1,
                "test"
            )
            
            setModelPath(originalPath)
            return Triple(true, result.tokensPerSecond, null)
        } catch (e: Exception) {
            setModelPath(originalPath)
            return Triple(false, 0f, e.message)
        }
    }
}
```

- [ ] **Step 3: Run Gradle build**

Run: `./gradlew build`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/devdeck/app/model/ModelConfig.kt app/src/main/java/com/devdeck/app/model/ModelManager.kt
git commit -m "feat: add ModelConfig and ModelManager for dynamic model switching

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 7: Model Settings UI (Layouts, Activity, Adapter)

**Files:**
- Create: `app/src/main/res/layout/activity_model_settings.xml`
- Create: `app/src/main/res/layout/item_model_card.xml`
- Create: `app/src/main/java/com/devdeck/app/ui/ModelSettingsActivity.kt`
- Create: `app/src/main/java/com/devdeck/app/ui/ModelListAdapter.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `ModelManager`, `ModelConfig`
- Produces: UI screen for model selection and verification

- [ ] **Step 1: Create activity_model_settings.xml layout**

Create `app/src/main/res/layout/activity_model_settings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/color_bg_light"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:padding="20dp"
        android:gravity="center_vertical"
        android:orientation="horizontal">
        
        <Button
            android:id="@+id/btnBack"
            style="@style/Widget.MaterialComponents.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="‹ Back"
            android:textAllCaps="false" />
        
        <TextView
            style="@style/TextAppearance.DevDeck.Heading"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="Model Manager"
            android:textSize="18sp" />
    </LinearLayout>

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/color_border" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/modelRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:padding="16dp" />

    <ProgressBar
        android:id="@+id/progressBar"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:visibility="gone" />

    <com.google.android.material.button.MaterialButton
        android:id="@+id/btnCustomPath"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:text="Set Custom Model Path"
        android:textAllCaps="false"
        app:backgroundTint="@color/color_surface"
        app:strokeColor="@color/color_border"
        app:strokeWidth="1dp"
        app:cornerRadius="12dp" />
</LinearLayout>
```

- [ ] **Step 2: Create item_model_card.xml layout**

Create `app/src/main/res/layout/item_model_card.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView 
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginBottom="12dp"
    app:cardCornerRadius="14dp"
    app:strokeColor="@color/color_border"
    app:strokeWidth="1dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/modelName"
                style="@style/TextAppearance.DevDeck.Heading"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="Gemma-2B-IT"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/activeBadge"
                style="@style/TextAppearance.DevDeck.Mono"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:background="@drawable/bg_pill_teal"
                android:paddingHorizontal="8dp"
                android:paddingVertical="4dp"
                android:text="ACTIVE"
                android:textColor="@color/color_teal"
                android:textSize="9sp"
                android:visibility="gone" />
        </LinearLayout>

        <TextView
            android:id="@+id/modelDescription"
            style="@style/TextAppearance.DevDeck.Body"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="6dp"
            android:text="Model description"
            android:textSize="13sp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <TextView
                android:id="@+id/tierBadge"
                style="@style/TextAppearance.DevDeck.Mono"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="FAST"
                android:textSize="9sp"
                android:textColor="@color/color_teal" />

            <TextView
                android:id="@+id/modelMeta"
                style="@style/TextAppearance.DevDeck.Mono"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_marginStart="12dp"
                android:layout_weight="1"
                android:text="1.3GB • 18 tok/s"
                android:textSize="11sp" />
        </LinearLayout>

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="12dp"
            android:orientation="horizontal">

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnSelect"
                android:layout_width="0dp"
                android:layout_height="44dp"
                android:layout_marginEnd="6dp"
                android:layout_weight="1"
                android:text="Use This"
                android:textAllCaps="false"
                android:textSize="12sp"
                app:backgroundTint="@color/color_teal_tint"
                app:cornerRadius="10dp"
                app:strokeColor="@color/color_teal_border"
                app:strokeWidth="1dp" />

            <com.google.android.material.button.MaterialButton
                android:id="@+id/btnVerify"
                android:layout_width="0dp"
                android:layout_height="44dp"
                android:layout_marginStart="6dp"
                android:layout_weight="1"
                android:text="Verify"
                android:textAllCaps="false"
                android:textSize="12sp"
                app:backgroundTint="@color/color_surface"
                app:cornerRadius="10dp"
                app:strokeColor="@color/color_border"
                app:strokeWidth="1dp" />
        </LinearLayout>
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

- [ ] **Step 3: Create ModelListAdapter.kt**

Create `app/src/main/java/com/devdeck/app/ui/ModelListAdapter.kt`:

```kotlin
package com.devdeck.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devdeck.app.databinding.ItemModelCardBinding
import com.devdeck.app.model.ModelConfig
import com.devdeck.app.model.ModelTier

class ModelListAdapter(
    private var models: List<ModelConfig>,
    private val onModelSelected: (ModelConfig) -> Unit,
    private val onVerifyClicked: (ModelConfig) -> Unit
) : RecyclerView.Adapter<ModelListAdapter.ModelViewHolder>() {
    
    inner class ModelViewHolder(private val binding: ItemModelCardBinding) :
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(model: ModelConfig) {
            binding.modelName.text = model.displayName
            binding.modelDescription.text = model.description
            binding.modelMeta.text = "${model.sizeGB}GB • ~${model.estimatedTPS} tok/s • ${model.specialty}"
            
            binding.activeBadge.visibility = if (model.isActive) View.VISIBLE else View.GONE
            
            val tierColor = when (model.tier) {
                ModelTier.FAST -> "#0B8A78"
                ModelTier.ADVANCED -> "#3B6FD1"
            }
            binding.tierBadge.text = model.tier.name
            binding.tierBadge.setTextColor(Color.parseColor(tierColor))
            
            binding.btnSelect.setOnClickListener { onModelSelected(model) }
            binding.btnVerify.setOnClickListener { onVerifyClicked(model) }
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val binding = ItemModelCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ModelViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(models[position])
    }
    
    override fun getItemCount() = models.size
    
    fun updateModels(newModels: List<ModelConfig>) {
        models = newModels
        notifyDataSetChanged()
    }
}
```

- [ ] **Step 4: Create ModelSettingsActivity.kt**

Create `app/src/main/java/com/devdeck/app/ui/ModelSettingsActivity.kt`:

```kotlin
package com.devdeck.app.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.devdeck.app.databinding.ActivityModelSettingsBinding
import com.devdeck.app.model.ModelConfig
import com.devdeck.app.model.ModelManager
import kotlinx.coroutines.launch

class ModelSettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityModelSettingsBinding
    private lateinit var modelManager: ModelManager
    private lateinit var adapter: ModelListAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        modelManager = ModelManager(this)
        
        setupRecyclerView()
        setupCustomPathButton()
        
        binding.btnBack.setOnClickListener { finish() }
    }
    
    private fun setupRecyclerView() {
        val models = modelManager.getPredefinedModels()
        
        adapter = ModelListAdapter(
            models = models,
            onModelSelected = { model -> selectModel(model) },
            onVerifyClicked = { model -> verifyModel(model) }
        )
        
        binding.modelRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.modelRecyclerView.adapter = adapter
    }
    
    private fun selectModel(model: ModelConfig) {
        if (!modelManager.isModelAvailable(model.filePath)) {
            Toast.makeText(this, "Model not found. Use ADB to push it.", Toast.LENGTH_LONG).show()
            return
        }
        
        modelManager.setModelPath(model.filePath)
        Toast.makeText(this, "Switched to ${model.displayName}", Toast.LENGTH_SHORT).show()
        adapter.updateModels(modelManager.getPredefinedModels())
    }
    
    private fun verifyModel(model: ModelConfig) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val (success, tps, error) = modelManager.verifyModel(model.filePath)
            
            binding.progressBar.visibility = View.GONE
            
            if (success) {
                AlertDialog.Builder(this@ModelSettingsActivity)
                    .setTitle("✅ Verified")
                    .setMessage("${model.displayName}\n\n${tps.toInt()} tokens/sec")
                    .setPositiveButton("Use This") { _, _ -> selectModel(model) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                AlertDialog.Builder(this@ModelSettingsActivity)
                    .setTitle("❌ Failed")
                    .setMessage(error ?: "Unknown error")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
    
    private fun setupCustomPathButton() {
        binding.btnCustomPath.setOnClickListener {
            val input = EditText(this).apply {
                hint = "/data/local/tmp/my-model.bin"
                setText(modelManager.getCurrentModelPath())
            }
            
            AlertDialog.Builder(this)
                .setTitle("Custom Model Path")
                .setMessage("Enter MediaPipe-compatible model path:")
                .setView(input)
                .setPositiveButton("Set") { _, _ ->
                    val path = input.text.toString().trim()
                    if (path.isNotEmpty()) {
                        modelManager.setModelPath(path)
                        Toast.makeText(this, "Custom path set", Toast.LENGTH_SHORT).show()
                        adapter.updateModels(modelManager.getPredefinedModels())
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
```

- [ ] **Step 5: Add activity to AndroidManifest.xml**

Edit `app/src/main/AndroidManifest.xml` and add inside `<application>`:

```xml
<activity
    android:name=".ui.ModelSettingsActivity"
    android:label="Model Manager"
    android:parentActivityName=".ui.MainActivity" />
```

- [ ] **Step 6: Run Gradle build**

Run: `./gradlew assembleDebug`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/layout/activity_model_settings.xml app/src/main/res/layout/item_model_card.xml app/src/main/java/com/devdeck/app/ui/ModelSettingsActivity.kt app/src/main/java/com/devdeck/app/ui/ModelListAdapter.kt app/src/main/AndroidManifest.xml
git commit -m "feat: add Model Settings UI with RecyclerView and verification

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

### Task 8: Wire Model Settings Button in MainActivity

**Files:**
- Modify: `app/src/main/java/com/devdeck/app/ui/MainActivity.kt`

**Interfaces:**
- Produces: Click listener to launch `ModelSettingsActivity`

- [ ] **Step 1: Add Intent to launch ModelSettingsActivity**

In `MainActivity.kt`, find the `setupActionButtons()` or `onCreate()` section, and add:

```kotlin
// Inside MainActivity, after other button setups:
binding.modelStatusContainer.setOnClickListener {
    vibrate()
    startActivity(Intent(this, ModelSettingsActivity::class.java))
}
```

- [ ] **Step 2: Run Gradle build**

Run: `./gradlew assembleDebug`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/devdeck/app/ui/MainActivity.kt
git commit -m "feat: wire model settings button in MainActivity

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] **Test Python relay server starts**

Run: `python relay_server.py`  
Expected: Server running on ws://0.0.0.0:8765

- [ ] **Test Android APK builds**

Run: `./gradlew assembleDebug`  
Expected: BUILD SUCCESSFUL

- [ ] **Install APK on device and verify Model Manager opens**

Run: `./gradlew installDebug` (if device connected)  
Expected: App installs, Model Manager screen opens from home

- [ ] **End-to-end test: trigger error, verify diff repair**

1. Run `python relay_server.py` on host
2. Launch DevDeck app on Android, ensure "RELAY: CONNECTED"
3. Run `python devdeck.py run "python test_errors.py typeerror"`
4. Verify Android shows diagnosis with diff preview
5. Tap "Apply" or enable Agent mode
6. Verify host terminal shows "✅ SUCCESS" or rollback on failure

---

**Plan complete!** All tasks defined with exact code, test steps, and commit messages.
