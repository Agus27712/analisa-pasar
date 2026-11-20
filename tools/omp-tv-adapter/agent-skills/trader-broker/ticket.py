#!/usr/bin/env python3
"""Order Executor for the Tokocrypto day-trader pack. Standard library only.

    python3 ticket.py show                     # every order, READY or not
    python3 ticket.py show --index 1 --json    # one ticket as JSON
    python3 ticket.py approve --index 1 --reply "yes"
    python3 ticket.py place --index 1          # send the approved order to the bridge
    python3 ticket.py result --index 1 --status placed --order-id 12345
    python3 ticket.py fill --symbol BTCUSDT --side buy --qty 0.001 --price 64500
    python3 ticket.py flatten --symbol BTCUSDT --qty 0.001
    python3 ticket.py log

Paper trading is the default and the only mode this tool will use unless the
user explicitly asks for live. Nothing is sent to the bridge until the user
typed an explicit yes for that one order AND trader-risk passed that exact
order today.

The bridge is the Analisa Pasar Agent Bridge at http://127.0.0.1:8765. Every
/api/v1/trading/* route needs the X-Agent-Token header (env ANALISA_PASAR_TOKEN
or the agent-bridge-token.txt the app installs next to the OMP extension).
"""

import argparse
import datetime
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_trader-lib"))
import venue  # noqa: E402

BRIDGE = "http://127.0.0.1:8765"
TOKEN_FILE = "agent-bridge-token.txt"
TOKEN_DIRS = ("extensions", "extension")
YES_WORDS = ("yes", "y", "ya", "ya,", "ok", "oke", "lanjut", "setuju")
DOUBT_WORDS = ("no", "not", "dont", "don't", "wait", "cancel", "stop", "but",
               "maybe", "later", "hold", "tidak", "jangan", "batal", "tunggu")
DEFAULTS = {"quote": "USDT", "paper": True, "max_order": 0.0}


class TicketError(Exception):
    pass


# ---------------------------------------------------------------------------
# bridge
# ---------------------------------------------------------------------------

def bridge_token():
    tok = (os.environ.get("ANALISA_PASAR_TOKEN") or "").strip()
    if tok:
        return tok
    home = os.environ.get("HOME") or "/data/data/com.termux/files/home"
    for d in TOKEN_DIRS:
        p = os.path.join(home, ".omp", "agent", d, "analisa-pasar", TOKEN_FILE)
        try:
            with open(p, encoding="utf-8") as fh:
                raw = fh.read().strip()
            if raw:
                return raw
        except OSError:
            continue
    return ""


def bridge_call(path, body=None, method=None, timeout=25):
    """Call the bridge. Returns (ok, payload_or_message)."""
    url = "%s/api/v1/trading/%s" % (BRIDGE, path)
    data = None
    headers = {"Accept": "application/json"}
    tok = bridge_token()
    if tok:
        headers["X-Agent-Token"] = tok
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method or ("POST" if data else "GET"))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        if exc.code == 401:
            return False, ("rejected 401: wrong/missing bridge token (%s or env ANALISA_PASAR_TOKEN). "
                           "Reinstall the extension from the app's settings." % TOKEN_FILE)
        try:
            return False, json.loads(raw).get("message") or ("HTTP %s" % exc.code)
        except ValueError:
            return False, "HTTP %s: %s" % (exc.code, raw[:200])
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return False, ("bridge unreachable at %s (%s). Is the analisa-pasar app running with "
                       "the Agent Bridge switch ON?" % (BRIDGE, exc))
    try:
        return True, json.loads(raw)
    except ValueError:
        return False, "unreadable bridge response: %s" % raw[:200]


def bridge_health():
    try:
        with urllib.request.urlopen("%s/health" % BRIDGE, timeout=8) as resp:
            return json.loads(resp.read().decode("utf-8", "replace"))
    except Exception:
        return None


# ---------------------------------------------------------------------------
# state
# ---------------------------------------------------------------------------

def load_config(path="trader/config.json"):
    cfg = dict(DEFAULTS)
    doc = venue.read_json(path, {}) or {}
    for k in DEFAULTS:
        if k in doc:
            cfg[k] = doc[k]
    return cfg


def read_json(path, what, required=True):
    doc = venue.read_json(path, None)
    if doc is None:
        if required:
            raise TicketError("no %s at %s" % (what, path))
        return None
    return doc


def write_json(path, data):
    venue.write_json(path, data)


def fingerprint(order):
    """Stable identity of an order, so a risk PASS cannot be reused elsewhere."""
    key = "|".join(str(order.get(k, "")) for k in
                   ("symbol", "side", "qty", "quote_notional", "entry", "stop", "target"))
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]


def today_utc(now=None):
    return (now or venue.utc_now()).strftime("%Y-%m-%d")


