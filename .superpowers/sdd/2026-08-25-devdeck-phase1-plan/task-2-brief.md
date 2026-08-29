# Task 2: Patch Manager with Diff Support

## Requirements

Create `patch_manager.py` and `test_patch_manager.py` to implement a unified patch management system that handles both single-line repairs and multi-line unified diff patches, with automatic rollback on failure.

## Files to Create/Modify

- Create: `patch_manager.py` - Main patch manager with diff support
- Create: `test_patch_manager.py` - Unit tests for compile checking
- Modify: `relay_server.py` - Integrate PatchManager into the repair handling block

## Interface Contract

**Consumes (from Task 1):**
- `GitTransactionEngine` from `git_transaction_engine.py`
- `FallbackBackupManager` from `git_transaction_engine.py`

**Produces:**
- `PatchManager.apply_repair(data: dict, last_command: str) -> tuple[bool, str, str, str]`
  - Returns: (success: bool, error_msg: str, file_path: str, transaction_id: str)

**PatchManager methods:**
- `apply_repair(data, last_command)` - Main entry point, dispatches to single_line or diff based on patch_type
- `apply_single_line_repair(data, last_command, file_path)` - Legacy single-line repair
- `apply_diff_patch(data, last_command, file_path)` - New unified diff patch application
- `dry_run_compile_check(file_path, content)` - Syntax validation before writing
- `rerun_command(command)` - Execute command to verify fix
- `_apply_diff_to_string(original, diff_text)` - Apply unified diff using system `patch` command

## Global Constraints

- Git Integration: Use GitTransactionEngine when in git repo, fallback to FallbackBackupManager otherwise
- All subprocess calls must have timeout limits (3-8 seconds)
- Syntax validation required for .py files using `python -m py_compile`
- Basic brace matching for .js/.ts/.kt/.java files
- Single-line mode rejects multi-line content (safety constraint)
- Diff mode limited to 2-20 lines changed (from spec)

## Implementation Details

**Patch application flow:**
1. Validate file exists
2. Create transaction (git stash or backup)
3. Apply patch (single-line or unified diff)
4. Run syntax check (compile validation)
5. Rerun original failing command
6. On success: commit transaction; On failure: rollback transaction

**Unified diff format:**
- Uses system `patch` command via subprocess
- Constructs proper patch header: `--- a/temp\n+++ b/temp\n{diff_text}\n`
- Applies to temp file, reads result back
- Handles patch command failure gracefully

**Integration into relay_server.py:**
- Replace entire repair handling block (around lines 40-116)
- Import PatchManager at top
- Create global instance: `patch_manager = PatchManager()`
- Call `patch_manager.apply_repair(data, last_command)`
- Broadcast success/failure via WebSocket

## Testing

Test must verify:
- Valid Python code passes dry_run_compile_check
- Invalid Python syntax fails dry_run_compile_check
- Run with: `python -m unittest test_patch_manager.py`

## Success Criteria

1. Unit tests pass
2. relay_server.py starts without errors
3. PatchManager correctly routes single_line vs diff patch types
4. Syntax validation prevents broken code from being written
5. Rollback restores original file on any failure
