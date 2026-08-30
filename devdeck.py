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
import shutil
import socket
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


def _watch_outcome(data: dict, incident_id: str) -> str | None:
    if data.get("incident_id") and data.get("incident_id") != incident_id:
        if data.get("type") not in ("sandbox_line", "log_stream"):
            return None
    kind = data.get("type")
    if kind == "sandbox_line":
        line = (data.get("line") or "").rstrip()
        if line:
            print(f"  [Sandbox] {line}")
        return None
    if kind == "log_stream":
        line = (data.get("log_line") or "").rstrip()
        if line:
            print(f"  {line}")
        return None
    if kind == "repair_success":
        print("✅ Repair applied and verified on disk.")
        return "success"
    if kind == "repair_failed":
        print(f"❌ Live apply/verify failed: {data.get('message')}")
        return "failed"
    if kind != "pipeline_event":
        return None
    stage = data.get("stage")
    phase = data.get("phase")
    message = data.get("message") or ""
    print(f"  [{stage} {phase}] {message}")
    if stage == "complete" and phase == "completed":
        return "success"
    if stage == "rolled_back" or (stage == "verifying" and phase == "failed"):
        return "failed"
    if phase == "review_rejected" or (stage == "awaiting_review" and phase == "failed"):
        return "rejected"
    if stage == "diagnosing" and phase == "failed":
        return "failed"
    if stage == "awaiting_review" and phase == "completed":
        print("  → Approve, Reject, or Request Changes on the phone. This terminal will wait.")
    return None


async def dispatch_and_wait(payloads: list[dict], incident_id: str, timeout_seconds: float) -> str:
    """Send incident traffic then stay on the socket until the phone/relay finishes."""
    uri = os.environ.get("DEVDECK_RELAY_URI", "ws://localhost:8765")
    try:
        async with websockets.connect(uri) as websocket:
            for payload in payloads:
                await websocket.send(json.dumps(payload))
            print("● Waiting for phone review / repair result. Ctrl+C detaches without cancelling.")
            print("=" * 65)
            loop = asyncio.get_running_loop()
            deadline = loop.time() + timeout_seconds
            while True:
                remaining = deadline - loop.time()
                if remaining <= 0:
                    print("⏱️ Timed out waiting for the phone. Incident is still active on the relay.")
                    return "timeout"
                raw = await asyncio.wait_for(websocket.recv(), timeout=remaining)
                try:
                    data = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                outcome = _watch_outcome(data, incident_id)
                if outcome:
                    return outcome
    except asyncio.TimeoutError:
        print("⏱️ Timed out waiting for the phone. Incident is still active on the relay.")
        return "timeout"
    except Exception as error:
        print(f"⚠️ Could not wait on relay ({error}). Incident was dispatched.")
        return "disconnected"


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


def _is_stdlib_frame(path: str) -> bool:
    norm = path.replace("\\", "/").lower()
    return any(x in norm for x in [
        "lib/unittest", "lib\\unittest", "site-packages", "node_modules", "importlib", "<frozen", "<string>",
        "unittest/loader", "unittest/runner", "unittest/case", "unittest/suite",
        "/lib/python", "\\lib\\python", "node:internal",
    ])


def _resolve_under_project(raw: str, project_root: Path | None) -> Path | None:
    if not raw:
        return None
    cleaned = raw.strip().strip('"').replace("\\", "/")
    candidates: list[Path] = [Path(raw), Path(cleaned)]
    if project_root:
        candidates.append(project_root / cleaned)
        candidates.append(project_root / Path(raw).name)
        if not cleaned.endswith(".py") and re.fullmatch(r"[A-Za-z_][\w.]*", cleaned):
            dotted = cleaned.replace(".", "/") + ".py"
            candidates.append(project_root / dotted)
            candidates.append(project_root / "src" / dotted)
            candidates.append(project_root / "tests" / dotted)
    for cand in candidates:
        try:
            resolved = cand.resolve()
        except OSError:
            continue
        if resolved.is_file():
            return resolved
    return None


def _source_from_command(command: str, project_root: Path | None) -> Path | None:
    if not command:
        return None
    for token in re.findall(r"[^\s\"']+\.py", command):
        found = _resolve_under_project(token, project_root)
        if found:
            return found
    dotted = re.search(r"unittest\s+([A-Za-z_][\w.]*)", command)
    if dotted:
        found = _resolve_under_project(dotted.group(1), project_root)
        if found:
            return found
    return None


