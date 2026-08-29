"""DevDeck CLI — Transparent Repair Runtime for real codebases."""

from __future__ import annotations

import sys
import subprocess
import json
import asyncio
import websockets
from datetime import datetime
import re
import os
import hashlib
import uuid
from pathlib import Path

from bridge_security import canonical_project_root, sha256_file
from pipeline_events import crash_to_dispatch_events, make_event
from repo_context import ProjectBrain, build_evidence_pack
from sandbox_verifier import SandboxVerifier
from repair_memory import RepairMemory, AutonomyPolicy, AutonomyLevel

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass


_project_brains = {}


async def send_event(payload: dict):
    uri = os.environ.get("DEVDECK_RELAY_URI", "ws://localhost:8765")
    try:
        async with websockets.connect(uri) as websocket:
            await websocket.send(json.dumps(payload))
    except Exception:
        pass


async def send_events(payloads: list[dict]):
    uri = os.environ.get("DEVDECK_RELAY_URI", "ws://localhost:8765")
    try:
        async with websockets.connect(uri) as websocket:
            for payload in payloads:
                await websocket.send(json.dumps(payload))
    except Exception:
        pass


def detect_language(file_path: str | None) -> str:
    if not file_path:
        return "unknown"
    ext = os.path.splitext(file_path)[1].lower()
    mapping = {
        ".py": "python",
        ".js": "javascript",
        ".ts": "typescript",
        ".jsx": "javascript",
        ".tsx": "typescript",
        ".kt": "kotlin",
        ".java": "java",
        ".rs": "rust",
        ".go": "go",
        ".cpp": "cpp",
        ".c": "c",
        ".rb": "ruby",
        ".php": "php",
    }
    return mapping.get(ext, "auto")


def get_error_metadata(stderr: str):
    patterns = [
        r'File "(.*?)", line (\d+)',
        r'at (?:[^\(\n]+\()?([a-zA-Z0-9_\-\./\\]+\.(?:js|ts|jsx|tsx|mjs)):(\d+)',
        r'([a-zA-Z0-9_\-\./\\]+\.(?:kt|java|cpp|c|rs|go)):(?:(?:\()?(\d+)(?:,\s*\d+\))?|(\d+):\d+)',
        r'\(([a-zA-Z0-9_\-]+\.(?:java|kt)):(\d+)\)',
        r'(?:-->|panicked at .*?,\s*)([a-zA-Z0-9_\-\./\\]+\.rs):(\d+)',
        r'([a-zA-Z0-9_\-\./\\]+\.(?:py|js|ts|kt|java|cpp|c|rs|go|rb|php)):(\d+)',
    ]

    found_file = None
    found_line = None
    lines = stderr.splitlines()

    for line in reversed(lines):
        for pattern in patterns:
            match = re.search(pattern, line)
            if match:
                groups = [g for g in match.groups() if g is not None]
                if len(groups) >= 2:
                    candidate_file = groups[0]
                    candidate_line = next((int(g) for g in groups[1:] if g.isdigit()), None)
                    if candidate_line is not None:
                        found_file = candidate_file
                        found_line = candidate_line
                        if os.path.exists(found_file):
                            break
        if found_file and os.path.exists(found_file):
            break

    context = None
    original_line = None
    if found_file and os.path.exists(found_file):
        try:
            with open(found_file, 'r', encoding='utf-8', errors='replace') as f:
                all_lines = f.readlines()
                if 1 <= found_line <= len(all_lines):
                    original_line = all_lines[found_line - 1].strip()

                start = max(0, found_line - 6)
                end = min(len(all_lines), found_line + 6)
                context_lines = []
                for idx in range(start, end):
                    cur_line_num = idx + 1
                    line_content = all_lines[idx]
                    prefix = ">>> " if cur_line_num == found_line else "    "
                    context_lines.append(f"{prefix}{cur_line_num:4d} | {line_content}")
                context = "".join(context_lines)
        except Exception as e:
            print(f"[DevDeck] Error reading source file: {e}")

    return context, found_file, found_line, original_line


def clean_stderr(stderr: str) -> str:
    ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
    clean_text = ansi_escape.sub('', stderr)
    lines = clean_text.splitlines()
    if len(lines) > 50:
        lines = lines[-50:]
    return "\n".join(lines)


def get_brain(project_root: str | Path) -> tuple[ProjectBrain, bool]:
    path = Path(project_root).resolve()
    rebuilt = path not in _project_brains
    if rebuilt:
        _project_brains[path] = ProjectBrain.build(path)
    return _project_brains[path], rebuilt


