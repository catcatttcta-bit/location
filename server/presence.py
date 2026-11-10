#!/usr/bin/env python3
"""Мини-счётчик «сейчас онлайн» для Exit Country Widget.

GET /ping?id=<случайный id установки>  -> {"online": N}   (приложение шлёт раз в минуту)
GET /online                            -> {"online": N}
GET /                                  -> страница с числом (автообновление)

Онлайн = пинг был за последние TTL секунд. Храним только случайный id и время в памяти:
IP-адреса не логируются, на диск ничего не пишется.
"""
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = 8787
TTL = 150          # сек; приложение пингует раз в 60 с
MAX_IDS = 20000    # защита памяти от мусорных id
ID_RE = re.compile(r"^[A-Za-z0-9-]{8,64}$")

seen = {}
lock = threading.Lock()

PAGE = """<!doctype html><html lang="ru"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="refresh" content="10">
<title>Exit Country — онлайн</title>
<body style="margin:0;height:100vh;display:grid;place-items:center;background:#12141c;color:#e8eaf2;font-family:system-ui,sans-serif;text-align:center">
<div><div style="font-size:min(30vw,200px);font-weight:700;line-height:1">{n}</div>
<div style="color:#7C829A;margin-top:12px">сейчас пользуются виджетом</div></div></body></html>"""


def count(touch=None):
    now = time.time()
    with lock:
        if touch and (touch in seen or len(seen) < MAX_IDS):
            seen[touch] = now
        for k in [k for k, t in seen.items() if now - t > TTL]:
            del seen[k]
        return len(seen)


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):  # без логов — не храним IP
        pass

    def send(self, code, body, ctype):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/ping":
            i = parse_qs(u.query).get("id", [""])[0]
            if not ID_RE.match(i):
                return self.send(400, '{"error":"bad id"}', "application/json")
            return self.send(200, json.dumps({"online": count(i)}), "application/json")
        if u.path == "/online":
            return self.send(200, json.dumps({"online": count()}), "application/json")
        if u.path == "/":
            return self.send(200, PAGE.format(n=count()), "text/html; charset=utf-8")
        self.send(404, "not found", "text/plain")


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
