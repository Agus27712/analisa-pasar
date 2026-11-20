#!/usr/bin/env python3
"""Shared helpers for the TradingView -> Tokocrypto / analisa-pasar adapter.

Python 3 standard library only. No app-owned Tokocrypto hosts are contacted;
market data comes from data-api.binance.vision (identical book) and orders go
through the local Analisa Pasar Agent Bridge.
"""

import datetime as dt
import hashlib
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HOME = os.environ.get("HOME") or "/data/data/com.termux/files/home"
TRADER_DIR = os.environ.get("TV_TRADER_DIR") or os.path.join(HOME, "trader")
ADAPTER_DIR = os.path.dirname(os.path.abspath(__file__))
PACKETS_DIR = os.path.join(ADAPTER_DIR, "packets")
LATEST_DIR = os.path.join(PACKETS_DIR, "latest")
HISTORY_FILE = os.path.join(PACKETS_DIR, "history.jsonl")
ORDERS_FILE = os.path.join(TRADER_DIR, "orders.json")
RISK_FILE = os.path.join(TRADER_DIR, "state", "risk.json")
BROKER_FILE = os.path.join(TRADER_DIR, "state", "broker.json")
PORTFOLIO_FILE = os.path.join(TRADER_DIR, "portfolio.json")
CONFIG_FILE = os.path.join(TRADER_DIR, "config.json")
PAPER_JOURNAL = os.path.join(
    HOME, "storage", "shared", "analisa-pasar", "journal", "paper_trades.jsonl"
)

BRIDGE = "http://127.0.0.1:8765"
VISION = "https://data-api.binance.vision/api/v3"
TOKEN_FILE = "agent-bridge-token.txt"
SLIPPAGE_PER_SIDE = 0.0008
USDT_LIKE = ("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "USD", "U", "DAI")
QUOTES = ("USDT", "IDR", "USDC", "BTC", "ETH", "BNB", "FDUSD", "TUSD", "USD1", "U")
TV_TO_VISION = {
    "1": "1m", "3": "3m", "5": "5m", "15": "15m", "30": "30m",
    "60": "1h", "120": "2h", "240": "4h", "360": "6h", "480": "8h",
    "720": "12h", "D": "1d", "1D": "1d", "W": "1w", "1W": "1w",
}
VISION_TO_TV = {v: k for k, v in TV_TO_VISION.items()}

SKILLS_DIR = os.path.join(HOME, ".agents", "skills")
RISK_SCRIPT = os.path.join(SKILLS_DIR, "trader-risk", "check.py")
BROKER_SCRIPT = os.path.join(SKILLS_DIR, "trader-broker", "ticket.py")
TIMEMACHINE_SCRIPT = os.path.join(SKILLS_DIR, "trader-timemachine", "timemachine.py")


class AdapterError(Exception):
    """A clear refusal: never paper over a missing feed, token or gate."""


# ---------------------------------------------------------------------------
# JSON / files
# ---------------------------------------------------------------------------

def read_json(path, default=None):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return default


def write_json(path, data):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
    os.replace(tmp, path)


def append_jsonl(path, row):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(row, ensure_ascii=False) + "\n")


def utc_now():
    return dt.datetime.now(dt.timezone.utc)


def iso_now():
    return utc_now().strftime("%Y-%m-%dT%H:%M:%SZ")


def age_seconds(path):
    try:
        return round(time.time() - os.path.getmtime(path), 1)
    except OSError:
        return None


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

