const vscode = require("vscode");
const WebSocket = require("ws");
const fs = require("fs");
const path = require("path");

let socket = null;
let statusBarItem = null;
let outputChannel = null;
let reconnectTimer = null;

function activate(context) {
    outputChannel = vscode.window.createOutputChannel("DevDeck Pocket");
    outputChannel.appendLine("[DevDeck] Extension activated.");

    // Status Bar Indicator
    statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    statusBarItem.command = "devdeck.connect";
    statusBarItem.text = "$(circle-slash) DevDeck: Offline";
    statusBarItem.tooltip = "Click to connect to DevDeck Relay Server";
    statusBarItem.show();
    context.subscriptions.push(statusBarItem);

    // Register Commands
    context.subscriptions.push(
        vscode.commands.registerCommand("devdeck.connect", () => connectToRelay(true)),
        vscode.commands.registerCommand("devdeck.disconnect", () => disconnectRelay()),
        vscode.commands.registerCommand("devdeck.triggerDemo", () => sendDemoError()),
        vscode.commands.registerCommand("devdeck.diagnoseActiveFile", () => diagnoseActiveFile())
    );

    // Initial connection attempt
    connectToRelay(false);

    // Listen to terminal execution end (if terminal shell integration is available)
    if (vscode.window.onDidEndTerminalShellExecution) {
        context.subscriptions.push(
            vscode.window.onDidEndTerminalShellExecution((event) => {
                if (event.exitCode !== undefined && event.exitCode !== 0) {
                    outputChannel.appendLine(`[DevDeck] Terminal command exited with code ${event.exitCode}`);
                }
            })
        );
    }
}

function connectToRelay(userInitiated = false) {
    const config = vscode.workspace.getConfiguration("devdeck");
    const uri = config.get("relayUri") || "ws://localhost:8765";

    if (socket && socket.readyState === WebSocket.OPEN) {
        if (userInitiated) {
            vscode.window.showInformationMessage(`DevDeck is already connected to ${uri}`);
        }
        return;
    }

    if (socket) {
        try { socket.terminate(); } catch (e) {}
    }

    outputChannel.appendLine(`[DevDeck] Connecting to relay at ${uri}...`);
    try {
        socket = new WebSocket(uri);

        socket.on("open", () => {
            statusBarItem.text = "$(zap) DevDeck: Paired";
            statusBarItem.color = "#00e5a3";
            statusBarItem.tooltip = `Connected to DevDeck Relay (${uri})`;
            outputChannel.appendLine("[DevDeck] Bridge connection established.");
            if (userInitiated) {
                vscode.window.showInformationMessage(`⚡ DevDeck paired successfully with ${uri}`);
            }
        });

        socket.on("message", (raw) => {
            try {
                const data = JSON.parse(raw.toString());
                handleIncomingMessage(data);
            } catch (e) {
                outputChannel.appendLine(`[DevDeck] Error parsing message: ${e.message}`);
            }
        });

        socket.on("close", () => {
            statusBarItem.text = "$(circle-slash) DevDeck: Offline";
            statusBarItem.color = "#fb7185";
            statusBarItem.tooltip = "DevDeck Relay disconnected. Click to retry.";
            outputChannel.appendLine("[DevDeck] Connection closed.");
            scheduleReconnect();
        });

        socket.on("error", (err) => {
            outputChannel.appendLine(`[DevDeck] Socket error: ${err.message}`);
        });

    } catch (err) {
        outputChannel.appendLine(`[DevDeck] Failed to create WebSocket: ${err.message}`);
        scheduleReconnect();
    }
}

function scheduleReconnect() {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(() => {
        connectToRelay(false);
    }, 5000);
}

function disconnectRelay() {
    if (reconnectTimer) clearTimeout(reconnectTimer);
    if (socket) {
        socket.close();
        socket = null;
    }
    statusBarItem.text = "$(circle-slash) DevDeck: Offline";
    statusBarItem.color = undefined;
    vscode.window.showInformationMessage("DevDeck disconnected.");
}

function handleIncomingMessage(data) {
    outputChannel.appendLine(`[DevDeck] Inbound packet: ${JSON.stringify(data).slice(0, 120)}...`);

    if (data.type === "log_stream") {
        outputChannel.appendLine(`[Relay] ${data.log_line}`);
    } else if (data.type === "repair") {
        handleRepairOffer(data);
    }
}