def load_state(path):
    doc = venue.read_json(path, None) or {"decisions": []}
    if not isinstance(doc.get("decisions"), list):
        doc["decisions"] = []
    return doc


def risk_verdict(risk, fp, today):
    """The risk PASS for this exact order, today. None when there is none."""
    if not risk:
        return None
    if risk.get("date") != today:
        return None
    for v in risk.get("verdicts", []):
        if v.get("order_fingerprint") == fp:
            return v
    return None


def status_of(order, risk, state, today):
    fp = fingerprint(order)
    rv = risk_verdict(risk, fp, today)
    if not rv or str(rv.get("verdict", "")).upper() != "PASS":
        return "BLOCKED", rv
    for d in state.get("decisions", []):
        if d.get("fingerprint") == fp and d.get("status") in ("placed", "filled"):
            return "DONE", rv
    return "READY", rv


# ---------------------------------------------------------------------------
# tickets
# ---------------------------------------------------------------------------

def max_loss(order, quote):
    """Loss at the stop, after fees and slippage, in the quote asset."""
    entry = float(order.get("entry") or 0)
    stop = float(order.get("stop") or 0)
    qty = float(order.get("qty") or 0)
    if entry <= 0 or stop <= 0 or qty <= 0 or stop >= entry:
        return None
    f = venue.fees(quote)
    cost = qty * entry * (1 + venue.SLIPPAGE_PER_SIDE) * (1 + f["buy"])
    back = qty * stop * (1 - venue.SLIPPAGE_PER_SIDE) * (1 - f["sell"])
    return cost - back


def plain(order, quote):
    lines = ["%s %s %s" % (order.get("side", "BUY").upper(), order.get("symbol"),
                           ("%.8g" % float(order["qty"])) if order.get("qty") else
                           ("%.2f %s" % (float(order.get("quote_notional") or 0), quote)))]
    if order.get("entry"):
        lines.append("  entry  %.8g" % float(order["entry"]))
    if order.get("stop"):
        lines.append("  stop   %.8g" % float(order["stop"]))
    if order.get("target"):
        lines.append("  target %.8g" % float(order["target"]))
    ml = max_loss(order, quote)
    if ml is not None:
        lines.append("  max loss at the stop: %.4f %s" % (ml, quote))
    if order.get("decided_by"):
        lines.append("  sized by: %s" % order["decided_by"])
    return "\n".join(lines)


def render(order, idx, total, cfg, risk, state, today):
    quote = order.get("quote") or cfg["quote"]
    st, rv = status_of(order, risk, state, today)
    out = ["[%d/%d] %s" % (idx, total, st)]
    out.append(plain(order, quote))
    if st == "BLOCKED":
        out.append("  risk: %s" % ((rv or {}).get("reason") or "no PASS recorded for this exact order today"))
    elif st == "READY":
        out.append("  risk: PASS (%s)" % ((rv or {}).get("reason") or "within limits"))
    return "\n".join(out)


def ticket_json(order, idx, cfg, risk, state, today):
    quote = order.get("quote") or cfg["quote"]
    st, rv = status_of(order, risk, state, today)
    return {
        "index": idx,
        "status": st,
        "fingerprint": fingerprint(order),
        "order": order,
        "max_loss": max_loss(order, quote),
        "risk": rv,
    }


# ---------------------------------------------------------------------------
# commands
# ---------------------------------------------------------------------------

def cmd_show(a, cfg):
    doc = read_json(a.orders, "orders file")
    orders = doc.get("orders") or []
    if not orders:
        print("no orders in %s" % a.orders)
        return 1
    risk = venue.read_json(a.risk, None)
    state = load_state(a.state)
    today = today_utc(venue.parse_now(a.now))
    if a.index is not None:
        if not 1 <= a.index <= len(orders):
            raise TicketError("--index must be 1..%d" % len(orders))
        t = ticket_json(orders[a.index - 1], a.index, cfg, risk, state, today)
        print(json.dumps(t, indent=2) if a.json else render(orders[a.index - 1], a.index, len(orders), cfg, risk, state, today))
        return 0
    if a.json:
        print(json.dumps([ticket_json(o, i + 1, cfg, risk, state, today) for i, o in enumerate(orders)], indent=2))
        return 0
    for i, o in enumerate(orders):
        print(render(o, i + 1, len(orders), cfg, risk, state, today))
        print()
    return 0


def parse_yes(reply, order):
    """A yes must be unambiguous: no doubt words, and a yes word present."""
    low = " ".join((reply or "").lower().split())
    if not low:
        return False, "empty reply"
    for w in DOUBT_WORDS:
        if w in low.split() or low.startswith(w + " "):
            return False, "the reply contains %r, which is not a yes" % w
    for w in YES_WORDS:
        if w in low.split():
            return True, "yes"
    return False, "no yes word in the reply"