def is_cli_invocation_error(command: str) -> bool:
    """True only when the watched command itself is a leftover `run` token."""
    token = command.strip().split(None, 1)[0] if command.strip() else ""
    return token.lower() == "run"


def get_error_metadata(stderr: str, project_root: str | Path | None = None, command: str = ""):
    patterns = [
        r'File "(.*?)", line (\d+)',
        r'at (?:[^\(\n]+\()?([a-zA-Z0-9_\-\./\\ ]+\.(?:js|ts|jsx|tsx|mjs)):(\d+)',
        r'([a-zA-Z0-9_\-\./\\ ]+\.(?:kt|java|cpp|c|rs|go)):(?:(?:\()?(\d+)(?:,\s*\d+\))?|(\d+):\d+)',
        r'\(([a-zA-Z0-9_\- ]+\.(?:java|kt)):(\d+)\)',
        r'(?:-->|panicked at .*?,\s*)([a-zA-Z0-9_\-\./\\ ]+\.rs):(\d+)',
        r'([a-zA-Z0-9_\-\./\\ ]+\.(?:py|js|ts|kt|java|cpp|c|rs|go|rb|php)):(\d+)',
    ]

    root = Path(project_root).resolve() if project_root else Path.cwd()
    found_path: Path | None = None
    found_line: int | None = None
    lines = stderr.splitlines()

    for line in reversed(lines):
        for pattern in patterns:
            match = re.search(pattern, line)
            if match:
                groups = [g for g in match.groups() if g is not None]
                if len(groups) >= 2:
                    candidate_file = groups[0]
                    candidate_line = next((int(g) for g in groups[1:] if g.isdigit()), None)
                    if candidate_line is None or _is_stdlib_frame(candidate_file):
                        continue
                    resolved = _resolve_under_project(candidate_file, root)
                    if resolved:
                        found_path = resolved
                        found_line = candidate_line
                        break
        if found_path:
            break

    if found_path is None:
        fail_mod = re.search(
            r"(?:FAIL|ERROR):\s+\S+\s+\(([A-Za-z_][\w.]*)\)",
            stderr,
        )
        if fail_mod:
            found_path = _resolve_under_project(fail_mod.group(1), root)
            found_line = found_line or 1

    if found_path is None:
        import_match = (
            re.search(r"No module named ['\"]([A-Za-z_][\w.]*)['\"]", stderr)
            or re.search(r"from ['\"]?([A-Za-z_][\w.]+)['\"]?", stderr)
            or re.search(r"module ['\"]?([A-Za-z_][\w.]+)['\"]?", stderr)
        )
        if import_match:
            found_path = _resolve_under_project(import_match.group(1), root)
            found_line = found_line or 1

    if found_path is None:
        found_path = _source_from_command(command, root)
        found_line = found_line or 1

    context = None
    original_line = None
    found_file = str(found_path) if found_path else None
    if found_path and found_path.is_file() and found_line:
        try:
            with open(found_path, "r", encoding="utf-8", errors="replace") as f:
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

    if not original_line:
        original_line = _source_line_from_traceback(stderr)

    return context, found_file, found_line, original_line


def _source_line_from_traceback(stderr: str) -> str | None:
    """Pull the source line Python printed under File ..., line N."""
    rows = stderr.splitlines()
    best = None
    file_re = re.compile(r'File "[^"]+", line \d+')
    for i, row in enumerate(rows):
        if file_re.search(row) and i + 1 < len(rows):
            nxt = rows[i + 1].strip()
            if nxt and not nxt.startswith("File ") and not re.match(r"^\w+(Error|Exception)\b", nxt):
                best = nxt
    return best


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
    project = canonical_project_root(project_root or Path.cwd())
    source_context, file_path, line_num, original_line = get_error_metadata(
        stderr, project.path, command
    )
    source_path = None
    if file_path:
        cand_p = (project.path / file_path).resolve()
        if cand_p.is_file():
            source_path = cand_p
        elif Path(file_path).resolve().is_file():
            source_path = Path(file_path).resolve()

    relative_file = None
    expected_sha256 = None
    if source_path and source_path.is_file():
        if project.path in source_path.parents or project.path == source_path.parent:
            relative_file = source_path.relative_to(project.path).as_posix()
        else:
            relative_file = source_path.name
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
        "error_file": relative_file or "",
        "error_line": int(line_num or 0),
        "original_line": original_line or "",
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

    # Broadcast brain ready event to relay server via both WebSocket & HTTP
    payload = {
        "type": "brain_ready",
        "timestamp": datetime.now().isoformat(),
        "project_root": str(root),
        "files_indexed": summary["files_indexed"],
        "symbols_indexed": summary["symbols_indexed"],
        "tests_discovered": summary["tests_discovered"],
        "tests": summary["tests"],
        "sample_symbols": summary.get("sample_symbols") or sorted(brain.symbols.keys())[:30],
        "edges": summary.get("edges", []),
    }
    try:
        asyncio.run(send_event(payload))
    except Exception:
        pass
    try:
        import urllib.request
        req = urllib.request.Request(
            "http://127.0.0.1:8766/brain",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=1) as resp:
            pass
    except Exception:
        pass