def build_incident_payload(command: str, stderr: str, project_root: str | Path | None = None) -> dict:
    source_context, file_path, line_num, original_line = get_error_metadata(stderr)
    project = canonical_project_root(project_root or Path.cwd())
    source_path = Path(file_path).resolve() if file_path and Path(file_path).exists() else None
    relative_file = None
    expected_sha256 = None
    if source_path and source_path.is_file() and project.path in source_path.parents:
        relative_file = source_path.relative_to(project.path).as_posix()
        expected_sha256 = sha256_file(source_path)

    context_budget = int(os.environ.get("DEVDECK_CONTEXT_TOKEN_BUDGET", "650"))
    brain, indexing_rebuilt = get_brain(project.path)
    evidence = build_evidence_pack(
        index=brain,
        error_text=clean_stderr(stderr),
        target_file=relative_file,
        target_line=line_num,
        token_budget=context_budget,
    )

    incident_id = str(uuid.uuid4())
    receipt_dict = evidence.receipt.to_dict() if evidence.receipt else None

    return {
        "type": "incident",
        "protocol_version": 2,
        "incident_id": incident_id,
        "project_id": project.project_id,
        "project_root": str(project.path),
        "timestamp": datetime.now().isoformat(),
        "command": command,
        "error_text": clean_stderr(stderr),
        "source_context": source_context,
        "error_file": relative_file,
        "error_line": line_num,
        "original_line": original_line,
        "language": detect_language(relative_file),
        "expected_sha256": expected_sha256,
        "repository_context": evidence.text,
        "repository_context_tokens": evidence.estimated_tokens,
        "allowed_symbols": sorted(evidence.allowed_symbols),
        "context_receipt": receipt_dict,
        "indexing_rebuilt": indexing_rebuilt,
    }


def scan_repository(project_root: str | Path | None = None) -> None:
    root = Path(project_root or Path.cwd()).resolve()
    print(f"\n🧠 [DevDeck] Scanning repository to build local Project Brain: {root}")
    print("=" * 65)
    brain = ProjectBrain.build(root)
    _project_brains[root] = brain
    summary = brain.summary()
    
    print(f"● Project Ready")
    print(f"  Indexed {summary['files_indexed']} source files · {summary['symbols_indexed']} symbols · {summary['tests_discovered']} tests discovered")
    if summary['tests']:
        print(f"  Sample Tests: {', '.join(summary['tests'][:3])}")
    print("=" * 65)

    # Broadcast brain ready event to relay server
    asyncio.run(send_event({
        "type": "brain_ready",
        "timestamp": datetime.now().isoformat(),
        "project_root": str(root),
        "files_indexed": summary["files_indexed"],
        "symbols_indexed": summary["symbols_indexed"],
        "tests_discovered": summary["tests_discovered"],
        "tests": summary["tests"],
    }))


def run_command_with_watch(command: str) -> int:
    root = Path.cwd()
    memory = RepairMemory(root)
    policy = memory.get_policy()

    print(f"\n[DevDeck Active Watch] Executing: {command} (Autonomy Policy: {policy.level.value})")
    print("=" * 65)
    process = subprocess.Popen(
        command,
        shell=True,
        stdout=sys.stdout,
        stderr=subprocess.PIPE,
        text=True
    )

    _, stderr = process.communicate()

    if process.returncode != 0:
        print("=" * 65)
        print(f"❌ [DevDeck] Command failed (Exit Code: {process.returncode}). Analyzing incident...")
        payload = build_incident_payload(command, stderr, root)

        print(f"\n● Failure captured")
        print(f"  {command} failed at {payload['error_file']}:{payload['error_line']}")

        receipt = payload.get("context_receipt")
        if receipt:
            print(f"\n● Evidence selected")
            print(f"  {receipt['total_files']} files · {receipt['total_symbols']} symbols · {receipt['total_tokens']} context tokens")
            print("  Selected Context Files:")
            for item in receipt.get("items", []):
                reasons_str = "; ".join(item.get("reasons", []))
                print(f"    - {item['file']}:{item['line_start']}-{item['line_end']} [{reasons_str}]")

        print(f"\n● Dispatched incident to paired clients (Android / Web Console)...")
        events = crash_to_dispatch_events(
            payload["incident_id"],
            indexing_rebuilt=payload.get("indexing_rebuilt", False),
            command=command,
        )
        events.append(payload)
        events.append(make_event(payload["incident_id"], "sent_to_phone", "completed", "Incident handed to paired phone"))
        asyncio.run(send_events(events))

        # Log incident in memory
        memory.log_incident(
            incident_id=payload["incident_id"],
            command=command,
            error_file=payload.get("error_file") or "unknown",
            error_line=payload.get("error_line") or 0,
            error_text=payload["error_text"],
            context_receipt=receipt,
            candidate_patch=None,
            repair_proof=None,
            trust_breakdown=None,
            status="CAPTURED",
        )

    return process.returncode


