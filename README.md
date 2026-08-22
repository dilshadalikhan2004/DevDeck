# DevDeck Pocket

**An On-Device AI Local Debugging Co-Pilot & Autonomous Code Repair Engine**

DevDeck Pocket intercepts failed developer commands (tests, builds, scripts), transfers the error trace and source context over a local WebSocket bridge to an Android device (e.g. iQOO running MediaPipe Gemma-2B), diagnoses the root cause entirely on-device, synthesizes a verified single-line fix, and pushes the patch back to the host machine to automatically repair and rerun the code.

---

## 🏗️ Architecture Overview

```
[ Developer Terminal ] ──(Runs command)──> [ devdeck.py Active Watch ]
                                                    │ (On Failure)
                                                    ▼
                                           [ relay_server.py ] (ws://localhost:8765)
                                             │          │
                     ┌───────────────────────┘          └────────────────────────┐
                     ▼                                                           ▼
       [ Android Device (DevDeck) ]                                [ Web Command Center ]
       • Local Gemma-2B NPU Inference                              • Live Incident Stream
       • Token Grounding Guardrail                                 • Real-Time Telemetry (TPS/RAM)
       • Heuristic Safety Synthesizer                              • Manual/Automated Trigger
                     │
                     ▼ (Verified Patch Payload)
       [ relay_server.py ] ──> Creates .bak ──> Writes Patch ──> Verifies Disk ──> Reruns Command
                                                                                       │
                                                                   ┌───────────────────┴──────────────┐
                                                                   ▼                                  ▼
                                                            [ EXIT CODE 0 ]                    [ FAIL / TIMEOUT ]
                                                            ✅ Success Broadcast              📦 Auto Rollback .bak
```

---

## ⚡ Key Features

1. **🔒 100% Local & Private**: All AI inference runs on the phone's NPU/GPU using MediaPipe GenAI. No proprietary code or trace logs leave the local network.
2. **🛡️ Semantic Grounding Guardrails**: Strictly validates AI token output; rejects hallucinated variable names and handles language keywords & f-strings without false positives.
3. **🔄 Transactional Patch & Rollback Engine**: The relay server automatically creates `.bak` copies, verifies line count integrity, reruns failed commands, and immediately rolls back if the fix is incorrect.
4. **📊 Dual-Tier Intelligence**: High-accuracy few-shot prompt for on-device Gemma-2B + deterministic `HeuristicDiagnosticEngine` covering `TypeError`, `AttributeError`, `KeyError`, `ZeroDivisionError`, `NPE`, and `IndexError`.
5. **💻 Web Command Center**: Live web dashboard in `office-kit-dashboard/index.html` displaying real-time telemetry, incident replays, and audit trails.

---

## 🚀 Quickstart & Demo Flow

### 1. Setup Host Relay & Dependencies
```bash
pip install websockets
python relay_server.py
```

### 2. Connect Your Device (USB or Wi-Fi)
- **USB with ADB (Recommended)**:
  ```bash
  adb reverse tcp:8765 tcp:8765
  ```
  *(Retain `ws://localhost:8765` in the Android app)*
- **Wi-Fi**: Connect phone to the same network and enter `ws://<YOUR_LAPTOP_IP>:8765` in app settings.

### 3. Open Web Dashboard (Optional)
Open [`office-kit-dashboard/index.html`](file:///c:/Users/LENOVO/Downloads/receipts-android/office-kit-dashboard/index.html) in your browser for real-time telemetry and logs.

### 4. Run Automated Repair Verification
```bash
python verify_repair.py
```
*Watch `target_bug.py` get caught, diagnosed on your phone, patched autonomously, and rerun to exit code 0.*

### 5. Run Arbitrary Commands Through DevDeck
```bash
python devdeck.py run "pytest"
python devdeck.py run "python test_errors.py typeerror"
python devdeck.py run "python test_errors.py attributeerror"
python devdeck.py demo
```