async function handleRepairOffer(data) {
    const filePath = data.file;
    const lineNum = data.line;
    const newCode = data.code;

    if (!filePath || !lineNum || !newCode) return;

    outputChannel.appendLine(`[DevDeck] Inbound repair offer: ${filePath}:${lineNum} -> ${newCode}`);

    const config = vscode.workspace.getConfiguration("devdeck");
    const autoApply = config.get("autoApplyRepairs");

    if (autoApply) {
        applyPatchToEditor(filePath, lineNum, newCode);
    } else {
        const choice = await vscode.window.showInformationMessage(
            `🛠️ DevDeck AI Repair ready for ${path.basename(filePath)} (line ${lineNum})`,
            "Apply Fix",
            "View Diff",
            "Dismiss"
        );

        if (choice === "Apply Fix") {
            applyPatchToEditor(filePath, lineNum, newCode);
        } else if (choice === "View Diff") {
            openDiffPreview(filePath, lineNum, newCode);
        }
    }
}

async function applyPatchToEditor(filePath, lineNum, newCode) {
    try {
        const doc = await vscode.workspace.openTextDocument(filePath);
        const editor = await vscode.window.showTextDocument(doc);
        const lineIndex = lineNum - 1;

        if (lineIndex < 0 || lineIndex >= doc.lineCount) {
            vscode.window.showErrorMessage(`DevDeck: Line ${lineNum} is out of bounds.`);
            return;
        }

        const line = doc.lineAt(lineIndex);
        const indent = line.text.match(/^\s*/)[0];
        const replacement = indent + newCode.trim();

        await editor.edit((editBuilder) => {
            editBuilder.replace(line.range, replacement);
        });

        await doc.save();
        vscode.window.showInformationMessage(`✅ DevDeck: Applied repair to ${path.basename(filePath)}:${lineNum}`);
        outputChannel.appendLine(`[DevDeck] Successfully patched ${filePath}:${lineNum}`);
    } catch (e) {
        vscode.window.showErrorMessage(`DevDeck error applying patch: ${e.message}`);
    }
}

function sendDemoError() {
    if (!socket || socket.readyState !== WebSocket.OPEN) {
        vscode.window.showWarningMessage("DevDeck Relay is not connected.");
        return;
    }

    const payload = {
        timestamp: new Date().toISOString(),
        command: "vscode-demo-error",
        error_file: "auth_service.py",
        error_line: 42,
        original_line: "if user.is_authenticated():",
        error_text: "AttributeError: 'NoneType' object has no attribute 'is_authenticated'",
        source_context: "38 | user = db.find_user(user_id)\n40 | print(f'Fetching token for {user_id}')\n>>> 42 | if user.is_authenticated():\n43 |     return user.token",
        language: "python"
    };

    socket.send(JSON.stringify(payload));
    vscode.window.showInformationMessage("🚀 Staged Demo incident sent to DevDeck Pocket device!");
}

async function diagnoseActiveFile() {
    const editor = vscode.window.activeTextEditor;
    if (!editor) {
        vscode.window.showWarningMessage("No active text file open.");
        return;
    }

    if (!socket || socket.readyState !== WebSocket.OPEN) {
        vscode.window.showWarningMessage("DevDeck Relay is not connected.");
        return;
    }

    const doc = editor.document;
    const selection = editor.selection;
    const lineNum = selection.active.line + 1;
    const lineText = doc.lineAt(selection.active.line).text;

    const payload = {
        timestamp: new Date().toISOString(),
        command: "editor-diagnostic-audit",
        error_file: doc.fileName,
        error_line: lineNum,
        original_line: lineText.trim(),
        error_text: `Manual diagnostic request on line ${lineNum}: ${lineText.trim()}`,
        source_context: doc.getText(),
        language: doc.languageId
    };

    socket.send(JSON.stringify(payload));
    vscode.window.showInformationMessage(`🔍 Dispatched ${path.basename(doc.fileName)}:${lineNum} to DevDeck Pocket!`);
}

function deactivate() {
    disconnectRelay();
}

module.exports = { activate, deactivate };