def extract_repo_slug(url_or_path: str) -> str:
    """Extract a clean folder name/slug from a git URL or local path."""
    raw = url_or_path.strip().rstrip("/")
    if raw.endswith(".git"):
        raw = raw[:-4]
    # Handle git@github.com:owner/repo or https://github.com/owner/repo
    if ":" in raw and not (len(raw) > 1 and raw[1] == ":"):  # Not Windows drive letter C:
        parts = raw.split(":")[-1].split("/")
    else:
        parts = raw.replace("\\", "/").split("/")
    slug = parts[-1] if parts and parts[-1] else "linked_repo"
    # Keep only safe filename chars
    return re.sub(r"[^\w.-]", "_", slug)


def link_repository(repo_url: str, target_dir: str | Path | None = None) -> Path:
    """Clone a repository shallowly (--depth=1) using system git and build the on-device Knowledge Graph."""
    print("=" * 65)
    print("🔗 [DevDeck] Linking Remote Repository")
    print("=" * 65)

    git_bin = shutil.which("git")
    if not git_bin:
        print("❌ [DevDeck] Error: 'git' command not found on PATH. Please install Git.")
        sys.exit(1)

    slug = extract_repo_slug(repo_url)
    if target_dir:
        dest = Path(target_dir).resolve()
    else:
        state_dir = Path(os.environ.get("DEVDECK_STATE_DIR", ".devdeck"))
        dest = (state_dir / "repos" / slug).resolve()

    if dest.exists() and any(dest.iterdir()):
        print(f"• Repository already exists locally at: {dest}")
        print("• Syncing latest changes...")
        sync_repository(dest)
        return dest

    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"• Source: {repo_url}")
    print(f"• Destination: {dest}")
    print("• Executing shallow clone (depth=1, zero credential storage)...")

    cmd = [git_bin, "clone", "--depth=1", repo_url, str(dest)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"❌ [DevDeck] Git clone failed (Exit Code {res.returncode}):")
        if res.stderr:
            print(res.stderr.strip())
        sys.exit(res.returncode)

    print("✅ Repository cloned successfully.")
    scan_repository(dest)
    return dest


def sync_repository(project_root: str | Path | None = None) -> None:
    """Pull latest updates from remote repository and refresh the on-device Knowledge Graph."""
    root = Path(project_root or Path.cwd()).resolve()
    print("=" * 65)
    print(f"🔄 [DevDeck] Syncing Repository: {root}")
    print("=" * 65)

    git_bin = shutil.which("git")
    if git_bin and (root / ".git").exists():
        print("• Pulling latest changes from remote (git pull --ff-only)...")
        res = subprocess.run([git_bin, "pull", "--ff-only"], cwd=str(root), capture_output=True, text=True)
        if res.returncode == 0:
            print(f"  {res.stdout.strip() if res.stdout else 'Already up to date.'}")
        else:
            print(f"  ⚠️ Warning: git pull returned code {res.returncode} (offline or divergence); indexing current local tree.")
            if res.stderr:
                print(f"  {res.stderr.strip()}")
    else:
        print("• Directory is not a git clone; indexing current files on disk.")

    scan_repository(root)


def parse_run_command(args: list[str]) -> str:
    """Join remaining argv into a command, dropping duplicated ``run`` keywords."""
    cleaned = list(args)
    while cleaned and cleaned[0].lower() == "run":
        cleaned.pop(0)
    return " ".join(cleaned).strip()


_UNITTEST_PY_PATH = re.compile(
    r"(?P<pre>(?:python(?:\d+(?:\.\d+)*)?|py)\s+-m\s+unittest\s+)(?P<path>[^\s]+?\.py)(?!\S)",
    re.IGNORECASE,
)


