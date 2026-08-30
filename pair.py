"""
DevDeck Pair — Zero-dependency standalone pairing QR generator.
Uses Python stdlib + websockets (bundled by PyInstaller into the .exe).
End user needs nothing installed — just run devdeck-pair.exe.
"""

import asyncio
import http.server
import socket
import threading
import webbrowser
import json
import hmac
import hashlib
import platform
import subprocess
import signal
import sys

try:
    import websockets
    HAS_WS = True
except ImportError:
    HAS_WS = False

# ── Config ──────────────────────────────────────────────────────────────────
WS_PORT   = 8765
HTTP_PORT = 8766
_raw = hmac.new(
    hashlib.sha256(socket.gethostname().encode()).digest(),
    b"devdeck-pair",
    hashlib.sha256,
).hexdigest()[:12].upper()
SECRET = f"{_raw[:4]}-{_raw[4:8]}-{_raw[8:12]}"

# ── Live Pairing State ────────────────────────────────────────────────────────
_state = {
    "paired": False,
    "device": None,
    "device_ip": None,
    "connected_at": None,
    "is_returning": False,
    "pair_count": 0,
}

# ── Device Memory (persists across runs in ~/.devdeck/known_devices.json) ────
MEMORY_FILE = __import__("pathlib").Path.home() / ".devdeck" / "known_devices.json"

def load_known_devices() -> dict:
    try:
        MEMORY_FILE.parent.mkdir(parents=True, exist_ok=True)
        if MEMORY_FILE.exists():
            return json.loads(MEMORY_FILE.read_text(encoding="utf-8"))
    except Exception:
        pass
    return {}

def save_device(device_name: str, device_ip: str):
    """Persist device so it is recognized on next run."""
    try:
        from datetime import datetime
        devices = load_known_devices()
        key = device_name.strip().lower()
        prev_count = devices.get(key, {}).get("pair_count", 0)
        devices[key] = {
            "name": device_name,
            "last_ip": device_ip,
            "last_seen": datetime.now().strftime("%Y-%m-%d %H:%M"),
            "pair_count": prev_count + 1,
        }
        MEMORY_FILE.write_text(json.dumps(devices, indent=2), encoding="utf-8")
    except Exception:
        pass

def is_known_device(device_name: str):
    """Returns (is_returning: bool, pair_count: int)."""
    devices = load_known_devices()
    key = device_name.strip().lower()
    if key in devices:
        return True, devices[key].get("pair_count", 0)
    return False, 0


# ── Auto-clear ports on startup ──────────────────────────────────────────────
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

# ── Network ──────────────────────────────────────────────────────────────────
def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def qr_url(data):
    import urllib.parse
    return f"https://api.qrserver.com/v1/create-qr-code/?size=280x280&data={urllib.parse.quote(data)}"