def cmd_approve(a, cfg):
    doc = read_json(a.orders, "orders file")
    orders = doc.get("orders") or []
    if not orders:
        raise TicketError("no orders in %s" % a.orders)
    idx = a.index or 1
    if not 1 <= idx <= len(orders):
        raise TicketError("--index must be 1..%d" % len(orders))
    order = orders[idx - 1]
    ok, why = parse_yes(a.reply, order)
    state = load_state(a.state)
    today = today_utc(venue.parse_now(a.now))
    state.setdefault("decisions", []).append({
        "at": venue.iso(venue.utc_now()),
        "date": today,
        "symbol": order.get("symbol"),
        "side": order.get("side"),
        "fingerprint": fingerprint(order),
        "status": "approved" if ok else "denied",
        "reply": a.reply,
        "note": why,
    })
    write_json(a.state, state)
    print("%s: %s (%s)" % ("APPROVED" if ok else "DENIED", order.get("symbol"), why))
    if ok:
        print("next: python3 ticket.py place --index %d" % idx)
    return 0 if ok else 1


def cmd_place(a, cfg):
    doc = read_json(a.orders, "orders file")
    orders = doc.get("orders") or []
    if not orders:
        raise TicketError("no orders in %s" % a.orders)
    idx = a.index or 1
    if not 1 <= idx <= len(orders):
        raise TicketError("--index must be 1..%d" % len(orders))
    order = orders[idx - 1]
    fp = fingerprint(order)
    state = load_state(a.state)
    today = today_utc(venue.parse_now(a.now))

    # Three gates, all required: risk PASS today, an explicit yes, paper mode.
    risk = venue.read_json(a.risk, None)
    rv = risk_verdict(risk, fp, today)
    if not rv or str(rv.get("verdict", "")).upper() != "PASS":
        print("refused: trader-risk has no PASS for this exact order today. Run check.py first.")
        return 1
    approved = [d for d in state.get("decisions", [])
                if d.get("fingerprint") == fp and d.get("status") == "approved" and d.get("date") == today]
    if not approved:
        print("refused: no explicit yes recorded for this order today. Run approve first.")
        return 1
    if not cfg.get("paper", True) and not a.live:
        print("refused: config says paper=false but --live was not given. Paper is the default.")
        return 1

    health = bridge_health()
    if not health or not health.get("ok"):
        print("refused: the bridge is not answering at %s. Start the app with the Agent Bridge switch ON." % BRIDGE)
        return 1

    quote = order.get("quote") or cfg["quote"]
    body = {
        "side": order.get("side", "BUY").upper(),
        "symbol": venue.pair(order["symbol"]),
        "price": float(order.get("entry") or 0),
        "isSimulation": bool(cfg.get("paper", True)) and not a.live,
        "exchange": "TOKOCRYPTO",
    }
    if order.get("qty"):
        body["quantity"] = float(order["qty"])
    elif order.get("quote_notional"):
        body["quoteAmount"] = float(order["quote_notional"])
    else:
        print("refused: the order has neither qty nor quote_notional")
        return 1

    ok, res = bridge_call("buy" if body["side"] == "BUY" else "sell", body)
    if not ok:
        state["decisions"].append({"at": venue.iso(venue.utc_now()), "date": today,
                                   "symbol": body["symbol"], "side": body["side"],
                                   "fingerprint": fp, "status": "failed", "note": str(res)})
        write_json(a.state, state)
        print("failed: %s" % res)
        return 2
    if not res.get("success"):
        state["decisions"].append({"at": venue.iso(venue.utc_now()), "date": today,
                                   "symbol": body["symbol"], "side": body["side"],
                                   "fingerprint": fp, "status": "rejected",
                                   "note": res.get("message")})
        write_json(a.state, state)
        print("rejected: %s" % res.get("message"))
        return 2
    state["decisions"].append({
        "at": venue.iso(venue.utc_now()), "date": today,
        "symbol": body["symbol"], "side": body["side"], "fingerprint": fp,
        "status": "placed", "order_id": res.get("orderId"),
        "price": res.get("price"), "qty": res.get("quantity"),
        "simulation": res.get("isSimulation"), "quote": quote,
    })
    write_json(a.state, state)
    print("placed: %s %s qty %s at %s (order %s, %s)"
          % (body["side"], body["symbol"], res.get("quantity"), res.get("price"),
             res.get("orderId"), "paper" if res.get("isSimulation") else "LIVE"))
    return 0


def cmd_result(a, cfg):
    state = load_state(a.state)
    state.setdefault("decisions", []).append({
        "at": venue.iso(venue.utc_now()),
        "date": today_utc(venue.parse_now(a.now)),
        "symbol": a.symbol, "side": a.side, "status": a.status,
        "order_id": a.order_id, "note": a.note,
    })
    write_json(a.state, state)
    print("recorded: %s %s -> %s" % (a.side, a.symbol, a.status))
    return 0