def _http(url, method="GET", body=None, headers=None, timeout=20):
    data = None
    hdr = {"Accept": "application/json", "User-Agent": "tv-tokocrypto/1.0"}
    if headers:
        hdr.update(headers)
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdr["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=hdr, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, raw
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode("utf-8", "replace")
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        raise AdapterError("request failed for %s: %s" % (url, exc))


def _json_from(status, raw, what):
    try:
        return json.loads(raw)
    except ValueError:
        raise AdapterError("%s returned HTTP %s, non-JSON body: %s" % (what, status, raw[:200]))


def vision(path, timeout=20):
    status, raw = _http(VISION + path, timeout=timeout)
    if status == 429:
        raise AdapterError("vision 429: stop market-data calls, use cached data")
    if status != 200:
        raise AdapterError("vision HTTP %s for %s: %s" % (status, path, raw[:200]))
    return _json_from(status, raw, "vision")


def bridge_token():
    tok = (os.environ.get("ANALISA_PASAR_TOKEN") or "").strip()
    if tok:
        return tok
    for d in ("extensions", "extension"):
        p = os.path.join(HOME, ".omp", "agent", d, "analisa-pasar", TOKEN_FILE)
        try:
            with open(p, encoding="utf-8") as fh:
                raw = fh.read().strip()
            if raw:
                return raw
        except OSError:
            continue
    return ""


def bridge_get(path, auth=False, timeout=8):
    headers = {}
    if auth:
        tok = bridge_token()
        if tok:
            headers["X-Agent-Token"] = tok
    status, raw = _http(BRIDGE + path, headers=headers, timeout=timeout)
    if status != 200:
        raise AdapterError("bridge HTTP %s for %s: %s" % (status, path, raw[:200]))
    return _json_from(status, raw, "bridge")


def bridge_health():
    return bridge_get("/health", timeout=6)


def bridge_trading_status():
    tok = bridge_token()
    headers = {"X-Agent-Token": tok} if tok else {}
    status, raw = _http(BRIDGE + "/api/v1/trading/status", headers=headers, timeout=8)
    if status != 200:
        raise AdapterError("bridge trading status HTTP %s: %s" % (status, raw[:200]))
    return _json_from(status, raw, "trading status")


# ---------------------------------------------------------------------------
# symbols and fees
# ---------------------------------------------------------------------------

def normalize_symbol(raw):
    """BINANCE:BTCUSDT / TOKOCRYPTO:BTCUSDT.P / BTC-USDT -> BTCUSDT.

    TradingView perpetual symbols carry .P/.PERP; map them to the spot pair.
    """
    s = str(raw or "").strip().upper()
    if ":" in s:
        s = s.split(":", 1)[1]
    s = s.replace("/", "").replace("_", "").replace("-", "")
    s = re.sub(r"\.(P|PERP|SWAP)$", "", s)
    s = re.sub(r"[^A-Z0-9]", "", s)
    if not (4 <= len(s) <= 24):
        raise AdapterError("invalid symbol %r" % raw)
    for q in sorted(QUOTES, key=len, reverse=True):
        if s.endswith(q) and len(s) > len(q):
            return s
    raise AdapterError("unknown quote asset in %r (want e.g. BTCUSDT)" % raw)


def quote_of(symbol):
    s = normalize_symbol(symbol)
    for q in sorted(QUOTES, key=len, reverse=True):
        if s.endswith(q) and len(s) > len(q):
            return q
    raise AdapterError("unknown quote for %s" % s)


def fees_for(symbol):
    q = quote_of(symbol)
    if q in USDT_LIKE:
        return {"buy": 0.004044, "sell": 0.004044, "slippage": SLIPPAGE_PER_SIDE}
    return {"buy": 0.002222, "sell": 0.004322, "slippage": SLIPPAGE_PER_SIDE}


def round_trip_pct(symbol):
    f = fees_for(symbol)
    return (f["buy"] + f["sell"] + 2 * f["slippage"]) * 100.0


def net_rr(entry, stop, target, symbol):
    """Net reward:risk after fee + slippage on both sides. >0 reward required."""
    e, s, t = (float(entry), float(stop), float(target))
    if not (e > 0 and 0 < s < e < t):
        raise AdapterError("geometry must satisfy 0 < stop < entry < target")
    f = fees_for(symbol)
    basis = e * (1 + f["buy"] + f["slippage"])
    risk = basis - s * (1 - f["sell"] - f["slippage"])
    reward = t * (1 - f["sell"] - f["slippage"]) - basis
    if risk <= 0:
        raise AdapterError("net risk is not positive")
    return {"net_reward": reward, "net_risk": risk, "net_rr": reward / risk,
            "round_trip_pct": round_trip_pct(symbol)}


def verify_symbol(symbol, refresh=False):
    """Validate against the venue book; cache result for a day."""
    pair = normalize_symbol(symbol)
    cache = os.path.join(PACKETS_DIR, "symbols.json")
    doc = read_json(cache, {}) or {}
    hit = doc.get(pair)
    if hit and not refresh and (time.time() - hit.get("ts", 0) < 86400):
        return hit.get("info")
    try:
        data = vision("/exchangeInfo?symbol=" + urllib.parse.quote(pair), timeout=12)
    except AdapterError:
        if hit and hit.get("info"):
            return hit["info"]
        raise
    syms = data.get("symbols") or [] if isinstance(data, dict) else []
    info = syms[0] if syms and syms[0].get("symbol") == pair else None
    doc[pair] = {"ts": time.time(), "info": info}
    write_json(cache, doc)
    return info
def floor_qty(symbol, qty):
    """Round a qty DOWN to the venue LOT_SIZE stepSize (never round up sizing)."""
    info = verify_symbol(symbol) or {}
    step = None
    for f in info.get("filters") or []:
        if f.get("filterType") == "LOT_SIZE" and f.get("stepSize"):
            step = float(f["stepSize"])
            break
    if not step or step <= 0:
        return round(float(qty), 8)
    from decimal import Decimal, ROUND_FLOOR
    d = Decimal(str(qty)) / Decimal(str(step))
    return float((d.to_integral_value(rounding=ROUND_FLOOR)) * Decimal(str(step)))


# ---------------------------------------------------------------------------
# indicators (ported subset of tradingview-mcp / AI Agent Lens concepts)
# ---------------------------------------------------------------------------

def ema(values, period):
    if not values or period <= 0:
        return []
    k = 2.0 / (period + 1)
    out = [float(values[0])]
    for v in values[1:]:
        out.append(float(v) * k + out[-1] * (1 - k))
    return out


def rsi(values, period=14):
    if len(values) < period + 1:
        return None
    gains, losses = [], []
    for i in range(1, len(values)):
        d = values[i] - values[i - 1]
        gains.append(max(d, 0.0))
        losses.append(max(-d, 0.0))
    ag = sum(gains[:period]) / period
    al = sum(losses[:period]) / period
    for i in range(period, len(gains)):
        ag = (ag * (period - 1) + gains[i]) / period
        al = (al * (period - 1) + losses[i]) / period
    if al == 0:
        return 100.0
    return 100.0 - 100.0 / (1.0 + ag / al)


def macd_hist(values, fast=12, slow=26, signal=9):
    if len(values) < slow + signal:
        return None
    ef, es = ema(values, fast), ema(values, slow)
    line = [a - b for a, b in zip(ef, es)]
    sig = ema(line, signal)
    return line[-1] - sig[-1]


def atr_pct(bars, period=14):
    if len(bars) < period + 1:
        return None
    tr = []
    for i in range(1, len(bars)):
        h, l, pc = float(bars[i][2]), float(bars[i][3]), float(bars[i - 1][4])
        tr.append(max(h - l, abs(h - pc), abs(l - pc)))
    a = sum(tr[:period]) / period
    for v in tr[period:]:
        a = (a * (period - 1) + v) / period
    last = float(bars[-1][4])
    return (a / last * 100.0) if last else None


def klines(symbol, interval="1h", limit=200):
    iv = TV_TO_VISION.get(str(interval).upper(), str(interval))
    data = vision("/klines?symbol=%s&interval=%s&limit=%d" % (
        urllib.parse.quote(normalize_symbol(symbol)), urllib.parse.quote(iv),
        max(1, min(1000, int(limit)))))
    if not isinstance(data, list):
        raise AdapterError("klines returned no bars for %s" % symbol)
    return [b for b in data if len(b) >= 6 and b[4] not in (None, "")]


def ohlcv_summary(bars):
    closes = [float(b[4]) for b in bars]
    vols = [float(b[5]) for b in bars]
    e20, e50 = ema(closes, 20), ema(closes, 50)
    return {
        "bars": len(bars),
        "last": closes[-1],
        "first": closes[0],
        "change_pct": (closes[-1] / closes[0] - 1) * 100 if closes[0] else None,
        "high": max(float(b[2]) for b in bars),
        "low": min(float(b[3]) for b in bars),
        "volume_mean": sum(vols) / len(vols) if vols else None,
        "volume_last": vols[-1] if vols else None,
        "ema20": e20[-1] if e20 else None,
        "ema50": e50[-1] if e50 else None,
        "stack": "BULLISH" if e20 and e50 and e20[-1] > e50[-1] else "BEARISH",
        "rsi14": rsi(closes),
        "macd_hist": macd_hist(closes),
        "atr14_pct": atr_pct(bars),
        "rvol": (vols[-1] / (sum(vols[-20:]) / len(vols[-20:]))) if len(vols) >= 20 else None,
    }
# ---------------------------------------------------------------------------
# packets / lens
# ---------------------------------------------------------------------------

def packet_summaries(symbol=None, timeframe=None):
    rows = []
    if not os.path.isfile(HISTORY_FILE):
        return rows
    try:
        with open(HISTORY_FILE, encoding="utf-8") as fh:
            for line in fh:
                if not line.strip():
                    continue
                try:
                    p = json.loads(line)
                except ValueError:
                    continue
                ps = str(p.get("symbol") or "").upper()
                pt = str(p.get("timeframe") or "")
                if symbol and normalize_symbol(ps) != normalize_symbol(symbol):
                    continue
                if timeframe and pt != str(timeframe):
                    continue
                rows.append(p)
    except OSError:
        return rows
    return rows


def latest_packet(symbol=None, timeframe=None):
    rows = packet_summaries(symbol, timeframe)
    return rows[-1] if rows else None


def lens_read(packet):
    """Deterministic reading of an ai-agent-lens/v1 packet. Never places orders."""
    scores = packet.get("scores") or {}
    reg = str(packet.get("regime") or "")
    event = str(packet.get("event") or "")
    long_s, short_s = scores.get("long"), scores.get("short")
    threshold = scores.get("threshold", 70)
    if reg == "HIGH_VOLATILITY":
        decision, why = "SKIP", "regime HIGH_VOLATILITY"
    elif event in ("LONG_SETUP", "SHORT_SETUP"):
        decision = "LONG" if event == "LONG_SETUP" else "SHORT"
        why = "indicator setup event"
    elif isinstance(long_s, (int, float)) and isinstance(short_s, (int, float)):
        if long_s >= threshold and long_s > short_s:
            decision, why = "LONG", "long score at/above threshold"
        elif short_s >= threshold and short_s > long_s:
            decision, why = "SHORT", "short score at/above threshold"
        else:
            decision, why = "WAIT", "no side at/above threshold"
    else:
        decision, why = "WAIT", "scores missing"
    trade = packet.get("trade") or {}
    return {
        "schema": packet.get("schema"),
        "symbol": packet.get("symbol"),
        "venue_symbol": normalize_symbol(packet.get("symbol")) if packet.get("symbol") else None,
        "timeframe": packet.get("timeframe"),
        "time": packet.get("time"),
        "event": event,
        "regime": reg,
        "decision": decision,
        "reason": why,
        "scores": {"long": long_s, "short": short_s, "threshold": threshold},
        "trend": packet.get("trend"),
        "momentum": packet.get("momentum"),
        "strength": packet.get("strength"),
        "structure": packet.get("structure"),
        "levels": packet.get("levels"),
        "candle": packet.get("candle"),
        "trade": trade,
        "note": "analisa, bukan order. Spot Tokocrypto tidak mendukung SHORT; gate + risiko + yes wajib sebelum order.",
    }


# ---------------------------------------------------------------------------
# command wrappers
# ---------------------------------------------------------------------------

def skill_args(paths):
    cfg = ["--orders", paths["orders"], "--risk", paths["risk"],
           "--config", paths["config"]]
    if "portfolio" in paths:
        cfg += ["--portfolio", paths["portfolio"]]
    if "state" in paths:
        cfg += ["--state", paths["state"]]
    return cfg


def default_paths():
    return {"orders": ORDERS_FILE, "risk": RISK_FILE, "config": CONFIG_FILE,
            "portfolio": PORTFOLIO_FILE, "state": BROKER_FILE}


def run_script(script, args, timeout=90):
    import subprocess
    cmd = [sys.executable or "python3", script] + list(args)
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True,
                              timeout=timeout, cwd=HOME)
    except subprocess.TimeoutExpired:
        raise AdapterError("script timed out: %s" % os.path.basename(script))
    return {
        "ok": proc.returncode == 0,
        "exit_code": proc.returncode,
        "stdout": proc.stdout.strip(),
        "stderr": proc.stderr.strip(),
    }


def order_fingerprint(order):
    key = "|".join(str(order.get(k, "")) for k in
                   ("symbol", "side", "qty", "quote_notional", "entry", "stop", "target"))
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]
