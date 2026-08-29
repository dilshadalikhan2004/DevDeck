import os
import asyncio
import websockets
import json
import time
import subprocess
import hashlib

RELAY_URI = "ws://localhost:8765"
PAIRING_SECRET = "DECK-POCKET-SAFE"

TEST_CASES = [
    {
        "name": "TypeError: Concatenation",
        "file": "test_type_error.py",
        "content": "def run(x):\n    print('Value: ' + x)\n\nrun(None)",
        "fix_type": "single_line",
        "line": 2,
        "fix_code": "print('Value: ' + str(x))"
    },
    {
        "name": "AttributeError: NoneType",
        "file": "test_attr_error.py",
        "content": "class User:\n    def __init__(self, name): self.name = name\n\ndef greet(user):\n    print(user.name)\n\ngreet(None)",
        "fix_type": "single_line",
        "line": 5,
        "fix_code": "if user: print(user.name)"
    }
]

async def run_test(case):
    print(f"\n🚀 Running Test Case: {case['name']}")

    # 1. Setup file
    file_path = os.path.abspath(case['file'])
    with open(file_path, 'w') as f:
        f.write(case['content'])

    sha256 = hashlib.sha256(case['content'].encode('utf-8')).hexdigest()

    # 2. Start relay server if not running (assumed running for this script)

    # 3. Connect as "Authorized App"
    async with websockets.connect(RELAY_URI) as ws:
        # Authenticate
        await ws.send(json.dumps({"type": "pair", "secret": PAIRING_SECRET}))
        resp = await ws.recv()
        auth_data = json.loads(resp)
        if not auth_data.get("success"):
            print(f"❌ Auth failed: {auth_data}")
            return

        print("✅ Authenticated with Relay.")

        # 4. Run command via devdeck.py in background
        # Note: In a real test, devdeck.py would send the incident.
        # Here we manually send the repair to test the hardening.

        repair_payload = {
            "type": "repair",
            "patch_type": case["fix_type"],
            "file": file_path,
            "expected_sha256": sha256,
            "incident_id": "test_bench",
        }
        if case["fix_type"] == "single_line":
            repair_payload["line"] = case["line"]
            repair_payload["code"] = case["fix_code"]

        print(f"🛠️  Sending Repair Payload for {case['file']}...")
        await ws.send(json.dumps(repair_payload))

        # Wait for log stream confirmation
        start_time = time.time()
        while time.time() - start_time < 5:
            try:
                msg = await asyncio.wait_for(ws.recv(), timeout=2)
                data = json.loads(msg)
                if data.get("type") == "log_stream":
                    print(f"📡 [Relay Log] {data.get('log_line')}")
                    if "PATCH APPLIED" in data.get("log_line"):
                        break
            except:
                continue

    # 5. Verify file content and execution
    with open(file_path, 'r') as f:
        new_content = f.read()

    print(f"📝 New Content:\n---\n{new_content}\n---")

    # Try running the fixed code
    result = subprocess.run(['python', file_path], capture_output=True, text=True)
    if result.returncode == 0:
        print(f"✅ TEST PASSED: {case['name']} fixed and verified.")
    else:
        print(f"❌ TEST FAILED: {case['name']} still failing.\n{result.stderr}")

    # Cleanup
    if os.path.exists(case['file']): os.remove(case['file'])
    if os.path.exists(case['file'] + ".bak"): os.remove(case['file'] + ".bak")

async def main():
    for case in TEST_CASES:
        await run_test(case)

if __name__ == "__main__":
    asyncio.run(main())
