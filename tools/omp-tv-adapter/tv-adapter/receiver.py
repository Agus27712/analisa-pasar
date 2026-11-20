#!/usr/bin/env python3
"""Adapted AI Agent Lens webhook receiver for Tokocrypto.

Upstream: roan-maverick/tradingview-indicator-for-ai-agents
  examples/webhook_receiver.py

Adaptation:
  * symbols are normalised to Tokocrypto BASEQUOTE (BINANCE:BTCUSDT /
    TOKOCRYPTO:BTCUSDT -> BTCUSDT) and validated against the venue book;
  * packets land under trader/tv-adapter/packets/ where the omp MCP server
    reads them;
  * a shared token is required (TV_LENS_TOKEN env or --token); the first run
    can generate one.

Standard library only.

    python3 receiver.py --port 8080
    python3 receiver.py --print-token
"""

import argparse
import json
import os
import secrets
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

SCHEMA = "ai-agent-lens/v1"
MAX_BODY = 64 * 1024
TOKEN_FILE = os.path.join(common.ADAPTER_DIR, "tv-lens-token.txt")


def load_or_make_token():
    tok = (os.environ.get("TV_LENS_TOKEN") or "").strip()
    if tok:
        return tok
    try:
        with open(TOKEN_FILE, encoding="utf-8") as fh:
            raw = fh.read().strip()
        if raw:
            return raw
    except OSError:
        pass
    raw = secrets.token_hex(24)
    try:
        with open(TOKEN_FILE, "w", encoding="utf-8") as fh:
            fh.write(raw)
    except OSError:
        pass
    return raw


def safe_name(text):
    import re
    return re.sub(r"[^A-Za-z0-9._-]+", "_", str(text))[:80] or "unknown"


class Handler(BaseHTTPRequestHandler):
    token = ""
    out_dir = Path(common.PACKETS_DIR)

    def _reply(self, code, message, extra=None):
        body = json.dumps({"ok": code == 200, "message": message, **(extra or {})}).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/health":
            latest = common.latest_packet()
            return self._reply(200, "tv-lens receiver alive", {
                "latest": latest and {k: latest.get(k) for k in
                                      ("event", "symbol", "timeframe", "time")},
                "latest_age_s": common.age_seconds(os.path.join(self.out_dir, "history.jsonl")),
            })
        if url.path in ("/latest", "/latest.json"):
            q = parse_qs(url.query)
            rows = common.packet_summaries(q.get("symbol", [None])[0],
                                           q.get("timeframe", [None])[0])
            return self._reply(200, "latest packet", {"packet": rows[-1] if rows else None})
        return self._reply(404, "not found")

    def do_POST(self):
        url = urlparse(self.path)
        if url.path != "/webhook":
            return self._reply(404, "not found")
        q = parse_qs(url.query)
        if self.token and (q.get("token", [""])[0] != self.token):
            return self._reply(401, "bad token")

        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self._reply(400, "empty or oversized body")
        raw = self.rfile.read(length)
        try:
            packet = json.loads(raw)
        except json.JSONDecodeError:
            return self._reply(400, "body is not valid JSON")
        if not isinstance(packet, dict) or packet.get("schema") != SCHEMA:
            return self._reply(400, "expected schema %s" % SCHEMA)
        if not packet.get("symbol"):
            return self._reply(400, "packet has no symbol")

        try:
            venue_symbol = common.normalize_symbol(packet["symbol"])
            info = common.verify_symbol(venue_symbol)
        except common.AdapterError as exc:
            return self._reply(400, str(exc))
        if not info or info.get("status") != "TRADING":
            return self._reply(400, "symbol %s is not TRADING on the venue" % venue_symbol)

        packet["_received_at"] = common.iso_now()
        packet["_venue_symbol"] = venue_symbol
        packet["symbol"] = venue_symbol

        self.out_dir.mkdir(parents=True, exist_ok=True)
        chart = safe_name("%s_%s" % (venue_symbol, packet.get("timeframe") or "na"))
        text = json.dumps(packet, indent=2)
        (self.out_dir / "latest.json").write_text(text)
        (self.out_dir / "latest").mkdir(exist_ok=True)
        (self.out_dir / "latest" / ("%s.json" % chart)).write_text(text)
        with (self.out_dir / "history.jsonl").open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(packet, ensure_ascii=False) + "\n")

        print("[%s] %s %s" % (packet["_received_at"], packet.get("event"),
                              packet.get("state_code")), flush=True)
        return self._reply(200, "saved", {"venue_symbol": venue_symbol,
                                         "latest": "packets/latest/%s.json" % chart})

    def log_message(self, *args):
        pass


def main(argv=None):
    p = argparse.ArgumentParser(description="Tokocrypto adapter for AI Agent Lens packets")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--token", default="", help="shared secret (?token=...)")
    p.add_argument("--out", default=common.PACKETS_DIR)
    p.add_argument("--print-token", action="store_true")
    a = p.parse_args(argv)

    tok = a.token or load_or_make_token()
    if a.print_token:
        print(tok)
        return 0
    Handler.token = tok
    Handler.out_dir = Path(a.out)
    print("Listening on http://%s:%d/webhook (token required, saving to %s)"
          % (a.host, a.port, Path(a.out).resolve()), flush=True)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    sys.exit(main())