def normalize_watched_command(command: str, cwd: str | Path | None = None) -> str:
    """Load ``python -m unittest path/to/test.py`` via discover so Windows does not import ``tests.unit``."""
    if re.search(r"-m\s+unittest\s+discover\b", command, re.IGNORECASE):
        return command
    match = _UNITTEST_PY_PATH.search(command)
    if not match:
        return command
    root = Path(cwd or Path.cwd()).resolve()
    raw = match.group("path").strip("\"'")
    path = Path(raw)
    if not path.is_file():
        path = root / raw
    if not path.is_file():
        return command
    try:
        start = path.parent.resolve().relative_to(root).as_posix() or "."
    except ValueError:
        start = str(path.parent)
    rewritten = f'{match.group("pre")}discover -s "{start}" -p "{path.name}"'
    return command[: match.start()] + rewritten + command[match.end() :]


def run_command_with_watch(command: str) -> int:
    root = Path.cwd()
    memory = RepairMemory(root)
    policy = memory.get_policy()

    command = normalize_watched_command(command, root)
    print(f"\n[DevDeck Active Watch] Executing: {command} (Autonomy Policy: {policy.level.value})")
    print("=" * 65)
    env = os.environ.copy()
    py_paths = [str(root.resolve())]
    if (root / "src").is_dir():
        py_paths.append(str((root / "src").resolve()))
    if env.get("PYTHONPATH"):
        py_paths.append(env["PYTHONPATH"])
    env["PYTHONPATH"] = os.pathsep.join(py_paths)

    env["PYTHONUNBUFFERED"] = "1"

    process = subprocess.Popen(
        command,
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        text=True,
        env=env,
        bufsize=1,
    )

    captured: list[str] = []
    assert process.stdout is not None
    for line in process.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        captured.append(line)
    process.wait()
    combined_output = "".join(captured)

    if process.returncode == 0:
        print("=" * 65)
        print("✅ [DevDeck] Command exited 0 — no crash, so nothing was sent to the phone.")
        return 0

    if process.returncode != 0:
        print("=" * 65)
        print(f"❌ [DevDeck] Command failed (Exit Code: {process.returncode}). Analyzing incident...")
        payload = build_incident_payload(command, combined_output, root)

        print(f"\n● Failure captured")
        file_label = payload.get("error_file") or "unknown"
        line_label = payload.get("error_line") if payload.get("error_line") is not None else "unknown"
        print(f"  {command} failed at {file_label}:{line_label}")

        if is_cli_invocation_error(command):
            payload["error_file"] = payload.get("error_file") or "unknown"
            payload["error_line"] = payload.get("error_line") or 0
            payload["validation_error"] = True
            payload["validation_message"] = (
                "The watched command started with an extra `run` keyword. "
                "Use: python devdeck.py run python -m unittest tests/unit/test_receipts.py"
            )
            print(f"  {payload['validation_message']}")
        elif not payload.get("error_file"):
            payload["error_file"] = "unknown"
            payload["error_line"] = payload.get("error_line") or 0

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
        if payload.get("validation_error"):
            events.append(make_event(
                payload["incident_id"],
                "crash_detected",
                "failed",
                payload["validation_message"],
                detail=payload.get("error_text"),
            ))
            asyncio.run(send_events(events))
        else:
            wait_s = float(os.environ.get("DEVDECK_WAIT_SECONDS", "600"))
            try:
                outcome = asyncio.run(dispatch_and_wait(events, payload["incident_id"], wait_s))
            except KeyboardInterrupt:
                print("\n● Detached. The phone can still finish this repair.")
                outcome = "detached"
            if outcome == "success":
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
                    status="SOLVED",
                )
                return 0

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


