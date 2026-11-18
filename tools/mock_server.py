"""PC-side page test without a phone: mimics the phone's API (PIN 1234).
Run: python tools/mock_server.py  ->  http://localhost:8765"""
import json, os, uuid
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs, quote

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PAGE = os.path.join(ROOT, "app", "src", "main", "assets", "index.html")
TOKEN = uuid.uuid4().hex
OUTBOX = [{"id": "a1", "name": "अनुमान_2026.pdf", "size": 2_400_000, "data": b"%PDF demo"},
          {"id": "b2", "name": "photo 01.jpg", "size": 9, "data": b"jpeg demo"}]
RECEIVED = []

class H(BaseHTTPRequestHandler):
    def authed(self):
        return f"fs={TOKEN}" in (self.headers.get("Cookie") or "")
    def send(self, code, body=b"", ctype="text/plain; charset=utf-8", extra=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (extra or {}).items(): self.send_header(k, v)
        self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/":
            return self.send(200, open(PAGE, "rb").read(), "text/html; charset=utf-8")
        if not self.authed(): return self.send(401)
        if u.path == "/api/state":
            s = {"device": "Mock Phone", "outbox": [{k: f[k] for k in ("id", "name", "size")} for f in OUTBOX], "received": RECEIVED}
            return self.send(200, json.dumps(s, ensure_ascii=False).encode(), "application/json")
        if u.path.startswith("/api/file/"):
            f = next((f for f in OUTBOX if f["id"] == u.path.rsplit("/", 1)[1]), None)
            if not f: return self.send(404)
            return self.send(200, f["data"], "application/octet-stream",
                             {"Content-Disposition": "attachment; filename*=UTF-8''" + quote(f["name"])})
        self.send(404)
    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/api/login":
            if parse_qs(u.query).get("pin", [""])[0] == "1234":
                return self.send(200, b"ok", extra={"Set-Cookie": f"fs={TOKEN}; Path=/; HttpOnly; SameSite=Strict"})
            return self.send(401, "गलत PIN".encode())
        self.send(404)
    def do_PUT(self):
        u = urlparse(self.path)
        if not self.authed(): return self.send(401)
        n = int(self.headers.get("Content-Length", 0)); left = n
        while left > 0: left -= len(self.rfile.read(min(left, 1 << 16)))
        name = parse_qs(u.query).get("name", ["file"])[0]
        RECEIVED.insert(0, {"name": name, "size": n})
        self.send(200, json.dumps({"name": name}, ensure_ascii=False).encode(), "application/json")
    def log_message(self, *a): pass

ThreadingHTTPServer(("127.0.0.1", 8765), H).serve_forever()
