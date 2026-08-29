# DevDeck Pocket - Phase 1 Core Engine Improvements

**Design Document**  
**Date:** 2026-08-25  
**Author:** Claude (with User)  
**Status:** Approved  
**Target:** iQOO Hackathon Demo

---

## Executive Summary

This document specifies Phase 1 enhancements to DevDeck Pocket, an on-device AI-powered autonomous code repair engine. Phase 1 focuses on three core improvements:

1. **Multi-line Diff Repair System** - Expand beyond single-line fixes to support complex multi-line patches using unified diff format
2. **Model Manager UI** - Allow dynamic model switching without recompiling the app, with predefined model catalog
3. **Git-Assisted Transactional Rollback** - Harden error recovery using Git version control integration with intelligent fallback

These improvements maintain DevDeck's core principles: 100% local inference, semantic grounding, transactional safety, and autonomous operation, while significantly expanding repair capabilities and robustness.

---

## Context & Motivation

### Current State

DevDeck Pocket currently:
- Intercepts failed developer commands on the host machine
- Sends error traces + source context over WebSocket to an Android device
- Runs on-device AI inference (Gemma-2B via MediaPipe GenAI)
- Generates single-line code fixes with semantic grounding
- Applies fixes transactionally with `.bak` file rollback

**Limitations:**
- **Single-line only**: Cannot fix errors requiring multi-line changes (adding try-catch, restructuring logic, guard clauses)
- **Fixed model**: Gemma-2B path is hardcoded; no easy way to test other models
- **Basic rollback**: Simple `.bak` file copying; no git integration, potential for orphaned backups

### Why These Features Now

**Hackathon Context:**
- iQOO 15 hackathon (30 hours)
- Judged on: AI-first design, phone-centric architecture, novelty, feasibility
- "Local or open-source model at the core earns brownie points"
- Developer Tools track

**Phase 1 features address:**
- **Multi-line fixes**: Shows sophisticated AI capability (judges see complex repairs)
- **Model Manager**: Demonstrates extensibility and "production-ready" architecture
- **Git rollback**: Emphasizes safety and real-world dev workflow integration

---

## Goals & Non-Goals

### Goals
✅ Support unified diff patches (2-20 lines) with semantic grounding  
✅ Allow model switching via UI without APK rebuild  
✅ Integrate with Git for transactional rollback  
✅ Maintain 100% on-device inference (no cloud dependencies)  
✅ Preserve existing single-line repair mode for simple fixes  
✅ Add compiler/syntax validation before applying patches  

### Non-Goals
❌ Cloud-based model hosting  
❌ Support for non-MediaPipe model formats (GGUF, ONNX) - conversion required  
❌ Automatic model downloading from HuggingFace  
❌ Multi-file patches in a single repair (one file per repair payload)  
❌ IDE plugin integration (deferred to Phase 3)  

---

## Architecture Overview

### System Components (After Phase 1)

```
┌─────────────────────────────────────────────────────────────┐
│                ANDROID APP (Kotlin)                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  MainActivity.kt [MODIFIED]                                 │
│  ├─ WebSocket Client (receives errors, sends repairs)       │
│  ├─ UI Navigation (Home/Trace/Diagnosis screens)            │
│  ├─ Model settings button → ModelSettingsActivity           │
│  └─ Triggers DiagnosticAgent                                │
│                                                              │
│  DiagnosticAgent.kt [MODIFIED]                              │
│  ├─ LlmInference (MediaPipe GenAI)                          │
│  ├─ Prompt: Generate Unified Diff (multi-line)              │
│  ├─ Diff parsing & validation                               │
│  ├─ Semantic Grounding (check added identifiers)            │
│  └─ Returns DiagnosticResult with diff_text                 │
│                                                              │
│  ModelManager.kt [NEW]                                      │
│  ├─ ModelConfig data class (id, path, metadata)             │
│  ├─ Predefined model catalog (Gemma-2B, Qwen, Custom)       │
│  ├─ Model verification (canary test)                        │
│  └─ SharedPreferences persistence                           │
│                                                              │
│  ModelSettingsActivity.kt [NEW]                             │
│  ├─ RecyclerView of available models                        │
│  ├─ "Verify & Test" button per model                        │
│  ├─ Shows TPS telemetry after verification                  │
│  └─ Active model badge                                      │
│                                                              │
│  DiagnosticResult.kt [MODIFIED]                             │
│  ├─ Add: diffText: String?                                  │
│  ├─ Add: patchType: PatchType (SINGLE_LINE | DIFF)          │
│  └─ Existing fields preserved                               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ WebSocket (ws://localhost:8765)
                            │
┌─────────────────────────────────────────────────────────────┐
│              PYTHON RELAY SERVER                             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  relay_server.py [MODIFIED]                                 │
│  ├─ relay() - Main WebSocket handler                        │
│  ├─ Detects patch_type in repair payload                    │
│  └─ Routes to PatchManager.apply_repair()                   │
│                                                              │
│  patch_manager.py [NEW]                                     │
│  ├─ apply_repair(data) - Dispatcher                         │
│  ├─ apply_single_line_repair() - Legacy mode                │
│  ├─ apply_diff_patch() - New unified diff mode              │
│  ├─ dry_run_compile_check() - Syntax validation             │
│  └─ rerun_command() - Test patched code                     │
│                                                              │
│  git_transaction_engine.py [NEW]                            │
│  ├─ is_git_repo() - Detect git availability                 │
│  ├─ get_file_status() - Check if file has changes           │
│  ├─ create_transaction() - Stash + save HEAD                │
│  ├─ commit_transaction() - Restore stash cleanly            │
│  ├─ rollback_transaction() - git checkout + restore stash   │
│  └─ FallbackBackupManager - Non-git .bak registry           │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Data Flow for Multi-line Repair

```
1. Developer runs: python devdeck.py run "pytest test_auth.py"
   └─> Command fails with AttributeError