def install_shell_hook() -> None:
    hook_dir = Path(__file__).resolve().parent
    ps_hook_file = hook_dir / "devdeck-hook.ps1"
    sh_hook_file = hook_dir / "devdeck-hook.sh"

    marker_start = "# >>> DevDeck Universal Hook >>>"
    marker_end = "# <<< DevDeck Universal Hook <<<"

    installed = []

    # 1. PowerShell Profile
    try:
        res = subprocess.run(
            ["powershell", "-NoProfile", "-Command", "echo $PROFILE"],
            capture_output=True, text=True, timeout=5
        )
        profile_path_str = res.stdout.strip()
        if profile_path_str:
            profile_path = Path(profile_path_str)
            profile_path.parent.mkdir(parents=True, exist_ok=True)
            existing = profile_path.read_text(encoding="utf-8", errors="replace") if profile_path.exists() else ""
            if marker_start not in existing:
                hook_code = ps_hook_file.read_text(encoding="utf-8") if ps_hook_file.exists() else ""
                with open(profile_path, "a", encoding="utf-8") as f:
                    f.write(f"\n{marker_start}\n{hook_code}\n{marker_end}\n")
                installed.append(f"PowerShell ($PROFILE -> {profile_path})")
            else:
                installed.append(f"PowerShell (already installed in {profile_path})")
    except Exception as e:
        print(f"[Hook Warning] Could not install to PowerShell: {e}")

    # 2. Bash/Zsh Profile
    for rc_name in [".bashrc", ".zshrc"]:
        rc_path = Path.home() / rc_name
        if rc_path.exists():
            try:
                existing = rc_path.read_text(encoding="utf-8", errors="replace")
                if marker_start not in existing:
                    with open(rc_path, "a", encoding="utf-8") as f:
                        f.write(f"\n{marker_start}\n[ -f \"{sh_hook_file.as_posix()}\" ] && source \"{sh_hook_file.as_posix()}\"\n{marker_end}\n")
                    installed.append(f"{rc_name} ({rc_path})")
            except Exception:
                pass

    print("=" * 65)
    print("✨ DevDeck Universal Terminal Hook Installed!")
    print("=" * 65)
    for item in installed:
        print(f"  ✓ {item}")
    print("\n💡 Any command error in PowerShell, VS Code, or Antigravity terminals")
    print("   will now automatically transmit to your paired DevDeck device!")
    print("=" * 65)


def uninstall_shell_hook() -> None:
    marker_start = "# >>> DevDeck Universal Hook >>>"
    marker_end = "# <<< DevDeck Universal Hook <<<"
    pattern = re.compile(rf"{re.escape(marker_start)}.*?{re.escape(marker_end)}\n?", re.DOTALL)

    uninstalled = []

    # 1. PowerShell Profile
    try:
        res = subprocess.run(
            ["powershell", "-NoProfile", "-Command", "echo $PROFILE"],
            capture_output=True, text=True, timeout=5
        )
        profile_path_str = res.stdout.strip()
        if profile_path_str:
            profile_path = Path(profile_path_str)
            if profile_path.exists():
                content = profile_path.read_text(encoding="utf-8", errors="replace")
                if marker_start in content:
                    clean = pattern.sub("", content)
                    profile_path.write_text(clean, encoding="utf-8")
                    uninstalled.append(f"PowerShell ($PROFILE -> {profile_path})")
    except Exception as e:
        print(f"[Hook Warning] Could not uninstall from PowerShell: {e}")

    # 2. Bash/Zsh Profile
    for rc_name in [".bashrc", ".zshrc"]:
        rc_path = Path.home() / rc_name
        if rc_path.exists():
            try:
                content = rc_path.read_text(encoding="utf-8", errors="replace")
                if marker_start in content:
                    clean = pattern.sub("", content)
                    rc_path.write_text(clean, encoding="utf-8")
                    uninstalled.append(f"{rc_name}")
            except Exception:
                pass

    print("=" * 65)
    print("🗑️ DevDeck Universal Hook Uninstalled.")
    for item in uninstalled:
        print(f"  ✓ Removed from {item}")
    print("=" * 65)


def hook_status() -> None:
    marker_start = "# >>> DevDeck Universal Hook >>>"
    ps_installed = False
    profile_path = None
    try:
        res = subprocess.run(
            ["powershell", "-NoProfile", "-Command", "echo $PROFILE"],
            capture_output=True, text=True, timeout=5
        )
        profile_path_str = res.stdout.strip()
        if profile_path_str:
            profile_path = Path(profile_path_str)
            if profile_path.exists() and marker_start in profile_path.read_text(encoding="utf-8", errors="replace"):
                ps_installed = True
    except Exception:
        pass

    # Check relay status
    relay_alive = False
    try:
        import urllib.request
        with urllib.request.urlopen("http://127.0.0.1:8766/status", timeout=1) as resp:
            if resp.status == 200:
                relay_alive = True
    except Exception:
        pass

    print("=" * 65)
    print("🔍 DevDeck Shell Hook Status:")
    print(f"• PowerShell Hook: {'✅ Installed' if ps_installed else '❌ Not Installed'}")
    if profile_path:
        print(f"  File: {profile_path}")
    print(f"• Relay Ingestion Server (Port 8766): {'✅ Active / Listening' if relay_alive else '⚠️ Offline (start python relay_server.py)'}")
    print("=" * 65)


