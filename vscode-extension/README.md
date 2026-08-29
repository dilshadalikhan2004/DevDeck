# DevDeck Pocket — VS Code Extension

Autonomous on-device AI debugging co-pilot & code repair bridge for Visual Studio Code.

## ⚡ Features

- **Live Status Bar Badge**: Displays real-time connection status with the paired mobile diagnostic device (`⚡ DevDeck: Paired` / `DevDeck: Offline`).
- **Interactive Patch Notification**: When the mobile AI synthesizes a verified fix, receive an immediate popup with `[Apply Fix]`, `[View Diff]`, or automatic instant patching.
- **One-Click Active File Audit**: Send any file/function directly from your editor to your phone's NPU for on-device analysis.
- **Direct Terminal Integration**: Automatically captures terminal exit errors and forwards the context to your mobile co-pilot.

## 🚀 Quickstart

1. Install dependencies in this folder: `npm install`
2. Start the relay bridge: `python relay_server.py`
3. Launch the extension in VS Code (`F5` or install `.vsix`).
4. Click the status bar item or run command: `DevDeck: Connect to Relay Server`.
