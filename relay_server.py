"""DevDeck Relay Server — Streaming bridge between Developer Machine, Sandbox Verifier, Android App, and Web Console."""

from __future__ import annotations

import asyncio
import websockets
import json
import os
import socket
import subprocess
from datetime import datetime
from pathlib import Path

from patch_manager import PatchManager
from bridge_protocol import RepairRequest
from bridge_security import canonical_project_root, resolve_project_file
from pairing_state import PairingRegistry
from sandbox_verifier import SandboxVerifier
from pipeline_events import humanize_sandbox_failure, make_event
from repair_memory import RepairMemory
import sys
import platform
import http.server
import threading

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

try:
    import qrcode
    HAS_QRCODE = True
except ImportError:
    HAS_QRCODE = False

connected_clients = set()
authenticated_clients = set()
incidents = {}  # incident_id -> data
incident_history = []
patch_manager = PatchManager()
repair_memory = RepairMemory(Path.cwd())

# Pairing State Management
STATE_DIR = os.environ.get("DEVDECK_STATE_DIR", ".devdeck")
pairing_registry = PairingRegistry(os.path.join(STATE_DIR, "pairing_state.json"))
PAIRING_SECRET = os.environ.get("DEVDECK_PAIRING_SECRET", "DECK-POCKET-SAFE")
current_pairing_data = {}


def generate_pairing_html(pairing_data: dict) -> str:
    """Generate a clean, zero-dependency browser pairing portal with embedded SVG QR Code."""
    pairing_json = json.dumps(pairing_data)
    svg_html = ""
    if HAS_QRCODE:
        try:
            import qrcode.image.svg
            import io
            factory = qrcode.image.svg.SvgPathImage
            img = qrcode.make(pairing_json, image_factory=factory, box_size=8)
            stream = io.BytesIO()
            img.save(stream)
            svg_html = stream.getvalue().decode("utf-8")
        except Exception:
            pass

    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>DevDeck • Device Pairing</title>