def doctor() -> None:
    print("=" * 65)
    print("DevDeck doctor")
    print("=" * 65)
    hook_status()

    adb_bin = shutil.which("adb")
    if not adb_bin:
        print("• ADB: not on PATH (USB fallback unavailable from this shell)")
    else:
        try:
            res = subprocess.run([adb_bin, "devices"], capture_output=True, text=True, timeout=8)
            lines = [ln for ln in (res.stdout or "").splitlines() if ln.strip() and not ln.startswith("List")]
            ready = [ln for ln in lines if "\tdevice" in ln or ln.endswith("device")]
            print(f"• ADB: found ({adb_bin})")
            print(f"  Devices: {len(ready)} ready")
            for ln in lines[:5]:
                print(f"    {ln}")
        except Exception as e:
            print(f"• ADB: error ({e})")

    ws_ok = False
    try:
        with socket.create_connection(("127.0.0.1", 8765), timeout=1):
            ws_ok = True
    except Exception:
        ws_ok = False
    print(f"• Wi-Fi / local WebSocket 8765: {'reachable' if ws_ok else 'not reachable (start python relay_server.py)'}")

    pairing = Path(os.environ.get("DEVDECK_STATE_DIR", ".devdeck")) / "pairing_state.json"
    print(f"• Pairing state file: {'present' if pairing.is_file() else 'missing'} ({pairing})")
    print("=" * 65)
    print("Phone pairing is confirmed in the app (Settings → Paired Laptop), not from this file alone.")
    print("=" * 65)


def cli_entry():
    if len(sys.argv) < 2:
        print("DevDeck Transparent Repair Runtime")
        print("Usage:")
        print("  devdeck pair               # ⚡ One-line QR code generator & instant pairing")
        print("  devdeck scan [path]        # Index local codebase into Knowledge Graph")
        print("  devdeck link <repo_url>    # Clone & link remote GitHub repo")
        print("  devdeck sync [path]        # Sync changes and update Knowledge Graph")
        print("  devdeck run \"<command>\"    # Watch and auto-repair command failures")
        print("  devdeck install-hook       # Auto-capture failures in shell")
        print("  devdeck uninstall-hook     # Remove shell auto-capture")
        print("  devdeck hook-status        # Check shell hook status")
        print("  devdeck doctor             # Diagnose pairing and environment")
        print("  devdeck replay <id>        # Replay incident")
        print("  devdeck policy [level]     # Get/set autonomy policy")
        sys.exit(1)

    command_type = sys.argv[1].lower()
    if command_type in ("pair", "qr", "relay", "bridge", "server"):
        from relay_server import main as run_relay
        auto_open = command_type in ("pair", "qr") or "--open" in sys.argv
        try:
            asyncio.run(run_relay(open_browser=auto_open))
        except KeyboardInterrupt:
            print("\n[Relay] Server shut down.")
        sys.exit(0)
    elif command_type == "scan":
        target = sys.argv[2] if len(sys.argv) > 2 else None
        scan_repository(target)
        sys.exit(0)
    elif command_type in ("link", "clone"):
        if len(sys.argv) < 3:
            print("Usage: devdeck link <repo_url> [target_dir]")
            sys.exit(1)
        url = sys.argv[2]
        target = sys.argv[3] if len(sys.argv) > 3 else None
        link_repository(url, target)
        sys.exit(0)
    elif command_type == "sync":
        target = sys.argv[2] if len(sys.argv) > 2 else None
        sync_repository(target)
        sys.exit(0)
    elif command_type == "install-hook":
        install_shell_hook()
        sys.exit(0)
    elif command_type == "uninstall-hook":
        uninstall_shell_hook()
        sys.exit(0)
    elif command_type == "hook-status":
        hook_status()
        sys.exit(0)
    elif command_type == "doctor":
        doctor()
        sys.exit(0)
    elif command_type == "run":
        command_str = parse_run_command(sys.argv[2:])
        if not command_str:
            print("Usage: devdeck run \"<command>\"")
            print("Unquoted arguments are joined, and extra 'run' keywords are ignored.")
            asyncio.run(send_event({
                "type": "error",
                "message": "Missing command after 'run'. Example: devdeck run python -m unittest",
            }))
            sys.exit(1)
        sys.exit(run_command_with_watch(command_str))
    elif command_type == "replay":
        if len(sys.argv) < 3:
            print("Usage: devdeck replay <incident_id>")
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


if __name__ == "__main__":
    cli_entry()