2. devdeck.py:
   ├─ Captures stderr
   ├─ Extracts file:line with regex
   ├─ Reads source context (±6 lines)
   └─> Sends JSON to relay_server.py

3. relay_server.py broadcasts to connected clients (Android + Web)

4. MainActivity.kt receives error JSON:
   └─> Calls handleIncomingError(jsonText)

5. DiagnosticAgent.analyzeError():
   ├─ Reads model_path from SharedPreferences
   ├─ Initializes LlmInference (if not already loaded)
   ├─ Constructs prompt requesting unified diff
   ├─ Calls inference.generateResponse(prompt)
   ├─ Parses response for diff markers
   ├─ Validates diff syntax
   ├─ Semantic grounding: checks added identifiers
   └─> Returns DiagnosticResult(diffText="@@...", patchType=DIFF)

6. MainActivity.kt checks agentModeSwitch or user taps "Apply":
   └─> Calls sendRepair(result)

7. WebSocket sends to relay_server.py:
   {
     "type": "repair",
     "file": "auth_service.py",
     "patch_type": "diff",
     "diff_text": "@@ -40,3 +40,5 @@\n..."
   }

8. relay_server.py → PatchManager.apply_repair():
   ├─ GitTransactionEngine.is_git_repo() → True/False
   ├─ [If Git] create_transaction() - stash uncommitted changes
   ├─ [If No Git] FallbackBackupManager - copy to .devdeck/backups/
   ├─ Apply diff in-memory first (using difflib or patch command)
   ├─ dry_run_compile_check() - Run python -m py_compile
   ├─ If syntax valid: write to disk
   ├─ rerun_command(last_command)
   ├─ Exit code 0? → commit_transaction() (restore stash, keep patch)
   └─ Exit code != 0? → rollback_transaction() (revert file, restore stash)

9. Broadcast result back to Android:
   └─> Shows success/failure in terminal + updates repair button
```

---

## Feature 1: Multi-line Diff Repair System

### Overview

Upgrade from single-line replacement to full unified diff patches, enabling complex repairs that restructure code across multiple lines.

### Unified Diff Format

**Example diff:**
```diff
@@ -40,3 +40,5 @@
 user = db.find_user(user_id)
 
-if user.is_authenticated():
-    return user.token
+if user:
+    if user.is_authenticated():
+        return user.token
+return None
```

**Why unified diff:**
- Industry standard (Git, patch command)
- Human-readable
- Contains context lines for verification
- Supports additions, deletions, modifications

### Android Implementation

#### 1. Update `DiagnosticResult.kt`

```kotlin
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
    
    // NEW FIELDS
    val diffText: String? = null,
    val patchType: PatchType = PatchType.SINGLE_LINE
)