def replay_incident(incident_id: str) -> None:
    root = Path.cwd()
    memory = RepairMemory(root)
    incident = memory.get_incident_by_id(incident_id)
    if not incident:
        print(f"❌ [DevDeck] Incident '{incident_id}' not found in audit memory.")
        sys.exit(1)

    print(f"\n⏮️  [DevDeck Incident Replay] ID: {incident['incident_id']}")
    print("=" * 65)
    print(f"Timestamp: {incident.get('timestamp')}")
    print(f"Command:   {incident.get('command')}")
    print(f"Target:    {incident.get('error_file')}:{incident.get('error_line')}")
    print(f"Status:    {incident.get('status')}")
    
    receipt = incident.get("context_receipt")
    if receipt:
        print(f"\nContext Receipt ({receipt.get('total_tokens')} tokens, {receipt.get('total_files')} files):")
        for item in receipt.get("items", []):
            print(f"  • {item.get('file')}: {', '.join(item.get('reasons', []))}")

    patch = incident.get("candidate_patch")
    if patch:
        print(f"\nCandidate Patch ({patch.get('patch_type')}):")
        print(patch.get("diff_text") or patch.get("repair_code"))

    proof = incident.get("repair_proof")
    if proof:
        print(f"\nSandbox Verification Proof (Exit: {proof.get('exit_code')}, Duration: {proof.get('execution_duration_ms')}ms):")
        print(f"  Passed: {proof.get('sandbox_passed')}")
        if proof.get("sandbox_stdout"):
            print(f"  Output:\n{proof.get('sandbox_stdout')[:300]}")

    trust = incident.get("trust_breakdown")
    if trust:
        print(f"\nTrust Meter: {trust.get('total_score')}% [{trust.get('trust_level')}]")
        for reason in trust.get("reasons", []):
            print(f"  ✓ {reason}")
    print("=" * 65)


def set_or_get_policy(arg: str | None = None) -> None:
    root = Path.cwd()
    memory = RepairMemory(root)
    if arg is None:
        policy = memory.get_policy()
        print(f"[DevDeck Autonomy Policy] Current level: {policy.level.value}")
        print("Available options: suggest_only, approve_each, auto_apply_verified_low_risk, full_autonomous")
    else:
        new_pol = AutonomyPolicy.from_string(arg)
        memory.set_policy(new_pol.level)
        print(f"✅ [DevDeck Autonomy Policy] Updated to: {new_pol.level.value}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("DevDeck Transparent Repair Runtime")
        print("Usage:")
        print("  python devdeck.py scan")
        print("  python devdeck.py run \"<command>\"")
        print("  python devdeck.py replay <incident_id>")
        print("  python devdeck.py policy [suggest | approve | low-risk | auto]")
        sys.exit(1)

    command_type = sys.argv[1].lower()
    if command_type == "scan":
        scan_repository()
        sys.exit(0)
    elif command_type == "run":
        if len(sys.argv) < 3:
            print("Usage: python devdeck.py run \"<command>\"")
            sys.exit(1)
        sys.exit(run_command_with_watch(sys.argv[2]))
    elif command_type == "replay":
        if len(sys.argv) < 3:
            print("Usage: python devdeck.py replay <incident_id>")
            sys.exit(1)
        replay_incident(sys.argv[2])
        sys.exit(0)
    elif command_type == "policy":
        arg = sys.argv[2] if len(sys.argv) > 2 else None
        set_or_get_policy(arg)
        sys.exit(0)
    elif command_type == "demo":
        # Run demo
        demo_trace = """Traceback (most recent call last):
  File "auth_service.py", line 42, in get_user_token
    if user.is_authenticated():
AttributeError: 'NoneType' object has no attribute 'is_authenticated'"""
        payload = build_incident_payload("python auth_service.py", demo_trace)
        print("Dispatched demo incident...")
        asyncio.run(send_event(payload))
        sys.exit(0)
    else:
        # Default fallback to run
        sys.exit(run_command_with_watch(" ".join(sys.argv[1:])))
