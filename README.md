# DevDeck Pocket

**An On-Device AI Local Debugging Co-Pilot, Knowledge Graph & Autonomous Code Repair Engine**

DevDeck Pocket intercepts failed developer commands (tests, builds, scripts), transfers the error trace and AST source context over a local WebSocket bridge to an Android device (e.g. running MediaPipe Gemma-2B on NPU), diagnoses the root cause entirely on-device, synthesizes a verified single-line fix, and pushes the patch back to the host machine through a dual-gate sandbox to automatically repair and rerun the code.

---

## ⚡ 1-Line Universal Installer (Zero-Repo Windows Setup)

Any developer can install and launch the DevDeck native pairing bridge in 10 seconds by running this in PowerShell:

```powershell
irm https://raw.githubusercontent.com/dilshadalikhan2004/DevDeck/main/install.ps1 | iex
```

Or install via `pip` globally:
```bash
pip install "git+https://github.com/dilshadalikhan2004/DevDeck.git"
```

---

## 📸 App Screenshots

<p align="center">
  <img src="screenshots/WhatsApp Image 2026-08-22 at 9.43.04 PM.jpeg" width="30%" />
  <img src="screenshots/WhatsApp Image 2026-08-22 at 9.43.05 PM.jpeg" width="30%" />
  <img src="screenshots/WhatsApp Image 2026-08-22 at 9.43.06 PM.jpeg" width="30%" />
</p>
<p align="center">
  <img src="screenshots/WhatsApp Image 2026-08-22 at 9.43.08 PM.jpeg" width="30%" />
  <img src="screenshots/WhatsApp Image 2026-08-22 at 9.43.09 PM (1).jpeg" width="30%" />
  <img src="screenshots/WhatsApp Image 2026-08-22 at 9.43.09 PM.jpeg" width="30%" />
</p>

---

## 🏗️ Architecture Overview

```
                               ┌────────────────────────────────────────┐
                               │       Developer Laptop / Terminal      │
                               └────────────────────────────────────────┘
                                 │                                    │
               devdeck run / terminal hook                 devdeck scan / devdeck link
                                 │                                    │
                                 ▼                                    ▼
                      ┌──────────────────────┐             ┌─────────────────────┐
                      │ Crash Trace Capture  │             │ AST Knowledge Graph │
                      │ & Subprocess Guard   │             │ (Symbols/Call Graph)│
                      └──────────────────────┘             └─────────────────────┘
                                 │                                    │
                                 └───────────────┬────────────────────┘
                                                 ▼
                                     ┌───────────────────────┐
                                     │   DevDeck Relay Host  │  (Port 8765 / 8766)
                                     │  (USB ADB / Wi-Fi WS) │
                                     └───────────────────────┘
                                                 │
                                                 │ Encrypted WebSocket
                                                 ▼
                                     ┌───────────────────────┐
                                     │ Android DevDeck Phone │
                                     │ • Gemma-2B On-Device  │
                                     │ • Token Grounding     │
                                     │ • Heuristic Engine    │
                                     │ • Incident Memory     │
                                     └───────────────────────┘
                                                 │
                                                 │ Synthesized Patch Payload
                                                 ▼
                                     ┌───────────────────────┐
                                     │   Two-Gate Sandbox    │
                                     │   1. Isolated Temp Dir│
                                     │   2. Working Copy Bak │
                                     └───────────────────────┘
                                                 │
                                       ┌─────────┴─────────┐
                                       ▼                   ▼
                                 [ SUCCESS ✅ ]       [ FAILURE ❌ ]
                                 Applied & Rerun      Git Auto-Rollback
```

---

## ⚡ Key CLI Commands

| Command | Description |
| :--- | :--- |
| `devdeck pair` | ⚡ Starts pairing bridge, prints ASCII QR, and opens live browser portal |
| `devdeck scan [path]` | 🧠 Indexes local codebase symbols and dependencies into Knowledge Graph |
| `devdeck link <repo_url>` | 🌐 Shallow-clones remote GitHub repo and syncs graph to phone |
| `devdeck sync [path]` | 🔄 Pulls git delta changes and updates Knowledge Graph |
| `devdeck run "<command>"` | 🛡️ Intercepts crashes, streams trace to phone, and auto-repairs |
| `devdeck install-hook` | 🔌 Installs background shell hook for automatic failure capture |
| `devdeck doctor` | 🩺 Diagnoses network connectivity, ADB reverse, and pairing state |

---

## 🔒 Security & Sandboxing

1. **100% Local & Offline**: All AI inference runs strictly on the phone's NPU/GPU using MediaPipe GenAI. Code never leaves your local network.
2. **Two-Gate Repair Verification**: Before applying any patch, DevDeck tests candidate diffs in an isolated temporary directory sandbox. Only passing candidates reach the working directory.
3. **Transactional Git Rollback**: The host engine creates backup snapshots and automatically rolls back if tests or syntax checks fail after patching.
4. **Token Grounding Guardrail**: Strictly verifies that output tokens belong to the grounded source context, eliminating hallucinations.

---

## 🚀 Development & Testing

```bash
# Run unit test suite (45+ tests)
python -m unittest discover -s tests -p "test_*.py"

# Build standalone Windows GUI executable
pyinstaller --onefile --windowed --name "DevDeck" devdeck_gui.py

# Launch Android unit tests
./gradlew testDebugUnitTest
```

