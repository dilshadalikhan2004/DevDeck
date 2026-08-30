import tkinter as tk
from tkinter import ttk
import threading
import socket
import json
import hmac
import hashlib
import platform
import subprocess
import asyncio
import http.server
import io
import sys
from datetime import datetime

try:
    import qrcode
    from PIL import Image, ImageTk
    HAS_PIL_QR = True
except ImportError:
    HAS_PIL_QR = False

try:
    import websockets
    HAS_WS = True
except ImportError:
    HAS_WS = False

WS_PORT = 8765
HTTP_PORT = 8766

_raw = hmac.new(
    hashlib.sha256(socket.gethostname().encode()).digest(),
    b"devdeck-pair",
    hashlib.sha256,
).hexdigest()[:12].upper()
SECRET = f"{_raw[:4]}-{_raw[4:8]}-{_raw[8:12]}"

state = {
    "paired": False,
    "device": None,
    "device_ip": None,
    "connected_at": None,
}

def free_port(port):
    try:
        result = subprocess.run(["netstat", "-ano"], capture_output=True, text=True, timeout=5)
        for line in result.stdout.splitlines():
            if f":{port}" in line and "LISTENING" in line:
                parts = line.split()
                pid = int(parts[-1])
                if pid > 0:
                    subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True, timeout=3)
    except Exception:
        pass

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

# HTTP server for status and fallback web view
class StatusHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/status":
            body = json.dumps(state).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.end_headers()
            self.wfile.write(b"DevDeck Relay Running")
    def log_message(self, *a): pass

async def ws_handler(websocket):
    client_ip = websocket.remote_address[0] if websocket.remote_address else "Unknown"
    try:
        raw = await asyncio.wait_for(websocket.recv(), timeout=10)
        try:
            msg = json.loads(raw)
        except Exception:
            msg = {}
        device_name = msg.get("device_name") or msg.get("device") or msg.get("host") or "Android Device"
        state["paired"] = True
        state["device"] = device_name
        state["device_ip"] = client_ip
        state["connected_at"] = datetime.now().strftime("%H:%M:%S")
        async for _ in websocket:
            pass
    except Exception:
        pass
    finally:
        if state["device_ip"] == client_ip:
            state["paired"] = False
            state["device"] = None

async def run_ws_server():
    if HAS_WS:
        async with websockets.serve(ws_handler, "0.0.0.0", WS_PORT):
            await asyncio.Future()

def start_backend():
    free_port(HTTP_PORT)
    free_port(WS_PORT)

    # HTTP server thread
    http_srv = http.server.HTTPServer(("0.0.0.0", HTTP_PORT), StatusHandler)
    threading.Thread(target=http_srv.serve_forever, daemon=True).start()

    # ADB reverse
    try:
        import shutil
        adb = shutil.which("adb")
        if adb:
            subprocess.run([adb, "reverse", f"tcp:{WS_PORT}", f"tcp:{WS_PORT}"], capture_output=True, timeout=3)
    except Exception:
        pass

    # WebSocket server in new event loop
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(run_ws_server())

class DevDeckApp:
    def __init__(self, root):
        self.root = root
        self.root.title("DevDeck • Pairing Bridge")
        self.root.geometry("440x620")
        self.root.configure(bg="#0d1117")
        self.root.resizable(False, False)

        self.ip = get_local_ip()
        self.hostname = socket.gethostname()
        self.os_name = platform.system()

        pairing = {
            "url": f"ws://{self.ip}:{WS_PORT}",
            "secret": SECRET,
            "device_name": f"{self.hostname} ({self.os_name})",
            "host": self.hostname,
            "os": self.os_name
        }
        self.pairing_json = json.dumps(pairing)

        self.setup_ui()
        self.check_status_loop()

    def setup_ui(self):
        # Header
        title = tk.Label(self.root, text="⚡ DevDeck Pair", font=("Segoe UI", 18, "bold"), fg="#58a6ff", bg="#0d1117")
        title.pack(pady=(20, 2))

        self.subtitle = tk.Label(self.root, text="Scan with the DevDeck Android app", font=("Segoe UI", 10), fg="#8b949e", bg="#0d1117")
        self.subtitle.pack(pady=(0, 15))

        # Main Card Frame
        self.card = tk.Frame(self.root, bg="#161b22", highlightbackground="#30363d", highlightthickness=1, padx=20, pady=20)
        self.card.pack(padx=25, fill="both", expand=True)

        # QR Code Container
        self.qr_frame = tk.Frame(self.card, bg="#ffffff", padx=8, pady=8)
        self.qr_frame.pack(pady=10)

        if HAS_PIL_QR:
            qr = qrcode.QRCode(version=1, box_size=6, border=1)
            qr.add_data(self.pairing_json)
            qr.make(fit=True)
            img = qr.make_image(fill_color="black", back_color="white")
            self.qr_photo = ImageTk.PhotoImage(img)
            self.qr_label = tk.Label(self.qr_frame, image=self.qr_photo, bg="white")
            self.qr_label.pack()
        else:
            self.qr_label = tk.Label(self.qr_frame, text="QR Code Available", font=("Segoe UI", 12), bg="white", fg="black", padx=30, pady=30)
            self.qr_label.pack()

        # Status Badge
        self.badge = tk.Label(self.card, text="● WAITING FOR PHONE...", font=("Segoe UI", 9, "bold"), fg="#e3b341", bg="#21262d", padx=12, pady=4)
        self.badge.pack(pady=(12, 12))

        # Info Box
        self.info_frame = tk.Frame(self.card, bg="#0d1117", padx=12, pady=10)
        self.info_frame.pack(fill="x", pady=5)

        info_text = f"Device: {self.hostname} ({self.os_name})\nIP: {self.ip}  •  Port: {WS_PORT}\nPIN: {SECRET}"
        self.info_label = tk.Label(self.info_frame, text=info_text, font=("Consolas", 9), fg="#c9d1d9", bg="#0d1117", justify="left")
        self.info_label.pack(anchor="w")

        # Bottom help
        self.help_label = tk.Label(self.card, text="Manual: Enter IP & PIN in App Settings", font=("Segoe UI", 8), fg="#6e7681", bg="#161b22")
        self.help_label.pack(pady=(10, 0))

    def check_status_loop(self):
        if state["paired"]:
            self.badge.config(text="● PAIRED & ACTIVE", fg="#3fb950", bg="#1b4723")
            self.subtitle.config(text=f"Connected to {state['device']} ({state['device_ip']})", fg="#3fb950")
        else:
            self.badge.config(text="● WAITING FOR PHONE...", fg="#e3b341", bg="#21262d")
            self.subtitle.config(text="Scan with the DevDeck Android app", fg="#8b949e")

        self.root.after(800, self.check_status_loop)

if __name__ == "__main__":
    t = threading.Thread(target=start_backend, daemon=True)
    t.start()

    root = tk.Tk()
    app = DevDeckApp(root)
    root.mainloop()