def cmd_fill(a, cfg):
    """Record a fill in trader/portfolio.json (cash and units)."""
    doc = venue.read_json(a.portfolio, None) or {"quote": cfg["quote"], "cash": 0.0, "positions": {}}
    doc.setdefault("positions", {})
    sym = venue.pair(a.symbol)
    qty = float(a.qty)
    price = float(a.price)
    side = a.side.lower()
    pos = doc["positions"].get(sym) or {"qty": 0.0, "avg_price": 0.0, "opened_at": None}
    if side == "buy":
        new_qty = pos["qty"] + qty
        pos["avg_price"] = ((pos["qty"] * pos["avg_price"]) + qty * price) / new_qty if new_qty else 0.0
        pos["qty"] = new_qty
        pos["opened_at"] = pos.get("opened_at") or venue.iso(venue.utc_now())
        doc["cash"] = float(doc.get("cash") or 0.0) - qty * price
    else:
        pos["qty"] = pos["qty"] - qty
        doc["cash"] = float(doc.get("cash") or 0.0) + qty * price
        if pos["qty"] <= 1e-12:
            doc["positions"].pop(sym, None)
            pos = None
    if pos is not None:
        doc["positions"][sym] = pos
    write_json(a.portfolio, doc)
    print("fill recorded: %s %s %s at %s" % (side, sym, qty, price))
    return 0


def cmd_flatten(a, cfg):
    """Build a sell ticket that closes a symbol's open position."""
    doc = venue.read_json(a.portfolio, None) or {}
    pos = (doc.get("positions") or {}).get(venue.pair(a.symbol))
    qty = float(a.qty) if a.qty else (pos or {}).get("qty")
    if not qty:
        raise TicketError("no open position in %s" % a.symbol)
    order = {"symbol": venue.pair(a.symbol), "side": "SELL", "qty": qty,
             "quote": cfg["quote"], "decided_by": "flatten"}
    orders = venue.read_json(a.orders, None) or {"orders": []}
    orders.setdefault("orders", []).append(order)
    write_json(a.orders, orders)
    print("flatten ticket added: SELL %s %s" % (order["symbol"], qty))
    print("it still goes through trader-risk and your explicit yes.")
    return 0


def cmd_log(a, cfg):
    state = load_state(a.state)
    today = today_utc(venue.parse_now(a.now))
    rows = [d for d in state.get("decisions", []) if d.get("date") == today]
    if not rows:
        print("no decisions today (%s)" % today)
        return 1
    for d in rows:
        print("%s  %-6s %-12s %-9s %s"
              % (d.get("at", "")[11:19], d.get("side", ""), d.get("symbol", ""),
                 d.get("status", ""), d.get("note") or d.get("order_id") or ""))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description="Tokocrypto order executor (paper by default)")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("show")
    s.add_argument("--index", type=int)
    s.add_argument("--json", action="store_true")

    s = sub.add_parser("approve")
    s.add_argument("--index", type=int)
    s.add_argument("--reply", required=True)

    s = sub.add_parser("place")
    s.add_argument("--index", type=int)
    s.add_argument("--live", action="store_true", help="allow a real order when config paper=false")

    s = sub.add_parser("result")
    s.add_argument("--symbol", required=True)
    s.add_argument("--side", default="BUY")
    s.add_argument("--status", required=True)
    s.add_argument("--order-id")
    s.add_argument("--note")

    s = sub.add_parser("fill")
    s.add_argument("--symbol", required=True)
    s.add_argument("--side", required=True, choices=["buy", "sell"])
    s.add_argument("--qty", required=True)
    s.add_argument("--price", required=True)

    s = sub.add_parser("flatten")
    s.add_argument("--symbol", required=True)
    s.add_argument("--qty")

    sub.add_parser("log")

    for sp in sub.choices.values():
        sp.add_argument("--orders", default="trader/orders.json")
        sp.add_argument("--risk", default="trader/state/risk.json")
        sp.add_argument("--state", default="trader/state/broker.json")
        sp.add_argument("--portfolio", default="trader/portfolio.json")
        sp.add_argument("--config", default="trader/config.json")
        sp.add_argument("--now", help="pretend it is this UTC time")
    a = p.parse_args(argv)
    cfg = load_config(a.config)
    try:
        return {"show": cmd_show, "approve": cmd_approve, "place": cmd_place,
                "result": cmd_result, "fill": cmd_fill, "flatten": cmd_flatten,
                "log": cmd_log}[a.cmd](a, cfg)
    except TicketError as exc:
        print("ticket error: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
