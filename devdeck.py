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

async def send_error(error_data):
    uri = os.environ.get("DEVDECK_RELAY_URI", "ws://localhost:8765")
    try:
        async with websockets.connect(uri) as websocket:
            await websocket.send(json.dumps(error_data))
            print(f"\n[DevDeck] -> Error incident + source context dispatched to paired device ({uri}).")
    except Exception as e:
        print(f"\n[DevDeck] Warning: Could not connect to relay server ({uri}): {e}")

def calculate_sha256(file_path):
    if not file_path or not os.path.exists(file_path):
        return None
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()

def detect_language(file_path):
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
        ".php": "php"
    }
    return mapping.get(ext, "auto")

def get_error_metadata(stderr):
    # Regex patterns for Python, JS/TS, Java/Kotlin, Rust, Go, C/C++
    patterns = [
        # Python traceback: File "path", line 123
        r'File "(.*?)", line (\d+)',
        # JS / TS / Node stack: at ... (path:123:45) or at path:123:45
        r'at (?:[^\(\n]+\()?([a-zA-Z0-9_\-\./\\]+\.(?:js|ts|jsx|tsx|mjs)):(\d+)',
        # Kotlin / Java compile: e: path: (123, 45) or path:123:45: error
        r'([a-zA-Z0-9_\-\./\\]+\.(?:kt|java|cpp|c|rs|go)):(?:(?:\()?(\d+)(?:,\s*\d+\))?|(\d+):\d+)',
        # Java runtime stack: at pkg.Class(Class.java:123)
        r'\(([a-zA-Z0-9_\-]+\.(?:java|kt)):(\d+)\)',
        # Rust panic: --> src/main.rs:123:45 or panicked at '...', src/main.rs:123:45
        r'(?:-->|panicked at .*?,\s*)([a-zA-Z0-9_\-\./\\]+\.rs):(\d+)',
        # Generic path:line
        r'([a-zA-Z0-9_\-\./\\]+\.(?:py|js|ts|kt|java|cpp|c|rs|go|rb|php)):(\d+)'
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
                    # Find first numeric group as line
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

def clean_stderr(stderr):
    ansi_escape = re.compile(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])')
    clean_text = ansi_escape.sub('', stderr)
    lines = clean_text.splitlines()
    if len(lines) > 50:
        lines = lines[-50:]
    return "\n".join(lines)


def build_incident_payload(command, stderr, project_root=None):
    source_context, file_path, line_num, original_line = get_error_metadata(stderr)
    project = canonical_project_root(project_root or Path.cwd())
    source_path = Path(file_path).resolve() if file_path and Path(file_path).exists() else None
    relative_file = None
    expected_sha256 = None
    if source_path and source_path.is_file() and project.path in source_path.parents:
        relative_file = source_path.relative_to(project.path).as_posix()
        expected_sha256 = sha256_file(source_path)

    return {
        "type": "incident",
        "protocol_version": 2,
        "incident_id": str(uuid.uuid4()),
        "project_id": project.project_id,
        "timestamp": datetime.now().isoformat(),
        "command": command,
        "error_text": clean_stderr(stderr),
        "source_context": source_context,
        "error_file": relative_file,
        "error_line": line_num,
        "original_line": original_line,
        "language": detect_language(relative_file),
        "expected_sha256": expected_sha256,
    }

def run_command(command):
    print(f"\n[DevDeck Active Watch] Executing: {command}")
    print("=" * 60)
    process = subprocess.Popen(
        command,
        shell=True,
        stdout=sys.stdout,
        stderr=subprocess.PIPE,
        text=True
    )

    _, stderr = process.communicate()

    if process.returncode != 0:
        print("=" * 60)
        print("❌ [DevDeck] Command failed (Exit Code: {}). Analyzing incident...".format(process.returncode))
        error_payload = build_incident_payload(command, stderr)

        print(f"📍 Target: {error_payload['error_file']}:{error_payload['error_line']} [{error_payload['language']}]")
        print(f"🆔 Incident ID: {error_payload['incident_id']}")
        if error_payload["original_line"]:
            print(f"🔍 Line Content: {error_payload['original_line']}")

        asyncio.run(send_error(error_payload))

    return process.returncode

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python devdeck.py [run \"<command>\" | demo]")
        sys.exit(1)

    if sys.argv[1] == "demo":
        print("[DevDeck] TRACE: Initializing Staged Demo Error...")
        demo_trace = """Traceback (most recent call last):
  File "auth_service.py", line 42, in get_user_token
    if user.is_authenticated():
AttributeError: 'NoneType' object has no attribute 'is_authenticated'"""

        demo_context = """       38 |     user = db.find_user(user_id)
       39 |     # Logic to fetch token
       40 |     print(f"Fetching token for {user_id}")
>>>    42 |     if user.is_authenticated():
       43 |         return user.token
       44 |     return None"""

        payload = {
            "timestamp": datetime.now().isoformat(),
            "command": "demo-staged-bug",
            "error_text": demo_trace,
            "source_context": demo_context,
            "error_file": "auth_service.py",
            "error_line": 42,
            "original_line": "if user.is_authenticated():",
            "language": "python"
        }
        asyncio.run(send_error(payload))
        sys.exit(0)

    if len(sys.argv) < 3 or sys.argv[1] != "run":
        print("Usage: python devdeck.py run \"<command>\"")
        sys.exit(1)

    cmd = sys.argv[2]
    sys.exit(run_command(cmd))