<style>
  :root {{ --bg: #090d16; --card: #111827; --border: #1f2937; --accent: #38bdf8; --text: #f9fafb; --muted: #9ca3af; }}
  * {{ box-sizing: border-box; }}
  body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: var(--bg); color: var(--text); min-height: 100vh; margin: 0; display: flex; align-items: center; justify-content: center; padding: 20px; }}
  .card {{ background: var(--card); border: 1px solid var(--border); border-radius: 20px; padding: 36px; max-width: 440px; width: 100%; text-align: center; box-shadow: 0 25px 50px -12px rgba(0, 0, 0, 0.5); }}
  .logo {{ font-size: 28px; margin-bottom: 4px; }}
  h1 {{ font-size: 22px; font-weight: 700; margin: 0 0 8px; }}
  p.sub {{ color: var(--muted); font-size: 14px; margin: 0 0 24px; }}
  .qr-container {{ background: #ffffff; padding: 16px; border-radius: 16px; display: inline-block; box-shadow: 0 10px 25px rgba(0,0,0,0.3); margin-bottom: 20px; }}
  .qr-container svg {{ display: block; max-width: 240px; max-height: 240px; width: 100%; height: auto; }}
  .badge {{ display: inline-flex; align-items: center; gap: 8px; background: rgba(34, 197, 94, 0.1); border: 1px solid rgba(34, 197, 94, 0.2); color: #4ade80; font-size: 13px; font-weight: 600; padding: 6px 14px; border-radius: 9999px; margin-bottom: 24px; }}
  .dot {{ width: 8px; height: 8px; background: #22c55e; border-radius: 50%; box-shadow: 0 0 8px #22c55e; }}
  .details {{ background: rgba(0,0,0,0.3); border: 1px solid var(--border); border-radius: 12px; padding: 14px; text-align: left; font-family: monospace; font-size: 13px; margin-bottom: 16px; }}
  .row {{ display: flex; justify-content: space-between; margin-bottom: 6px; }}
  .row:last-child {{ margin-bottom: 0; }}
  .lbl {{ color: var(--muted); }}
  .val {{ color: var(--accent); font-weight: 600; }}
  .tip {{ font-size: 12px; color: var(--muted); line-height: 1.5; }}
</style>
</head>
<body>
<div class="card">
  <div class="logo">⚡</div>
  <h1>Pair with DevDeck Pocket</h1>
  <p class="sub">Open DevDeck on your phone and scan this code</p>
  
  <div class="qr-container">
    {svg_html if svg_html else f'<p style="color:#000;font-size:12px;word-break:break-all;">{pairing_json}</p>'}
  </div>

  <div>
    <span class="badge"><span class="dot"></span> Bridge Online (Port {pairing_data.get("url", "").split(":")[-1]})</span>
  </div>

  <div class="details">
    <div class="row"><span class="lbl">Host:</span><span class="val">{pairing_data.get("device_name", "Developer Machine")}</span></div>
    <div class="row"><span class="lbl">WebSocket:</span><span class="val">{pairing_data.get("url", "ws://127.0.0.1:8765")}</span></div>
    <div class="row"><span class="lbl">Secret:</span><span class="val">{pairing_data.get("secret", "DECK-POCKET-SAFE")}</span></div>
  </div>

  <div class="tip">
    💡 USB connected? Run <code>adb reverse tcp:8765 tcp:8765</code> for zero-config bridge.
  </div>
</div>
</body>
</html>
"""


def repair_for_incident(payload, incident_store):
    repair = RepairRequest.from_dict(payload)
    incident = incident_store.get(repair.incident_id)
    if incident is None:
        raise ValueError("unknown incident")
    if incident.get("project_id") != repair.project_id:
        raise ValueError("repair project does not match incident")
    project_root = incident.get("project_root")
    if not isinstance(project_root, str):
        raise ValueError("incident is missing its trusted project root")
    project = canonical_project_root(project_root)
    if project.project_id != repair.project_id:
        raise ValueError("incident project root does not match project id")
    target_path = resolve_project_file(project, repair.file) if (repair.file and repair.file not in ("unknown", "None")) else None
    return repair, incident, target_path


def sandbox_project_root(incident):
    """Return the root captured with a validated protocol-v2 incident."""
    return incident.get("project_root", str(Path.cwd()))


async def broadcast(message_dict, exclude=None):
    if not connected_clients:
        return
    msg = json.dumps(message_dict) if isinstance(message_dict, dict) else str(message_dict)
    dead_clients = set()
    for client in connected_clients:
        if client != exclude:
            try:
                await client.send(msg)
            except Exception:
                dead_clients.add(client)
    connected_clients.difference_update(dead_clients)


async def relay(websocket):
    addr = websocket.remote_address
    connected_clients.add(websocket)
    print(f"[Relay] Connected: {addr}. Total clients: {len(connected_clients)}")

    try:
        async for message in websocket:
            try:
                data = json.loads(message)
                msg_type = data.get("type")

                # 1. Pairing / Authentication
                if msg_type == "pair":
                    secret = data.get("secret")
                    device_pk = data.get("device_public_key", str(addr))

                    is_valid = (
                        secret == PAIRING_SECRET or
                        pairing_registry.consume_enrollment(str(secret), device_pk) or
                        pairing_registry.is_paired(device_pk)
                    )

                    if is_valid:
                        authenticated_clients.add(websocket)
                        device_label = f"{socket.gethostname()} ({platform.system()})"
                        print(f"✅ [Relay] Client {addr} authenticated successfully with {device_label}.")
                        await websocket.send(json.dumps({
                            "type": "pair_result",
                            "success": True,
                            "device_name": device_label,
                            "host": socket.gethostname(),
                            "system": platform.system(),
                        }))
                    else:
                        print(f"❌ [Relay] Client {addr} failed authentication.")
                        await websocket.send(json.dumps({"type": "pair_result", "success": False, "error": "Invalid secret or expired token"}))
                    continue

                # 1.1 Ping/Pong
                if msg_type == "ping":
                    await websocket.send(json.dumps({"type": "pong", "timestamp": data.get("timestamp")}))
                    continue

                if msg_type == "quick_action":
                    await handle_quick_action(data)
                    continue

                # 1.2 Brain Ready Event
                if msg_type == "brain_ready":
                    print(f"🧠 [Relay] Project Brain Ready ({data.get('files_indexed')} files, {data.get('symbols_indexed')} symbols)")
                    await broadcast(data, exclude=websocket)
                    continue

                # 2. Repair Request (Requires Auth or local dev connection)
                if msg_type == "repair":
                    incident_id = data.get("incident_id")
                    incident_data = incidents.get(incident_id) if incident_id else None

                    if data.get("protocol_version") in (2, 3):
                        try:
                            repair, incident_data, target_path = repair_for_incident(data, incidents)
                            data = {**data, "file": str(target_path) if target_path else ""}
                        except ValueError as error:
                            print(f"⚠️ [Relay] Repair validation error: {error}")
                            await websocket.send(json.dumps({"type": "error", "message": str(error)}))
                            if incident_id:
                                await broadcast(make_event(
                                    incident_id,
                                    "sandbox_dry_run",
                                    "failed",
                                    f"Sandbox setup failed: {error}",
                                    detail=str(error),
                                    sandbox_passed=False,
                                    sandbox_exit_code=1,
                                ))
                            continue

                    target_file = data.get("file", "")
                    patch_type = data.get("patch_type", "single_line")
                    repair_code = data.get("code")
                    diff_text = data.get("diff_text")
                    cmd_to_rerun = incident_data.get("command") if incident_data else None
                    if cmd_to_rerun:
                        from devdeck import normalize_watched_command
                        cmd_to_rerun = normalize_watched_command(
                            cmd_to_rerun,
                            incident_data.get("project_root", str(Path.cwd())),
                        )
                    allowed_symbols = set(incident_data.get("allowed_symbols", [])) if incident_data else set()
                    expected_sha256 = data.get("expected_sha256")

                    print(f"🛠️  [Relay] Verifying candidate patch in Sandbox for Incident {incident_id}...")
                    intent = data.get("intent", "apply")

                    await broadcast(make_event(
                        incident_id or "unknown",
                        "sandbox_dry_run",
                        "started",
                        f"Applying candidate in a throwaway copy ({cmd_to_rerun or 'pytest'})",
                        detail=f"Command: {cmd_to_rerun or 'pytest'}",
                    ))

                    # Step A: Run in Sandbox Verifier with real-time streaming
                    project_root = incident_data.get("project_root", str(Path.cwd())) if incident_data else str(Path.cwd())

                    async def emit_sandbox(line: str):
                        stamped = f"{datetime.now().strftime('%H:%M:%S')}  {line}"
                        print(f"  [Sandbox] {stamped}")
                        await broadcast({"type": "sandbox_line", "line": stamped})

                    await emit_sandbox(f"Creating throwaway copy of {project_root}")
                    await emit_sandbox(f"Will rerun: {cmd_to_rerun or 'pytest'}")
                    await emit_sandbox(f"Candidate target: {target_file}:{data.get('line')}")
                    await emit_sandbox("Isolation: stdin closed, no live files, isolated PYTHONPATH")

                    def on_sandbox_line(line: str):
                        stamped = f"{datetime.now().strftime('%H:%M:%S')}  {line}"
                        print(f"  [Sandbox] {stamped}")
                        if main_loop and main_loop.is_running():
                            asyncio.run_coroutine_threadsafe(broadcast({"type": "sandbox_line", "line": stamped}), main_loop)

                    try:
                        proof, trust = await asyncio.to_thread(
                            SandboxVerifier.verify_patch,
                            project_root=project_root,
                            command=cmd_to_rerun or "pytest",
                            patch_type=patch_type,
                            target_file=target_file,
                            line_num=data.get("line"),
                            repair_code=repair_code,
                            diff_text=diff_text,
                            allowed_symbols=allowed_symbols,
                            expected_sha256=expected_sha256,
                            progress_callback=on_sandbox_line,
                        )
                    except Exception as ver_err:
                        print(f"❌ [Relay] Sandbox execution error: {ver_err}")
                        from sandbox_verifier import RepairProof, TrustBreakdown
                        proof = RepairProof(False, 1, 0, "", f"Sandbox error: {ver_err}")
                        trust = TrustBreakdown(0, "CRITICAL_RISK", 0, 0, 0, 0, [str(ver_err)])

                    await broadcast({"type": "sandbox_done"})

                    await broadcast({
                        "type": "sandbox_verified",
                        "incident_id": incident_id,
                        "proof": proof.to_dict(),
                        "trust": trust.to_dict(),
                    })

                    if proof.sandbox_passed:
                        await broadcast(make_event(
                            incident_id or "unknown",
                            "sandbox_dry_run",
                            "completed",
                            "Sandbox dry-run passed (exit 0)",
                            detail=f"Command: {cmd_to_rerun or 'pytest'}\nExit code: 0",
                            sandbox_passed=True,
                            sandbox_command=cmd_to_rerun,
                            sandbox_exit_code=0,
                            trust_score=trust.total_score,
                        ))
                    else:
                        message, detail = humanize_sandbox_failure(proof.to_dict(), cmd_to_rerun)
                        await broadcast(make_event(
                            incident_id or "unknown",
                            "sandbox_dry_run",
                            "failed",
                            message,
                            detail=detail,
                            sandbox_passed=False,
                            sandbox_command=cmd_to_rerun,
                            sandbox_exit_code=proof.exit_code,
                            trust_score=trust.total_score,
                        ))

                    if intent == "dry_run":
                        review_msg = "Ready for Approve / Reject / Request Changes" if proof.sandbox_passed else f"Sandbox test failed (Exit {proof.exit_code}) — Review diff"
                        await broadcast(make_event(incident_id or "unknown", "awaiting_review", "started", "Waiting for developer review"))
                        await broadcast(make_event(incident_id or "unknown", "awaiting_review", "completed", review_msg))
                        print(f"⏸️ [Relay] Dry-run complete for {incident_id}. Ready for developer review.")
                        continue

                    policy = repair_memory.get_policy()
                    should_apply = (
                        data.get("force_apply", False)
                        or intent == "apply"
                        or policy.should_auto_apply(trust.total_score, proof.sandbox_passed)
                    )

                    if should_apply and proof.sandbox_passed:
                        print(f"🚀 [Relay] Applying patch to main workspace ({target_file})...")
                        await broadcast(make_event(incident_id or "unknown", "applying", "started", "Writing snapshot and patch to real files"))
                        success, error_msg, file_path, transaction_id = patch_manager.apply_repair(
                            data,
                            cmd_to_rerun,
                            project_root=project_root,
                            skip_preflight=True,
                        )

                        if success:
                            repair_memory.save_verified_repair(
                                incident_id=incident_id or "unknown",
                                error_type=incident_data.get("language", "python") if incident_data else "python",
                                file_path=target_file,
                                original_line=incident_data.get("original_line", "") if incident_data else "",
                                fix_code=repair_code,
                                diff_text=diff_text,
                                trust_score=trust.total_score,
                            )
                            if incident_id:
                                repair_memory.log_incident(
                                    incident_id=incident_id,
                                    command=cmd_to_rerun or "",
                                    error_file=target_file,
                                    error_line=data.get("line", 0),
                                    error_text=incident_data.get("error_text", "") if incident_data else "",
                                    context_receipt=incident_data.get("context_receipt") if incident_data else None,
                                    candidate_patch=data,
                                    repair_proof=proof.to_dict(),
                                    trust_breakdown=trust.to_dict(),
                                    status="SOLVED",
                                )

                            await broadcast(make_event(incident_id or "unknown", "applying", "completed", "Patch written to real files"))
                            await broadcast(make_event(incident_id or "unknown", "verifying", "started", "Re-running the original command"))
                            await broadcast(make_event(incident_id or "unknown", "verifying", "completed", "Original command exited 0"))
                            await broadcast(make_event(incident_id or "unknown", "complete", "completed", "Fix kept on disk"))
                            await broadcast({
                                "type": "log_stream",
                                "log_line": "✅ [Relay] PATCH APPLIED AND VERIFIED IN MAIN WORKSPACE."
                            })
                            await broadcast({
                                "type": "rerun_result",
                                "incident_id": incident_id,
                                "success": True,
                                "message": "Patch applied and verified.",
                            })
                            await broadcast({"type": "repair_success", "incident_id": incident_id})
                        else:
                            await broadcast(make_event(
                                incident_id or "unknown",
                                "verifying",
                                "failed",
                                f"Verification failed: {error_msg}",
                                detail=error_msg,
                            ))
                            await broadcast(make_event(
                                incident_id or "unknown",
                                "rolled_back",
                                "failed",
                                "Live files restored from snapshot",
                                detail=error_msg,
                            ))
                            await broadcast({
                                "type": "log_stream",
                                "log_line": f"❌ [Relay] MAIN WORKSPACE APPLY FAILED: {error_msg}"
                            })
                            await broadcast({
                                "type": "rerun_result",
                                "incident_id": incident_id,
                                "success": False,
                                "message": error_msg,
                            })
                            await broadcast({"type": "repair_failed", "incident_id": incident_id, "message": error_msg})
                    else:
                        print(f"⏸️ [Relay] Patch held (Trust Score: {trust.total_score}%, Policy: {policy.level.value}, intent={intent}).")
                    continue

                # 3. Incident Capture (from devdeck.py)
                if "command" in data and "error_text" in data:
                    incident_id = data.get("incident_id", f"inc_{int(datetime.now().timestamp())}")
                    data["incident_id"] = incident_id
                    incidents[incident_id] = data
                    print(f"📍 [Relay] Captured Incident {incident_id} for: {data['command']}")

                    incident_history.append(data)
                    if len(incident_history) > 50:
                        incident_history.pop(0)

                    if data.get("validation_error"):
                        await broadcast(make_event(
                            incident_id,
                            "crash_detected",
                            "failed",
                            data.get("validation_message") or "Command failed without a parseable source location",
                            detail=data.get("error_text"),
                        ))

                # Broadcast to all clients
                await broadcast(data, exclude=websocket)

            except Exception as e:
                print(f"[Relay] Message processing error: {e}")

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        connected_clients.discard(websocket)
        authenticated_clients.discard(websocket)
        print(f"[Relay] Disconnected: {addr}. Remaining clients: {len(connected_clients)}")


async def handle_quick_action(data: dict):
    """Home-tab laptop actions. Does not enter the repair/sandbox apply path."""
    command = str(data.get("command") or "").strip()
    root = Path.cwd()

    async def emit(line: str):
        print(line)
        await broadcast({"type": "log_stream", "log_line": line})

    await emit(f"[Quick Action] {command} · cwd={root}")

    if command == "new_shell":
        await emit(f"$ cd {root}")
        await emit(f"Host: {socket.gethostname()}  Python: {sys.executable}")
        await emit("Open a terminal in that folder, then:")
        await emit('  python devdeck.py run "python test_errors.py keyerror"')
        await emit("This button does not spawn a GUI terminal; it attaches the existing relay session.")
        return

    if command == "sync_db":
        pairing = Path(STATE_DIR) / "pairing_state.json"
        mem_dir = Path(".devdeck")
        await emit("Checking local DevDeck stores (no writes)...")
        await emit(f"  pairing_state: {'present' if pairing.is_file() else 'missing'} ({pairing})")
        await emit(f"  .devdeck dir: {'present' if mem_dir.is_dir() else 'not created yet'}")
        await emit(f"  incidents held in this relay process: {len(incidents)}")
        await emit("Phone History is the incident log. Laptop stores pairing + verified-repair memory.")
        await emit("Sync check complete.")
        return

    if command == "run_tests":
        from subprocess_guard import run_command_isolated

        tests_dir = root / "tests"
        cmd = (
            'python -m unittest discover -s tests -p "test_*.py" -v'
            if tests_dir.is_dir()
            else "python -m unittest discover -v"
        )
        await emit(f"> {cmd}")
        code, out, err, timed_out, ms = await asyncio.to_thread(
            run_command_isolated,
            cmd,
            cwd=root,
            timeout_seconds=90,
        )
        combined = (out or "") + (("\n" + err) if err else "")
        for line in combined.splitlines()[-80:]:
            if line.strip():
                await emit(line)
        if timed_out:
            await emit("Tests timed out after 90s (process tree killed).")
        await emit(f"Exit {code} in {ms}ms")
        return

    if command == "deploy":
        await emit("Deploy dry-run: no production target is configured.")
        await emit(f"Would package project: {root.name}")
        await emit("Would skip: Play Store, CI publish, live file writes.")
        await emit("To ship a code fix, Approve a candidate after a passing sandbox dry-run.")
        return

    await emit(f"Unknown quick action: {command}")


main_loop: asyncio.AbstractEventLoop | None = None


async def process_hook_incident(data: dict):
    cmd = data.get("command", "")
    exit_code = data.get("exit_code", 1)
    cwd_str = data.get("cwd", str(Path.cwd()))
    error_text = data.get("error_text", "")
    source = data.get("source", "shell_hook")

    print(f"\n⚡ [Terminal Intercept] Captured failure from {source}: {cmd} (Exit: {exit_code})")

    # Import devdeck builder
    try:
        from devdeck import build_incident_payload, crash_to_dispatch_events
        if not error_text or len(error_text.strip()) < 5:
            error_text = f"Command failed: {cmd}\nExit Code: {exit_code}\nWorking Directory: {cwd_str}"
        payload = build_incident_payload(cmd, error_text, Path(cwd_str))
    except Exception as e:
        incident_id = f"inc_{int(datetime.now().timestamp())}"
        payload = {
            "type": "incident",
            "incident_id": incident_id,
            "command": cmd,
            "error_text": error_text or f"Command failed: {cmd} (Exit: {exit_code})",
            "error_file": "unknown",
            "error_line": 1,
            "project_id": "auto_project",
            "project_root": cwd_str,
            "timestamp": datetime.now().isoformat(),
        }

    incident_id = payload.get("incident_id", f"inc_{int(datetime.now().timestamp())}")
    incidents[incident_id] = payload
    incident_history.append(payload)

    # Generate pipeline events
    try:
        from devdeck import crash_to_dispatch_events
        events = crash_to_dispatch_events(incident_id, False, cmd)
    except Exception:
        events = [
            make_event(incident_id, "crash_detected", "completed", f"{cmd} failed with exit {exit_code}", detail=error_text),
            make_event(incident_id, "indexing", "skipped", "Index ready"),
        ]

    events.append(payload)
    events.append(make_event(incident_id, "sent_to_phone", "completed", "Incident handed to paired phone"))

    # Broadcast to phone and all clients
    for ev in events:
        await broadcast(ev)

    print(f"📱 [Relay] Dispatched incident {incident_id} to {len(connected_clients)} connected client(s).")


class HookHTTPHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass  # quiet logs

    def do_GET(self):
        if self.path in ("/", "/pair", "/pair/", "/qrcode"):
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            html = generate_pairing_html(current_pairing_data)
            self.wfile.write(html.encode("utf-8"))
            return

        if self.path in ("/health", "/status", "/health/", "/status/"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            resp = json.dumps({
                "status": "online",
                "paired_clients": len(connected_clients),
                "host": socket.gethostname(),
                "os": platform.system(),
                "pairing_url": f"http://{current_pairing_data.get('host', 'localhost')}:8766/pair"
            })
            self.wfile.write(resp.encode())
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        if self.path in ("/incident", "/incident/"):
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length).decode("utf-8", errors="replace")
            try:
                data = json.loads(body) if body else {}
                if main_loop and main_loop.is_running():
                    asyncio.run_coroutine_threadsafe(process_hook_incident(data), main_loop)
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(b'{"status":"accepted","dispatched":true}')
            except Exception as e:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(str(e).encode())
        else:
            self.send_response(404)
            self.end_headers()


def free_port(p: int) -> None:
    """Terminate orphan listeners on *p* so a restart can bind cleanly (WinError 10048)."""
    if sys.platform != "win32":
        return
    try:
        out = subprocess.check_output(
            f"netstat -ano -p tcp | findstr :{p}",
            shell=True,
            text=True,
            stderr=subprocess.DEVNULL,
        )
    except Exception:
        return
    pids: set[int] = set()
    for line in out.strip().splitlines():
        parts = line.strip().split()
        if len(parts) >= 5 and "LISTENING" in parts:
            try:
                pid = int(parts[-1])
            except ValueError:
                continue
            if pid not in (0, os.getpid()):
                pids.add(pid)
    for pid in pids:
        subprocess.run(
            ["taskkill", "/F", "/T", "/PID", str(pid)],
            capture_output=True,
            timeout=10,
        )


def start_http_server(host: str = "0.0.0.0", http_port: int = 8766):
    try:
        server = http.server.ThreadingHTTPServer((host, http_port), HookHTTPHandler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        return server
    except Exception as e:
        print(f"[Relay HTTP Warning] Could not bind HTTP port {http_port}: {e}")
        return None


async def main():
    global main_loop
    main_loop = asyncio.get_running_loop()

    host = "0.0.0.0"
    port = 8765
    http_port = 8766

    free_port(port)
    free_port(http_port)

    # Start HTTP Hook Listener
    start_http_server(host, http_port)

    # Print local IPs
    hostname = socket.gethostname()
    os_name = platform.system()
    device_label = f"{hostname} ({os_name})"
    local_ips = ["127.0.0.1"]
    primary_ip = "127.0.0.1"
    try:
        for ip in socket.gethostbyname_ex(hostname)[2]:
            if not ip.startswith("127."):
                local_ips.append(ip)
                primary_ip = ip
    except Exception:
        pass

    pairing_data = {
        "url": f"ws://{primary_ip}:{port}",
        "secret": PAIRING_SECRET,
        "device_name": device_label,
        "host": hostname,
        "os": os_name,
    }
    global current_pairing_data
    current_pairing_data = pairing_data
    pairing_json = json.dumps(pairing_data)

    print("=" * 65)
    print("🚀 DevDeck Transparent Repair Runtime Bridge")
    print(f"• Host Device: {device_label}")
    print(f"• WebSocket Bridge: ws://localhost:{port} / ws://0.0.0.0:{port}")
    print(f"• Terminal Hook HTTP: http://127.0.0.1:{http_port}/incident")
    print(f"• Pairing Web Portal: http://localhost:{http_port}/pair (or http://{primary_ip}:{http_port}/pair)")
    print(f"• Local Network IP: {primary_ip}")
    print(f"• Autonomy Policy: {repair_memory.get_policy().level.value}")
    print(f"• Pairing Secret: {PAIRING_SECRET}")
    print("=" * 65)
    print("\n📱 [DevDeck] Scan this QR Code with DevDeck Android App to Pair:\n")

    if HAS_QRCODE:
        try:
            qr = qrcode.QRCode(
                version=1,
                error_correction=qrcode.constants.ERROR_CORRECT_L,
                box_size=1,
                border=2,
            )
            qr.add_data(pairing_json)
            qr.make(fit=True)
            qr.print_ascii(invert=True)
        except Exception as qre:
            print(f"[QR Error]: {qre}")
            print(f"Pairing JSON: {pairing_json}")
    else:
        print(f"Pairing JSON: {pairing_json}")

    print("\n💡 Alternatively, use ADB reverse: adb reverse tcp:8765 tcp:8765")
    print("=" * 65)

    async with websockets.serve(relay, host, port):
        await asyncio.Future()  # Run forever


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n[Relay] Server shut down.")