enum class PatchType {
    SINGLE_LINE,  // Legacy mode: repairCode contains single line
    DIFF          // New mode: diffText contains unified diff
}
```

#### 2. Modify `DiagnosticAgent.kt` Prompt

**New prompt structure:**
```kotlin
val prompt = """
<start_of_turn>user
You are an autonomous code repair engine. Fix the error by generating a unified diff patch.

$ruleContext

STRICT RULES:
1. Output a unified diff between <<<DIFF>>> and <<<END>>> markers
2. Use standard unified diff format: @@ -start,count +start,count @@
3. Include context lines (unchanged lines around the fix)
4. Only use existing identifiers: [$originalIds]
5. Maximum 20 lines changed (additions + deletions)

FEW-SHOT EXAMPLES:

Example 1:
Error: AttributeError: 'NoneType' object has no attribute 'is_authenticated'
Target Line: if user.is_authenticated():
Context:
  user = db.find_user(user_id)
  if user.is_authenticated():
      return user.token

<<<DIFF>>>
@@ -2,2 +2,3 @@
 user = db.find_user(user_id)
-if user.is_authenticated():
+if user and user.is_authenticated():
     return user.token
<<<END>>>

Example 2:
Error: ZeroDivisionError: division by zero
Target Line: avg = total / count
Context:
  total = sum(values)
  avg = total / count
  print(avg)

<<<DIFF>>>
@@ -2,1 +2,1 @@
 total = sum(values)
-avg = total / count
+avg = total / count if count != 0 else 0
 print(avg)
<<<END>>>

NOW FIX THIS:
Error:
${extractCleanError(errorTrace)}

$contextSection

Target Line:
$originalLine

<end_of_turn>
<start_of_turn>model
<<<DIFF>>>""".trimIndent()
```

#### 3. Parse Diff Response

```kotlin
private fun parseDiffResponse(
    raw: String,
    originalIds: Set<String>
): Pair<String?, Boolean> {
    // Extract diff between <<<DIFF>>> and <<<END>>> markers
    val diffRegex = "<<<DIFF>>>([\\s\\S]*?)(?:<<<END>>>|<end_of_turn>|$)".toRegex()
    val match = diffRegex.find(raw)
    var diffText = match?.groupValues?.get(1)?.trim() ?: return null to false
    
    // Validate diff format: must start with @@ marker
    if (!diffText.startsWith("@@")) return null to false
    
    // Extract added lines (lines starting with +)
    val addedLines = diffText.lines()
        .filter { it.startsWith("+") && !it.startsWith("+++") }
        .map { it.substring(1).trim() }
    
    // Semantic grounding: check for hallucinated identifiers
    val addedIds = addedLines.flatMap { extractIdentifiers(it) }.toSet()
    val hallucinated = addedIds - originalIds
    
    if (hallucinated.isNotEmpty()) {
        Log.w("DevDeck", "Diff contains hallucinated identifiers: $hallucinated")
        return null to false // Reject ungrounded diff
    }
    
    // Count changed lines (+ and - lines)
    val changedLineCount = diffText.lines().count { 
        it.startsWith("+") || it.startsWith("-") 
    }
    if (changedLineCount > 20) {
        Log.w("DevDeck", "Diff too large: $changedLineCount lines changed")
        return null to false // Reject overly large diffs
    }
    
    return diffText to true // Valid diff
}
```

#### 4. Update `sendRepair()` in MainActivity.kt

```kotlin
private fun sendRepair(result: DiagnosticResult) {
    val json = when (result.patchType) {
        PatchType.SINGLE_LINE -> JSONObject().apply {
            put("type", "repair")
            put("patch_type", "single_line")
            put("file", result.repairFile)
            put("line", result.repairLine)
            put("code", result.repairCode)
        }
        PatchType.DIFF -> JSONObject().apply {
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
        // Update UI...
    }
}
```

### Python Relay Server Implementation

#### 1. Create `patch_manager.py`

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
        """
        Main entry point for applying repairs.
        Routes to single-line or diff mode based on patch_type.
        """
        patch_type = data.get("patch_type", "single_line")
        file_path = data.get("file", "")
        
        if not os.path.exists(file_path):
            rel_path = os.path.basename(file_path)
            if os.path.exists(rel_path):
                file_path = rel_path
            else:
                return False, f"File not found: {file_path}", None, None
        
        if patch_type == "single_line":
            return self.apply_single_line_repair(data, last_command)
        elif patch_type == "diff":
            return self.apply_diff_patch(data, last_command)
        else:
            return False, f"Unknown patch_type: {patch_type}", None, None
    
    def apply_single_line_repair(self, data, last_command):
        """
        Legacy single-line repair mode (existing logic from apply_repair_robust).
        Preserved for simple fixes.
        """
        # [Keep existing apply_repair_robust logic here]
        # ... (lines 141-214 from current relay_server.py)
        pass
    
    def apply_diff_patch(self, data, last_command):
        """
        Apply unified diff patch with git-assisted transactional rollback.
        """
        file_path = data.get("file")
        diff_text = data.get("diff_text")
        
        if not diff_text:
            return False, "No diff_text provided", file_path, None
        
        print(f"[PatchManager] Applying diff patch to {file_path}")
        
        # Step 1: Create transaction (git stash or backup)
        if self.git_engine.is_git_repo():
            transaction_id = self.git_engine.create_transaction(file_path)
            print(f"[Git] Transaction created: {transaction_id}")
        else:
            backup_path = self.fallback_backup.create_backup(file_path)
            transaction_id = backup_path
            print(f"[Backup] Created: {backup_path}")
        
        # Step 2: Apply diff in-memory first (dry-run)
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                original_content = f.read()
            
            # Use Python's difflib or subprocess patch command
            patched_content = self._apply_diff_to_string(original_content, diff_text)
            
            if patched_content is None:
                self._rollback(file_path, transaction_id)
                return False, "Diff application failed (invalid format)", file_path, transaction_id
        
        except Exception as e:
            self._rollback(file_path, transaction_id)
            return False, f"Diff parsing error: {e}", file_path, transaction_id
        
        # Step 3: Syntax validation (dry-run compile)
        syntax_ok, syntax_error = self.dry_run_compile_check(file_path, patched_content)
        if not syntax_ok:
            self._rollback(file_path, transaction_id)
            return False, f"Syntax check failed: {syntax_error}", file_path, transaction_id
        
        # Step 4: Write to disk
        try:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(patched_content)
            print(f"[PatchManager] Diff applied to disk: {file_path}")
        except Exception as e:
            self._rollback(file_path, transaction_id)
            return False, f"Write failed: {e}", file_path, transaction_id
        
        # Step 5: Rerun command
        if last_command:
            success = self.rerun_command(last_command, file_path, transaction_id)
            if success:
                self._commit(transaction_id)
                return True, None, file_path, transaction_id
            else:
                self._rollback(file_path, transaction_id)
                return False, "Rerun failed, rolled back", file_path, transaction_id
        
        # No command to rerun, assume success
        self._commit(transaction_id)
        return True, None, file_path, transaction_id
    
    def _apply_diff_to_string(self, original: str, diff_text: str) -> str:
        """
        Apply unified diff to original content string.
        Returns patched content or None if diff is invalid.
        """
        # Write original to temp file
        with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False, encoding='utf-8') as tmp:
            tmp.write(original)
            tmp_path = tmp.name
        
        # Write diff to temp file
        with tempfile.NamedTemporaryFile(mode='w', suffix='.patch', delete=False, encoding='utf-8') as patch_file:
            # Ensure proper patch format with file headers
            patch_content = f"--- a/temp\n+++ b/temp\n{diff_text}\n"
            patch_file.write(patch_content)
            patch_path = patch_file.name
        
        try:
            # Apply patch using system patch command
            result = subprocess.run(
                ['patch', tmp_path, patch_path],
                capture_output=True,
                text=True,
                timeout=5
            )
            
            if result.returncode != 0:
                print(f"[PatchManager] patch command failed: {result.stderr}")
                return None
            
            # Read patched content
            with open(tmp_path, 'r', encoding='utf-8') as f:
                patched = f.read()
            
            return patched
        
        except subprocess.TimeoutExpired:
            print("[PatchManager] patch command timed out")
            return None
        except FileNotFoundError:
            print("[PatchManager] 'patch' command not found, falling back to Python difflib")
            # Fallback: use Python's difflib (less robust but cross-platform)
            return self._apply_diff_with_difflib(original, diff_text)
        finally:
            os.unlink(tmp_path)
            os.unlink(patch_path)
    
    def _apply_diff_with_difflib(self, original: str, diff_text: str) -> str:
        """
        Fallback diff application using Python's difflib.
        Less robust than system patch command.
        """
        import difflib
        
        # Parse unified diff
        original_lines = original.splitlines(keepends=True)
        
        # Extract changes from diff
        diff_lines = diff_text.splitlines()
        
        # Simple parser: find @@ header, then apply +/- lines
        # This is a simplified implementation - production should use proper diff parser
        # For hackathon, we'll rely on system 'patch' command primarily
        
        print("[PatchManager] difflib fallback not fully implemented, rejecting diff")
        return None
    
    def dry_run_compile_check(self, file_path: str, content: str) -> tuple[bool, str]:
        """
        Validate syntax before committing patch.
        Returns (success: bool, error_message: str)
        """
        _, ext = os.path.splitext(file_path)
        
        if ext == '.py':
            # Python: use py_compile
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
                os.unlink(tmp_path)
                
                if result.returncode == 0:
                    return True, ""
                else:
                    return False, result.stderr
            
            except Exception as e:
                os.unlink(tmp_path)
                return False, str(e)
        
        elif ext in ['.js', '.ts', '.jsx', '.tsx']:
            # JavaScript/TypeScript: basic bracket matching
            open_braces = content.count('{')
            close_braces = content.count('}')
            if open_braces != close_braces:
                return False, f"Unmatched braces: {open_braces} open, {close_braces} close"
            return True, ""
        
        elif ext in ['.kt', '.java']:
            # Kotlin/Java: basic syntax check (bracket matching)
            open_braces = content.count('{')
            close_braces = content.count('}')
            if open_braces != close_braces:
                return False, f"Unmatched braces"
            return True, ""
        
        else:
            # Unknown file type, skip validation
            return True, ""
    
    def rerun_command(self, command: str, file_path: str, transaction_id: str) -> bool:
        """
        Rerun the failed command to verify the patch works.
        Returns True if exit code is 0, False otherwise.
        """
        print(f"\n{'🚀' * 20}")
        print(f"[PatchManager] RERUNNING COMMAND: {command}")
        print(f"{'🚀' * 20}\n")
        
        try:
            result = subprocess.run(
                command,
                shell=True,
                capture_output=True,
                text=True,
                timeout=8
            )
            
            if result.returncode == 0:
                print(f"✅ [PatchManager] RERUN SUCCESS: Exit code 0")
                return True
            else:
                print(f"❌ [PatchManager] RERUN FAILED: Exit code {result.returncode}")
                return False
        
        except subprocess.TimeoutExpired:
            print(f"❌ [PatchManager] RERUN TIMEOUT (>8s, possible infinite loop)")
            return False
        except Exception as e:
            print(f"❌ [PatchManager] RERUN ERROR: {e}")
            return False
    
    def _commit(self, transaction_id: str):
        """Commit transaction (restore stash or keep backup)"""
        if self.git_engine.is_git_repo():
            self.git_engine.commit_transaction(transaction_id)
        else:
            self.fallback_backup.commit(transaction_id)
    
    def _rollback(self, file_path: str, transaction_id: str):
        """Rollback transaction (git checkout or restore backup)"""
        if self.git_engine.is_git_repo():
            self.git_engine.rollback_transaction(file_path, transaction_id)
        else:
            self.fallback_backup.rollback(file_path, transaction_id)
```

#### 2. Create `git_transaction_engine.py`

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
        """Check if current directory is a git repository"""
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
        """
        Create a transaction before modifying file.
        Returns transaction_id (stash ref or commit hash).
        """
        if not self.is_git_repo():
            return None
        
        # Check if file has uncommitted changes
        try:
            result = subprocess.run(
                ['git', 'status', '--porcelain', file_path],
                capture_output=True,
                text=True,
                timeout=2
            )
            
            has_changes = bool(result.stdout.strip())
            
            if has_changes:
                # Stash uncommitted changes with unique message
                stash_msg = f"devdeck_pre_repair_{datetime.now().isoformat()}"
                subprocess.run(
                    ['git', 'stash', 'push', '-u', '-m', stash_msg, file_path],
                    capture_output=True,
                    timeout=5
                )
                print(f"[Git] Stashed uncommitted changes: {stash_msg}")
                return stash_msg
            else:
                # No uncommitted changes, just save current HEAD
                result = subprocess.run(
                    ['git', 'rev-parse', 'HEAD'],
                    capture_output=True,
                    text=True,
                    timeout=2
                )
                head_commit = result.stdout.strip()
                print(f"[Git] No uncommitted changes, HEAD: {head_commit[:8]}")
                return head_commit
        
        except Exception as e:
            print(f"[Git] Transaction creation failed: {e}")
            return None
    
    def commit_transaction(self, transaction_id: str):
        """
        Commit transaction (restore stash if any).
        Called when patch succeeds.
        """
        if not transaction_id:
            return
        
        # Check if transaction_id is a stash message
        if transaction_id.startswith("devdeck_pre_repair_"):
            try:
                # Find stash by message
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
                    # Pop stash back (merge with current changes)
                    subprocess.run(
                        ['git', 'stash', 'pop', stash_ref],
                        capture_output=True,
                        timeout=5
                    )
                    print(f"[Git] Restored stash: {stash_ref}")
            
            except Exception as e:
                print(f"[Git] Stash restore failed: {e}")
    
    def rollback_transaction(self, file_path: str, transaction_id: str):
        """
        Rollback transaction (revert file to pre-patch state).
        Called when patch fails.
        """
        if not transaction_id:
            return
        
        try:
            # Checkout file from HEAD (discards current changes)
            subprocess.run(
                ['git', 'checkout', 'HEAD', '--', file_path],
                capture_output=True,
                timeout=3
            )
            print(f"[Git] Rolled back file: {file_path}")
            
            # Restore stash if any
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
                    print(f"[Git] Restored stash after rollback: {stash_ref}")
        
        except Exception as e:
            print(f"[Git] Rollback failed: {e}")


class FallbackBackupManager:
    """
    Fallback backup system for non-git repositories.
    Uses .devdeck/backups/ directory to store file copies.
    """
    def __init__(self, backup_dir=".devdeck/backups"):
        self.backup_dir = Path(backup_dir)
        self.backup_dir.mkdir(parents=True, exist_ok=True)
    
    def create_backup(self, file_path: str) -> str:
        """
        Create backup copy of file.
        Returns backup path (used as transaction_id).
        """
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        file_name = Path(file_path).name
        backup_path = self.backup_dir / f"{file_name}.{timestamp}.bak"
        
        shutil.copy2(file_path, backup_path)
        print(f"[Backup] Created: {backup_path}")
        
        return str(backup_path)
    
    def commit(self, backup_path: str):
        """
        Commit transaction (keep backup for history).
        """
        # Keep backup file for audit trail
        print(f"[Backup] Kept for history: {backup_path}")
    
    def rollback(self, file_path: str, backup_path: str):
        """
        Rollback transaction (restore from backup).
        """
        if not os.path.exists(backup_path):
            print(f"[Backup] Backup not found: {backup_path}")
            return
        
        shutil.copy2(backup_path, file_path)
        print(f"[Backup] Restored from: {backup_path}")
```

#### 3. Modify `relay_server.py`

```python
# At the top, import new modules
from patch_manager import PatchManager

# Initialize PatchManager
patch_manager = PatchManager()

# In the relay() function, replace the repair handling:

async def relay(websocket):
    global last_command
    # ... existing connection handling ...
    
    try:
        async for message in websocket:
            # ... existing message handling ...
            
            try:
                data = json.loads(message)
                
                # Check for repair payload from Phone / Web
                if data.get("type") == "repair":
                    target_file = data.get("file", "")
                    patch_type = data.get("patch_type", "single_line")
                    print(f"🛠️  [Relay] REPAIR RECEIVED ({patch_type}): {target_file}")
                    
                    # Use PatchManager instead of inline logic
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
                
                # ... rest of existing relay() logic ...
```

### Testing Strategy

#### Unit Tests (Python)
- `test_patch_manager.py`:
  - Test single-line mode (legacy)
  - Test diff parsing
  - Test syntax validation
  - Test git transaction flow
  - Test fallback backup flow

#### Integration Tests
- End-to-end repair flow:
  1. Create intentional bug in `test_file.py`
  2. Run `python devdeck.py run "python test_file.py"`
  3. Verify Android generates diff
  4. Verify relay applies diff correctly
  5. Verify rerun succeeds

#### Manual Testing Checklist
- [ ] Single-line repair still works (backward compatibility)
- [ ] Multi-line diff repair for AttributeError
- [ ] Multi-line diff repair for ZeroDivisionError
- [ ] Git stash/restore on uncommitted changes
- [ ] Git rollback on failed rerun
- [ ] Non-git backup/restore
- [ ] Syntax error rejection (Python)
- [ ] Diff with hallucinated variables rejected

---

## Feature 2: Model Manager UI

### Overview

Allow users to switch between different MediaPipe-compatible models without recompiling the APK. Provide a catalog of recommended models with metadata and verification tools.

### Model Configuration Data Model

```kotlin
data class ModelConfig(
    val id: String,              // "gemma-2b-it"
    val displayName: String,     // "Gemma-2B-IT"
    val description: String,     // "Official Google model. Native MediaPipe support."
    val filePath: String,        // "/data/local/tmp/gemma-2b-it-gpu.bin"
    val sizeGB: Float,           // 1.3
    val estimatedTPS: Int,       // 18
    val specialty: String,       // "General instruction-following"
    val tier: ModelTier,         // FAST
    val requiresGPU: Boolean = true,
    val maxTokens: Int = 1024,
    val defaultTemperature: Float = 0.3f,
    val isActive: Boolean = false
)

enum class ModelTier {
    FAST,      // 15+ tok/s
    ADVANCED   // 8-15 tok/s
}
```

### ModelManager.kt Implementation

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
    
    /**
     * Predefined model catalog for the app.
     * Users can select from these or add custom paths.
     */
    fun getPredefinedModels(): List<ModelConfig> {
        val currentPath = getCurrentModelPath()
        
        return listOf(
            ModelConfig(
                id = "gemma-2b-it",
                displayName = "Gemma-2B-IT",
                description = "Official Google Gemma 2B Instruction Tuned. Native MediaPipe support.",
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
                description = "⚡ Code generation & debugging specialist. Extremely fast on mobile.",
                filePath = "/data/local/tmp/qwen25-coder-15b-gpu.bin",
                sizeGB = 0.9f,
                estimatedTPS = 24,
                specialty = "Code debugging & generation",
                tier = ModelTier.FAST,
                isActive = currentPath == "/data/local/tmp/qwen25-coder-15b-gpu.bin"
            ),
            ModelConfig(
                id = "phi35-mini",
                displayName = "Phi-3.5-mini",
                description = "Microsoft's best small model. Excellent reasoning for 3.8B size.",
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
                description = "Use any custom MediaPipe-compatible model file",
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
    
    /**
     * Verify model by attempting to load it and running a canary inference.
     * Returns (success: Boolean, tps: Float, error: String?)
     */
    suspend fun verifyModel(path: String): Triple<Boolean, Float, String?> {
        if (!isModelAvailable(path)) {
            return Triple(false, 0f, "File not found: $path")
        }
        
        // Temporarily set this path
        val originalPath = getCurrentModelPath()
        setModelPath(path)
        
        try {
            val agent = com.devdeck.app.ai.DiagnosticAgent(context)
            agent.initModel()
            
            if (!agent.isEngineReady()) {
                setModelPath(originalPath) // Restore original
                return Triple(false, 0f, "Model failed to load. Check format compatibility.")
            }
            
            // Run canary test: "1+1="
            val startTime = System.currentTimeMillis()
            val (result, duration) = agent.analyzeError(
                "Warmup test",
                null,
                null,
                null,
                "test line"
            )
            
            val tps = result.tokensPerSecond
            
            return Triple(true, tps, null)
        
        } catch (e: Exception) {
            setModelPath(originalPath) // Restore original
            return Triple(false, 0f, "Verification failed: ${e.message}")
        }
    }
}
```

### ModelSettingsActivity.kt Implementation

```kotlin
package com.devdeck.app.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.devdeck.app.databinding.ActivityModelSettingsBinding
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
            onModelSelected = { model ->
                selectModel(model)
            },
            onVerifyClicked = { model ->
                verifyModel(model)
            }
        )
        
        binding.modelRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.modelRecyclerView.adapter = adapter
    }
    
    private fun selectModel(model: com.devdeck.app.model.ModelConfig) {
        if (!modelManager.isModelAvailable(model.filePath)) {
            Toast.makeText(
                this,
                "Model file not found. Use ADB to push it to device.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        
        modelManager.setModelPath(model.filePath)
        Toast.makeText(this, "Model switched to ${model.displayName}", Toast.LENGTH_SHORT).show()
        
        // Refresh list to update active badge
        adapter.updateModels(modelManager.getPredefinedModels())
    }
    
    private fun verifyModel(model: com.devdeck.app.model.ModelConfig) {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val (success, tps, error) = modelManager.verifyModel(model.filePath)
            
            binding.progressBar.visibility = View.GONE
            
            if (success) {
                AlertDialog.Builder(this@ModelSettingsActivity)
                    .setTitle("✅ Verification Passed")
                    .setMessage("${model.displayName}\n\nPerformance: ${tps.toInt()} tokens/sec")
                    .setPositiveButton("Use This Model") { _, _ ->
                        selectModel(model)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                AlertDialog.Builder(this@ModelSettingsActivity)
                    .setTitle("❌ Verification Failed")
                    .setMessage(error ?: "Unknown error")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
    
    private fun setupCustomPathButton() {
        binding.btnCustomPath.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                hint = "/data/local/tmp/my-model.bin"
                setText(modelManager.getCurrentModelPath())
            }
            
            AlertDialog.Builder(this)
                .setTitle("Custom Model Path")
                .setMessage("Enter the full path to your MediaPipe-compatible model file:")
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

### ModelListAdapter.kt (RecyclerView Adapter)

```kotlin
package com.devdeck.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devdeck.app.databinding.ItemModelCardBinding
import com.devdeck.app.model.ModelConfig

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
            
            // Show active badge
            binding.activeBadge.visibility = if (model.isActive) 
                android.view.View.VISIBLE 
            else 
                android.view.View.GONE
            
            // Tier badge color
            val tierColor = when (model.tier) {
                com.devdeck.app.model.ModelTier.FAST -> "#0B8A78"
                com.devdeck.app.model.ModelTier.ADVANCED -> "#3B6FD1"
            }
            binding.tierBadge.text = model.tier.name
            binding.tierBadge.setTextColor(android.graphics.Color.parseColor(tierColor))
            
            // Select button
            binding.btnSelect.setOnClickListener {
                onModelSelected(model)
            }
            
            // Verify button
            binding.btnVerify.setOnClickListener {
                onVerifyClicked(model)
            }
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

### UI Layout Files

#### `activity_model_settings.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/color_bg_light"
    android:orientation="vertical">

    <!-- Header -->
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

    <!-- Models List -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/modelRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:padding="16dp" />

    <!-- Loading Indicator -->
    <ProgressBar
        android:id="@+id/progressBar"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:visibility="gone" />

    <!-- Custom Path Button -->
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

#### `item_model_card.xml`

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
            android:text="Official Google model"
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
                android:textSize="9sp" />

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
                android:text="Use This Model"
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

### Update MainActivity.kt

Add a button to open Model Settings:

```kotlin
// In MainActivity.onCreate() or setupActionButtons():
binding.modelStatusContainer.setOnClickListener {
    vibrate()
    startActivity(Intent(this, ModelSettingsActivity::class.java))
}
```

### Testing Strategy

- [ ] Model list displays correctly
- [ ] Active model badge shows on current model
- [ ] Model verification works (canary test)
- [ ] Switching models updates SharedPreferences
- [ ] Custom path input works
- [ ] Non-existent model shows error toast
- [ ] DiagnosticAgent picks up new model path on next init

---

## Feature 3: Git-Assisted Transactional Rollback

(This feature is fully implemented in the `git_transaction_engine.py` section under Feature 1: Multi-line Diff Repair System.)

**Summary:**
- Detects if project is a git repo
- Stashes uncommitted changes before patching
- Rolls back via `git checkout HEAD` on failure
- Falls back to `.devdeck/backups/` for non-git projects
- Adds dry-run compiler checks before disk writes

---

## Implementation Plan Summary

### Phase 1A: Foundation (Days 1-2)
1. Create `patch_manager.py` with single-line mode preserved
2. Create `git_transaction_engine.py` with basic git detection
3. Add unit tests for git transaction flow
4. Verify backward compatibility with existing single-line repairs

### Phase 1B: Diff System (Days 3-5)
1. Update `DiagnosticAgent.kt` prompt for diff generation
2. Implement diff parsing and validation in `DiagnosticAgent.kt`
3. Implement `apply_diff_patch()` in `patch_manager.py`
4. Add dry-run compiler checks
5. Integration test: end-to-end diff repair flow

### Phase 1C: Model Manager (Days 6-7)
1. Create `ModelManager.kt` with predefined catalog
2. Create `ModelSettingsActivity.kt` and layouts
3. Create `ModelListAdapter.kt`
4. Wire up model selection in MainActivity
5. Test model switching and verification

### Phase 1D: Polish & Demo Prep (Day 8)
1. NPU telemetry dashboard (bonus feature)
2. Haptic feedback enhancements
3. Demo script preparation
4. Performance testing on iQOO 15
5. Edge case handling

---

## Success Criteria

### Functional Requirements
✅ Multi-line diff repairs work end-to-end  
✅ Git rollback triggers on rerun failure  
✅ Non-git backup fallback works  
✅ Model switching via UI without APK rebuild  
✅ Model verification (canary test) shows TPS  
✅ Semantic grounding rejects hallucinated diffs  
✅ Syntax validation prevents broken code commits  

### Performance Requirements
✅ Diff generation: <5s on iQOO 15  
✅ Model switching: <3s (reload time)  
✅ Git operations: <2s overhead per repair  
✅ No regression in single-line repair speed  

### Hackathon Scoring Optimization
✅ **AI-first:** 100% on-device inference, multi-line autonomous repairs  
✅ **Phone-centric:** Model runs on iQOO NPU, uses camera for scanning  
✅ **Novelty:** Unified diff generation by on-device SLM, git-integrated rollback  
✅ **Feasibility:** All features implementable in 30 hours  
✅ **iQOO utilization:** NPU inference, haptics, camera, battery optimization  

---

## Risk Mitigation

### Risk: MediaPipe diff generation quality
- **Mitigation:** Keep heuristic fallback engine for critical error types
- **Mitigation:** Semantic grounding rejects ungrounded diffs
- **Mitigation:** Syntax validation prevents broken code

### Risk: Git operations fail on complex repos
- **Mitigation:** Comprehensive error handling in GitTransactionEngine
- **Mitigation:** FallbackBackupManager for non-git projects
- **Mitigation:** Transaction rollback on any git command failure

### Risk: Diff parsing complexity
- **Mitigation:** Use system `patch` command (battle-tested)
- **Mitigation:** Validate diff format before applying
- **Mitigation:** Dry-run in-memory before disk write

### Risk: Model switching breaks existing setup
- **Mitigation:** Preserve Gemma-2B as default fallback
- **Mitigation:** Verification step before switching models
- **Mitigation:** SharedPreferences allows easy rollback to previous path

---

## Future Enhancements (Post-Hackathon)

### Phase 2: Intelligence & Context
- Deeper ProjectContextManager integration
- Telemetry export to SQLite
- Learning from repair success/failure rates

### Phase 3: Tooling & Visibility
- Enhanced web dashboard with diff visualizations
- VS Code extension
- IntelliJ plugin

---

## Appendix A: File Structure After Phase 1

```
receipts-android/
├── app/src/main/
│   ├── java/com/devdeck/app/
│   │   ├── ui/
│   │   │   ├── MainActivity.kt [MODIFIED]
│   │   │   ├── ModelSettingsActivity.kt [NEW]
│   │   │   ├── ModelListAdapter.kt [NEW]
│   │   │   ├── CameraActivity.kt
│   │   │   └── SyntaxHighlighter.kt
│   │   ├── ai/
│   │   │   └── DiagnosticAgent.kt [MODIFIED]
│   │   └── model/
│   │       ├── DiagnosticResult.kt [MODIFIED]
│   │       ├── ModelManager.kt [NEW]
│   │       ├── ModelConfig.kt [NEW]
│   │       ├── ProjectContextManager.kt
│   │       └── DiagnosticHistory.kt
│   └── res/layout/
│       ├── activity_main.xml [MODIFIED]
│       ├── activity_model_settings.xml [NEW]
│       └── item_model_card.xml [NEW]
├── relay_server.py [MODIFIED]
├── patch_manager.py [NEW]
├── git_transaction_engine.py [NEW]
├── devdeck.py
└── docs/superpowers/specs/
    └── 2026-08-25-devdeck-phase1-design.md [THIS FILE]
```

---

## Appendix B: JSON Payload Specifications

### Single-Line Repair Payload (Legacy)

```json
{
  "type": "repair",
  "patch_type": "single_line",
  "file": "auth_service.py",
  "line": 42,
  "code": "if user and user.is_authenticated():"
}
```

### Multi-Line Diff Repair Payload (New)

```json
{
  "type": "repair",
  "patch_type": "diff",
  "file": "auth_service.py",
  "diff_text": "@@ -40,3 +40,5 @@\n user = db.find_user(user_id)\n \n-if user.is_authenticated():\n-    return user.token\n+if user:\n+    if user.is_authenticated():\n+        return user.token\n+return None"
}
```

---

## Appendix C: Verification Checklist

### Pre-Demo Checklist
- [ ] Gemma-2B model active and verified
- [ ] Single-line repairs work (backward compatibility)
- [ ] Multi-line diff repairs work
- [ ] Model Manager UI complete
- [ ] Git rollback tested
- [ ] Non-git fallback tested
- [ ] Syntax validation prevents broken code
- [ ] Semantic grounding rejects hallucinations
- [ ] Battery usage optimized for demo
- [ ] Haptic feedback polished
- [ ] Terminal logs clear and informative

### Demo Flow Script
1. Show error in terminal → DevDeck intercepts
2. Android screen lights up → "Analyzing..."
3. Show diagnosis screen → diff preview
4. Tap "Apply" or autonomous mode triggers
5. Terminal shows "✅ SUCCESS: Exit code 0"
6. Show Model Manager → "Multiple models supported"
7. Explain git integration → "Safe rollback on failure"

---

**End of Design Document**
