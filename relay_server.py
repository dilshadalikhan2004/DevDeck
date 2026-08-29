import asyncio
import websockets
import json
import os
import subprocess
import shutil
from datetime import datetime
from patch_manager import PatchManager
from bridge_protocol import RepairRequest
from bridge_security import canonical_project_root, resolve_project_file

connected_clients = set()
authenticated_clients = set()
incidents = {}  # incident_id -> data
incident_history = []
patch_manager = PatchManager()

# Pairing Secret (could be randomized or fixed for MVP)
PAIRING_SECRET = "DECK-POCKET-SAFE"


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
    return repair, incident, resolve_project_file(project, repair.file)

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

                # 1. Pairing / Authentication
                if data.get("type") == "pair":
                    secret = data.get("secret")
                    if secret == PAIRING_SECRET:
                        authenticated_clients.add(websocket)
                        print(f"✅ [Relay] Client {addr} authenticated successfully.")
                        await websocket.send(json.dumps({"type": "pair_result", "success": True}))
                    else:
                        print(f"❌ [Relay] Client {addr} failed authentication.")
                        await websocket.send(json.dumps({"type": "pair_result", "success": False, "error": "Invalid secret"}))
                    continue

                # 1.1 Ping/Pong
                if data.get("type") == "ping":
                    await websocket.send(json.dumps({"type": "pong", "timestamp": data.get("timestamp")}))
                    continue

                # 2. Repair (Requires Auth)
                if data.get("type") == "repair":
                    if websocket not in authenticated_clients:
                        print(f"🚫 [Relay] Rejected unauthorized repair from {addr}")
                        await websocket.send(json.dumps({"type": "error", "message": "Unauthorized. Please pair first."}))
                        continue

                    incident_id = data.get("incident_id")
                    incident_data = incidents.get(incident_id) if incident_id else None

                    if data.get("protocol_version") == 2:
                        try:
                            repair, incident_data, target_path = repair_for_incident(data, incidents)
                        except ValueError as error:
                            await websocket.send(json.dumps({"type": "error", "message": str(error)}))
                            continue
                        data = {**data, "file": str(target_path)}

                    target_file = data.get("file", "")
                    patch_type = data.get("patch_type", "single_line")
                    print(f"🛠️  [Relay] REPAIR ({patch_type}) for Incident {incident_id}: {target_file}")

                    # Use command from incident if available
                    cmd_to_rerun = incident_data.get("command") if incident_data else None

                    success, error_msg, file_path, transaction_id = patch_manager.apply_repair(data, cmd_to_rerun)

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

                # 3. Incident Capture (from devdeck.py)
                if "command" in data and "error_text" in data:
                    # Generate an incident ID if not present
                    incident_id = data.get("incident_id", f"inc_{int(datetime.now().timestamp())}")
                    data["incident_id"] = incident_id
                    incidents[incident_id] = data

                    print(f"[Relay] Captured Incident {incident_id} for command: {data['command']}")

                    incident_history.append(data)
                    if len(incident_history) > 30:
                        incident_history.pop(0)

                # Broadcast to all other devices
                await broadcast(data, exclude=websocket)

            except Exception as e:
                print(f"[Relay] Message processing error: {e}")

    except websockets.exceptions.ConnectionClosed:
        pass
    finally:
        connected_clients.discard(websocket)
        authenticated_clients.discard(websocket)
        print(f"[Relay] Disconnected: {addr}. Remaining clients: {len(connected_clients)}")

def apply_repair_robust(data):
    file_path = data.get("file", "").replace("/", "\\")
    line_num = data.get("line")
    new_code_fragment = data.get("code", "")

    if not all([file_path, line_num, new_code_fragment]):
        return False, "Invalid repair payload", None, None

    # Reject multi-line injections
    if "\n" in new_code_fragment.strip() or "\r" in new_code_fragment.strip():
        print(f"❌ [Relay] REJECTED: Multi-line fix attempt: {repr(new_code_fragment)}")
        return False, "Multi-line fix rejected. Only single-line replacements permitted.", file_path, None

    if not os.path.exists(file_path):
        rel_path = os.path.basename(file_path)
        if os.path.exists(rel_path):
            file_path = rel_path
        else:
            return False, f"File {file_path} not found", file_path, None

    # Step 1: Backup
    backup_path = file_path + ".bak"
    shutil.copy2(file_path, backup_path)
    print(f"[Relay] Created backup at {backup_path}")

    try:
        # Step 2: Read fresh from disk
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()

        original_line_count = len(lines)

        if not (1 <= line_num <= original_line_count):
            return False, f"Line number {line_num} out of bounds (1..{original_line_count})", file_path, backup_path

        old_line_raw = lines[line_num - 1]
        print(f"[Relay] INSTRUMENTATION - RAW LINE ON DISK: {repr(old_line_raw)}")

        clean_code = new_code_fragment.strip()
        print(f"[Relay] INSTRUMENTATION - AI PROPOSED FIX: {repr(clean_code)}")

        # Preserve indentation
        indent = old_line_raw[:len(old_line_raw) - len(old_line_raw.lstrip())]
        new_full_line = f"{indent}{clean_code}\n"
        print(f"[Relay] INSTRUMENTATION - LINE TO BE WRITTEN: {repr(new_full_line)}")

        if old_line_raw == new_full_line:
            return False, "AI suggested identical code. No change made.", file_path, backup_path

        # Step 3: Write
        lines[line_num - 1] = new_full_line
        with open(file_path, 'w', encoding='utf-8') as f:
            f.writelines(lines)

        # Step 4: Verify after write
        with open(file_path, 'r', encoding='utf-8') as f:
            verified_lines = f.readlines()
            new_line_count = len(verified_lines)
            verified_line = verified_lines[line_num - 1]

        if verified_line != new_full_line or original_line_count != new_line_count:
            print("❌ [Relay] PATCH VERIFICATION FAILED! Restoring backup.")
            shutil.copy2(backup_path, file_path)
            return False, "Verification mismatch after write.", file_path, backup_path

        print(f"✅ [Relay] Repaired and Verified {file_path}:{line_num}")
        return True, None, file_path, backup_path

    except Exception as e:
        print(f"[Relay] Critical error applying repair: {e}")
        if os.path.exists(backup_path):
            shutil.copy2(backup_path, file_path)
            print("📦 [Relay] ROLLED BACK successfully after error.")
        return False, str(e), file_path, backup_path

async def main():
    host = os.environ.get("DEVDECK_RELAY_HOST", "0.0.0.0")
    port = int(os.environ.get("DEVDECK_RELAY_PORT", 8765))
    print(f"\n=======================================================")
    print(f"⚡ DevDeck Bridge Relay Server")
    print(f"   Listening on: ws://{host}:{port}")
    print(f"   Ready for Android app & host CLI connections")
    print(f"=======================================================\n")
    async with websockets.serve(relay, host, port):
        await asyncio.Future()

if __name__ == "__main__":
    asyncio.run(main())