# ── HTML Page ────────────────────────────────────────────────────────────────
def build_html(ip, pairing_json):
    img_url = qr_url(pairing_json)
    hostname = socket.gethostname()
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DevDeck Pair</title>
<style>
  *{{box-sizing:border-box;margin:0;padding:0}}
  body{{background:#0d1117;color:#e6edf3;font-family:'Segoe UI',system-ui,sans-serif;
       display:flex;flex-direction:column;align-items:center;justify-content:center;
       min-height:100vh;padding:32px 16px;transition:background .5s}}
  h1{{font-size:1.6rem;font-weight:700;color:#58a6ff;margin-bottom:6px}}
  p.sub{{color:#8b949e;font-size:.9rem;margin-bottom:28px}}
  .card{{background:#161b22;border:1px solid #30363d;border-radius:16px;
         padding:32px;text-align:center;max-width:420px;width:100%;transition:all .4s}}
  /* QR section */
  #qr-section{{transition:opacity .4s}}
  .qr{{background:#fff;border-radius:12px;padding:12px;display:inline-block;margin-bottom:20px}}
  .qr img{{width:260px;height:260px;display:block}}
  .badge-waiting{{display:inline-block;background:#21262d;border:1px solid #30363d;color:#8b949e;
                  border-radius:20px;padding:4px 14px;font-size:.75rem;margin-bottom:20px}}
  .pulse{{animation:pulse 1.5s infinite}}
  @keyframes pulse{{0%,100%{{opacity:1}}50%{{opacity:.4}}}}
  .info{{background:#0d1117;border-radius:10px;padding:14px 18px;text-align:left;
         font-size:.82rem;color:#8b949e;line-height:1.9}}
  .info b{{color:#e6edf3}}
  .pin{{font-size:1.3rem;font-weight:800;color:#58a6ff;letter-spacing:.15em;margin:16px 0 6px}}
  small{{color:#6e7681;font-size:.75rem}}
  /* Paired section */
  #paired-section{{display:none;animation:pop .5s ease}}
  @keyframes pop{{0%{{transform:scale(.8);opacity:0}}100%{{transform:scale(1);opacity:1}}}}
  .check{{font-size:4rem;margin-bottom:12px}}
  .paired-title{{font-size:1.4rem;font-weight:700;color:#3fb950;margin-bottom:6px}}
  .paired-sub{{color:#8b949e;font-size:.85rem;margin-bottom:24px}}
  .device-card{{background:#0d1117;border-radius:12px;padding:16px 20px;text-align:left;
                font-size:.85rem;line-height:2;border:1px solid #238636}}
  .device-card b{{color:#e6edf3}}
  .dot{{display:inline-block;width:8px;height:8px;border-radius:50%;
        background:#3fb950;margin-right:6px;animation:pulse 1.5s infinite}}
</style>
</head>
<body>
  <h1>&#9889; DevDeck Pair</h1>
  <p class="sub" id="subtitle">Scan with the DevDeck Android app to connect</p>

  <div class="card">
    <!-- QR State -->
    <div id="qr-section">
      <div class="qr">
        <img src="{img_url}" alt="Pairing QR"
             onerror="this.parentElement.innerHTML='<p style=padding:20px;color:#e6edf3>Offline &mdash; use PIN</p>'">
      </div>
      <div class="badge-waiting pulse">&#9679; Waiting for phone&hellip;</div>
      <div class="info">
        <b>Device:</b> {hostname}<br>
        <b>IP:</b> {ip}<br>
        <b>Port:</b> {WS_PORT}
      </div>
      <div class="pin">{SECRET}</div>
      <small>No camera? Open DevDeck &rarr; Settings &rarr; Enter IP Manually<br>
        <b>{ip}:{WS_PORT}</b></small>
    </div>

    <!-- Paired State -->
    <div id="paired-section" style="display:none">
      <div class="check" id="p-emoji">&#9989;</div>
      <div class="paired-title" id="p-title">PAIRED!</div>
      <div class="paired-sub" id="p-sub">Your phone is connected and ready</div>
      <div class="device-card">
        <span class="dot"></span><b>Status:</b> Live<br>
        <b>&#128241; Device:</b> <span id="p-device">-</span><br>
        <b>&#127758; Phone IP:</b> <span id="p-ip">-</span><br>
        <b>&#128337; Connected:</b> <span id="p-time">-</span><br>
        <b>&#128279; Sessions:</b> <span id="p-count">-</span>
      </div>
    </div>
  </div>

<script>
  let paired = false;
  function poll() {{
    fetch('/status').then(r => r.json()).then(data => {{
      if (data.paired && !paired) {{
        paired = true;
        const returning = data.is_returning;
        document.getElementById('subtitle').textContent =
          returning ? 'Welcome back!' : 'Connected for the first time';
        document.getElementById('qr-section').style.opacity = '0';
        setTimeout(() => {{
          document.getElementById('qr-section').style.display = 'none';
          const ps = document.getElementById('paired-section');
          ps.style.display = 'block';
          if (returning) {{
            document.getElementById('p-emoji').textContent = '👋';
            document.getElementById('p-title').textContent = 'WELCOME BACK!';
            document.getElementById('p-sub').textContent = 'Reconnected without scanning — device remembered';
            document.getElementById('p-title').style.color = '#58a6ff';
          }}
          document.getElementById('p-device').textContent = data.device || 'Android Device';
          document.getElementById('p-ip').textContent = data.device_ip || 'Local';
          document.getElementById('p-time').textContent = data.connected_at || 'Just now';
          document.getElementById('p-count').textContent =
            (data.pair_count || 1) + ' total connection' + (data.pair_count > 1 ? 's' : '');
          document.title = returning ? '👋 DevDeck Reconnected' : '✅ DevDeck Paired';
        }}, 400);
      }}
      if (!data.paired && paired) {{
        paired = false;
        document.getElementById('qr-section').style.opacity = '1';
        document.getElementById('qr-section').style.display = 'block';
        document.getElementById('paired-section').style.display = 'none';
        document.getElementById('subtitle').textContent = 'Scan with the DevDeck Android app to connect';
        document.title = '⚡ DevDeck Pair';
      }}
    }}).catch(() => {{}});
  }}
  setInterval(poll, 800);
</script>
</body>
</html>"""


# ── HTTP Handler ─────────────────────────────────────────────────────────────
_HTML_CACHE = ""

class PairHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/", "/pair"):
            body = _HTML_CACHE.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path == "/status":
            body = json.dumps(_state).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()
    def log_message(self, *a): pass

# ── WebSocket Handler ─────────────────────────────────────────────────────────
async def ws_handler(websocket):
    """Accept phone connection, detect returning device, update live state."""
    from datetime import datetime
    client_ip = websocket.remote_address[0] if websocket.remote_address else "Unknown"
    try:
        raw = await asyncio.wait_for(websocket.recv(), timeout=10)
        try:
            msg = json.loads(raw)
        except Exception:
            msg = {}
        device_name = (msg.get("device_name") or msg.get("device") or
                       msg.get("host") or "Android Device")

        returning, pair_count = is_known_device(device_name)
        save_device(device_name, client_ip)          # always update last_seen

        _state["paired"]       = True
        _state["device"]       = device_name
        _state["device_ip"]    = client_ip
        _state["connected_at"] = datetime.now().strftime("%H:%M:%S")
        _state["is_returning"] = returning
        _state["pair_count"]   = pair_count + 1

        if returning:
            print(f"\n  👋 Welcome back, {device_name}! ({client_ip})  [#{pair_count + 1} connection]")
        else:
            print(f"\n  ✅ PAIRED! — {device_name} ({client_ip})  [First time — device saved]")
        print(f"     Your phone is now connected to DevDeck.\n")

        async for _ in websocket:
            pass
    except Exception:
        pass
    finally:
        if _state["device_ip"] == client_ip:
            _state["paired"]   = False
            _state["device"]   = None
            print(f"\n  ⚠️  Phone disconnected. Showing QR again...\n")


# ── Main ─────────────────────────────────────────────────────────────────────
async def async_main():
    global _HTML_CACHE

    free_port(HTTP_PORT)
    free_port(WS_PORT)

    ip           = get_local_ip()
    hostname     = socket.gethostname()
    os_name      = platform.system()
    pairing      = {"url": f"ws://{ip}:{WS_PORT}", "secret": SECRET,
                    "device_name": f"{hostname} ({os_name})", "host": hostname, "os": os_name}
    pairing_json = json.dumps(pairing)
    _HTML_CACHE  = build_html(ip, pairing_json)

    # Start HTTP server in background thread
    server = http.server.HTTPServer(("0.0.0.0", HTTP_PORT), PairHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    print()
    print("=" * 56)
    print("  DevDeck Pair  --  Instant Pairing Server")
    print("=" * 56)
    print(f"  Device  : {hostname} ({os_name})")
    print(f"  IP      : {ip}")
    print(f"  PIN     : {SECRET}")
    print(f"  Portal  : http://localhost:{HTTP_PORT}/pair")
    print("=" * 56)
    print()
    print("  Opening browser... scan the QR with DevDeck app.")
    print("  Page updates live when phone connects.")
    print()

    # Open browser
    try:
        webbrowser.open(f"http://localhost:{HTTP_PORT}/pair")
    except Exception:
        pass

    # Auto ADB reverse
    try:
        import shutil
        adb = shutil.which("adb")
        if adb:
            subprocess.run([adb, "reverse", f"tcp:{WS_PORT}", f"tcp:{WS_PORT}"],
                           capture_output=True, timeout=3)
            print("  ADB reverse active -> USB pairing ready.")
    except Exception:
        pass

    print("  Press Ctrl+C to stop.\n")

    # Start WebSocket server
    if HAS_WS:
        async with websockets.serve(ws_handler, "0.0.0.0", WS_PORT):
            await asyncio.Future()  # run forever
    else:
        threading.Event().wait()

def main():
    try:
        asyncio.run(async_main())
    except KeyboardInterrupt:
        print("\n  Stopped.")

if __name__ == "__main__":
    main()
