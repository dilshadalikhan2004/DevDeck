# Task 1: Git Transaction Engine

## Requirements

Create `git_transaction_engine.py` and `test_git_transaction.py` to implement transactional file backup and rollback using git stashing for git repos and filesystem backups for non-git directories.

## Files to Create

- `git_transaction_engine.py` - Main transaction engine
- `test_git_transaction.py` - Unit tests

## Interface Contract

**GitTransactionEngine:**
- `is_git_repo() -> bool` - Check if current directory is a git repository
- `create_transaction(file_path: str) -> str` - Create transaction, returns transaction_id (stash message or HEAD commit)
- `commit_transaction(transaction_id: str) -> None` - Finalize transaction by dropping stash
- `rollback_transaction(file_path: str, transaction_id: str) -> None` - Restore file to pre-transaction state

**FallbackBackupManager:**
- `create_backup(file_path: str) -> str` - Create timestamped .bak file, returns backup_path
- `commit(backup_path: str) -> None` - Keep backup (no-op for now)
- `rollback(file_path: str, backup_path: str) -> None` - Restore from backup file

## Global Constraints

- Git Integration: Execute git CLI subprocesses with timeout protection
- Fallback: Use `.devdeck/backups/` directory for non-git backup storage
- All subprocess calls must have timeout limits (2-5 seconds)

## Implementation Details

Use the exact code provided in the plan:
- GitTransactionEngine checks git repo status via `git rev-parse --is-inside-work-tree`
- Stash format: `devdeck_pre_repair_{timestamp}` for easy identification
- FallbackBackupManager uses `.devdeck/backups/` with timestamp suffixes
- All operations must handle exceptions gracefully with print statements

## Testing

Test must verify:
- Backup creation produces a valid file
- File modification followed by rollback restores original content exactly
- Run with: `python -m unittest test_git_transaction.py`

## Success Criteria

1. Unit test passes
2. Code handles both git and non-git scenarios
3. No exceptions during normal operation
4. Print statements for debugging visibility
