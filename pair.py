"""
DevDeck Pair — Zero-dependency standalone pairing QR generator.
Uses ONLY Python stdlib: http.server, socket, webbrowser, threading, json.
No pip install needed. Builds into a single .exe via PyInstaller.
"""

import http.server
import socket
import threading
import webbrowser
import json
import os
import sys
import hmac
import hashlib
import platform
import subprocess

# ── Config ──────────────────────────────────────────────────────────────────
WS_PORT   = 8765
HTTP_PORT = 8766
_raw = hmac.new(
    hashlib.sha256(socket.gethostname().encode()).digest(),
    b"devdeck-pair",
    hashlib.sha256,
).hexdigest()[:12].upper()
SECRET = f"{_raw[:4]}-{_raw[4:8]}-{_raw[8:12]}"

# ── Network helpers ──────────────────────────────────────────────────────────
def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

def qr_image_url(data):
    import urllib.parse
    return f"https://api.qrserver.com/v1/create-qr-code/?size=300x300&data={urllib.parse.quote(data)}"

# ── HTML pairing page ────────────────────────────────────────────────────────
def build_html(ip, pairing_json):
    img_url  = qr_image_url(pairing_json)
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
       min-height:100vh;padding:32px 16px}}
  h1{{font-size:1.6rem;font-weight:700;color:#58a6ff;margin-bottom:6px}}
  p.sub{{color:#8b949e;font-size:.9rem;margin-bottom:28px}}
  .card{{background:#161b22;border:1px solid #30363d;border-radius:16px;
         padding:32px;text-align:center;max-width:420px;width:100%}}
  .qr{{background:#fff;border-radius:12px;padding:12px;display:inline-block;margin-bottom:20px}}
  .qr img{{width:260px;height:260px;display:block}}
  .badge{{display:inline-block;background:#238636;color:#fff;border-radius:20px;
          padding:4px 14px;font-size:.75rem;font-weight:600;margin-bottom:20px}}
  .info{{background:#0d1117;border-radius:10px;padding:14px 18px;text-align:left;
         font-size:.82rem;color:#8b949e;line-height:1.9}}
  .info b{{color:#e6edf3}}
  .pin{{font-size:1.4rem;font-weight:800;color:#58a6ff;letter-spacing:.15em;margin:18px 0 6px}}
  small{{color:#6e7681;font-size:.75rem}}
</style>
</head>
<body>
  <h1>&#9889; DevDeck Pair</h1>
  <p class="sub">Scan with the DevDeck Android app to connect</p>
  <div class="card">
    <div class="qr">
      <img src="{img_url}" alt="Pairing QR" onerror="this.parentElement.innerHTML='<p style=padding:20px;color:#e6edf3>Offline &mdash; use PIN below</p>'">
    </div>
    <div class="badge">&#9679; READY TO PAIR</div>
    <div class="info">
      <b>Device:</b> {hostname}<br>
      <b>IP:</b> {ip}<br>
      <b>WebSocket Port:</b> {WS_PORT}
    </div>
    <div class="pin">{SECRET}</div>
    <small>No camera? Open DevDeck &rarr; Settings &rarr; Enter IP Manually<br>
      <b>{ip}:{WS_PORT}</b></small>
  </div>
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
        else:
            self.send_response(404)
            self.end_headers()
    def log_message(self, *a): pass

# ── Main ─────────────────────────────────────────────────────────────────────
def main():
    global _HTML_CACHE
    ip           = get_local_ip()
    hostname     = socket.gethostname()
    os_name      = platform.system()
    pairing      = {"url": f"ws://{ip}:{WS_PORT}", "secret": SECRET,
                    "device_name": f"{hostname} ({os_name})", "host": hostname, "os": os_name}
    pairing_json = json.dumps(pairing)
    _HTML_CACHE  = build_html(ip, pairing_json)

    print()
    print("=" * 56)
    print("  DevDeck Pair  --  Zero-Dependency Pairing Server")
    print("=" * 56)
    print(f"  Device  : {hostname} ({os_name})")
    print(f"  IP      : {ip}")
    print(f"  PIN     : {SECRET}")
    print(f"  Portal  : http://localhost:{HTTP_PORT}/pair")
    print("=" * 56)
    print()

    server = http.server.HTTPServer(("0.0.0.0", HTTP_PORT), PairHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    try:
        webbrowser.open(f"http://localhost:{HTTP_PORT}/pair")
        print("  Browser opened automatically with QR Code.")
    except Exception:
        print(f"  Open manually: http://localhost:{HTTP_PORT}/pair")

    try:
        import shutil
        adb = shutil.which("adb")
        if adb:
            subprocess.run([adb, "reverse", f"tcp:{WS_PORT}", f"tcp:{WS_PORT}"],
                           capture_output=True, timeout=3)
            print("  ADB reverse active -> USB pairing ready.")
    except Exception:
        pass

    print()
    print("  Press Ctrl+C to stop.")
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        print("\n  Stopped.")
        server.shutdown()

if __name__ == "__main__":
    main()
