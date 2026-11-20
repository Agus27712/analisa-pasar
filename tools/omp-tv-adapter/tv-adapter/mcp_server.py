#!/usr/bin/env python3
"""MCP stdio server: TradingView concepts -> Tokocrypto via analisa-pasar.

Ported subset of tradesdontlie/tradingview-mcp tool surface (quote, ohlcv,
chart state, study values) re-pointed at data-api.binance.vision plus the
local Analisa Pasar Agent Bridge, plus AI Agent Lens packet tools from
roan-maverick/tradingview-indicator-for-ai-agents.

Zero dependencies. Registers in ~/.omp/agent/mcp.json:

  {"tv-tokocrypto": {"command": "python3",
                     "args": ["/data/data/com.termux/files/home/trader/tv-adapter/mcp_server.py"]}}

Hard rules baked into the tools:
  * packets/signals are analysis, never orders;
  * new entries must pass trader-risk + trader-timemachine --gate first;
  * the broker sends nothing until an explicit per-order user yes;
  * paper is the default; live needs config paper=false AND bridge Mode Real.
"""

import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common  # noqa: E402

SERVER_NAME = "tv-tokocrypto"
SERVER_VERSION = "1.0.0"
PROTOCOL_VERSION = "2025-06-18"


def ok(**kw):
    kw.setdefault("ok", True)
    return kw


def fail(message, **kw):
    return {"ok": False, "error": message, **kw}


# ---------------------------------------------------------------------------
# tools
# ---------------------------------------------------------------------------

def t_status(_args):
    out = {}
    try:
        out["bridge_health"] = common.bridge_health()
        out["bridge_ok"] = True
    except common.AdapterError as exc:
        out["bridge_ok"] = False
        out["bridge_error"] = str(exc)
    try:
        status = common.bridge_trading_status()
        out["trading_status"] = {
            k: status.get(k) for k in
            ("exchange", "isBridgeRunning", "isRealBuyEnabled", "isPinAuthenticated",
             "isSimulationAvailable")
        }
    except common.AdapterError as exc:
        out["trading_status_error"] = str(exc)

    cfg = common.read_json(common.CONFIG_FILE, {}) or {}
    paper_cfg = bool(cfg.get("paper", True))
    real_enabled = bool((out.get("trading_status") or {}).get("isRealBuyEnabled"))
    out["mode"] = "paper" if paper_cfg else "live-capable"
    out["live_gates"] = {
        "config_paper_true": paper_cfg,
        "bridge_real_enabled": real_enabled,
        "explicit_user_yes_per_order": True,
        "risk_pass_today": True,
        "allowed_now": (not paper_cfg) and real_enabled,
        "to_enable_live": ("set trader/config.json paper=false "
                           "AND app Analisa Pasar: Mode Real on + PIN session; "
                           "then tv_broker_place live=true confirm_live=LIVE"),
    }
    hist = os.path.join(common.PACKETS_DIR, "history.jsonl")
    latest = common.latest_packet()
    out["lens"] = {
        "packets_on_disk": latest is not None,
        "history_age_s": common.age_seconds(hist),
        "latest": latest and {k: latest.get(k) for k in
                              ("event", "symbol", "timeframe", "time", "state_code")},
    }
    out["limits"] = {
        k: cfg.get(k) for k in
        ("quote", "budget", "max_loss_per_trade", "max_order",
         "max_trades_per_day", "min_reward_risk")
    }
    out["next_steps"] = [
        "tv_lens_latest — baca paket kandle terbaru",
        "tv_ohlcv / tv_engine_analysis — konfirmasi live state",
        "tv_gate — first-passage barrier test (wajib sebelum entry)",
        "tv_prepare_order — tulis ticket (belum terkirim)",
        "tv_risk_check -> tv_broker_show -> user yes -> tv_broker_place",
    ]
    return ok(status=out)


def t_quote(args):
    pair = common.normalize_symbol(args["symbol"])
    data = common.vision("/ticker/24hr?symbol=" + pair)
    return ok(symbol=pair, last=float(data["lastPrice"]),
              change_pct=float(data.get("priceChangePercent") or 0),
              high_24h=float(data.get("highPrice") or 0),
              low_24h=float(data.get("lowPrice") or 0),
              quote_volume_24h=float(data.get("quoteVolume") or 0),
              source="data-api.binance.vision")


def t_ohlcv(args):
    pair = common.normalize_symbol(args["symbol"])
    interval = str(args.get("interval") or "1h")
    limit = int(args.get("limit") or 100)
    bars = common.klines(pair, interval=interval, limit=limit)
    summary = common.ohlcv_summary(bars)
    tv_interval = common.VISION_TO_TV.get(common.TV_TO_VISION.get(interval.upper(), interval),
                                          interval)
    if args.get("raw"):
        return ok(symbol=pair, interval=tv_interval, bars=bars)
    tail = [[b[0], float(b[1]), float(b[2]), float(b[3]), float(b[4]), float(b[5])]
            for b in bars[-10:]]
    return ok(symbol=pair, interval=tv_interval, summary=summary,
              last10=tail, source="data-api.binance.vision")


def t_chart_state(args):
    pair = common.normalize_symbol(args["symbol"])
    interval = str(args.get("interval") or "1h")
    bars = common.klines(pair, interval=interval, limit=200)
    summary = common.ohlcv_summary(bars)
    quote = common.vision("/ticker/24hr?symbol=" + pair)
    state = {
        "symbol": pair,
        "interval": interval,
        "price": float(quote["lastPrice"]),
        "change_pct_24h": float(quote.get("priceChangePercent") or 0),
        "study_values": {k: summary.get(k) for k in
                         ("ema20", "ema50", "stack", "rsi14", "macd_hist",
                          "atr14_pct", "rvol")},
        "lens": common.lens_read(common.latest_packet(pair) or {}) if common.latest_packet(pair) else None,
    }
    if args.get("with_engine"):
        try:
            state["engine"] = common.bridge_get(
                "/api/v1/engine/analysis?symbol=" + pair, auth=False, timeout=15)
        except common.AdapterError as exc:
            state["engine_error"] = str(exc)
    return ok(state=state)


def t_lens_latest(args):
    rows = common.packet_summaries(args.get("symbol"), args.get("timeframe"))
    if not rows:
        return fail("no AI Agent Lens packet on disk yet",
                    hint="start receiver.py, point the TradingView alert webhook at "
                         "http://<host>:8080/webhook?token=... (paid plan + 2FA)")
    packet = rows[-1]
    age = None
    if packet.get("_received_at"):
        try:
            import datetime as dt
            rec = dt.datetime.strptime(packet["_received_at"], "%Y-%m-%dT%H:%M:%SZ")
            rec = rec.replace(tzinfo=dt.timezone.utc)
            age = round((dt.datetime.now(dt.timezone.utc) - rec).total_seconds(), 1)
        except ValueError:
            age = None
    return ok(packet=common.lens_read(packet), age_s=age,
              raw_packet=args.get("include_raw") and packet or None)


def t_lens_history(args):
    rows = common.packet_summaries(args.get("symbol"), args.get("timeframe"))
    limit = max(1, min(200, int(args.get("limit") or 50)))
    out = []
    for p in rows[-limit:]:
        out.append({
            "symbol": p.get("symbol"), "timeframe": p.get("timeframe"),
            "time": p.get("time"), "event": p.get("event"),
            "regime": p.get("regime"),
            "scores": (p.get("scores") or {}).get("long"),
            "state_code": p.get("state_code"),
        })
    return ok(count=len(out), packets=out)


def t_engine_analysis(args):
    pair = common.normalize_symbol(args["symbol"])
    data = common.bridge_get("/api/v1/engine/analysis?symbol=" + pair,
                             auth=False, timeout=20)
    return ok(analysis=data)


def t_prepare_order(args):
    """Write a BUY ticket for trader-risk + trader-broker. Sends nothing."""
    pair = common.normalize_symbol(args["symbol"])
    side = str(args.get("side") or "BUY").upper()
    if side in ("LONG",):
        side = "BUY"
    if side != "BUY":
        return fail("tv_prepare_order only writes spot BUY entries; use tv_flatten for exits")
    if str(args.get("direction") or "").upper() == "SHORT":
        return fail("spot Tokocrypto cannot short; use WAIT/SKIP for SHORT setups")

    entry = float(args["entry"])
    stop = float(args["stop"])
    target = float(args["target"])
    try:
        rr = common.net_rr(entry, stop, target, pair)
    except common.AdapterError as exc:
        return fail(str(exc))

    cfg = common.read_json(common.CONFIG_FILE, {}) or {}
    required = max(2.0, float(cfg.get("min_reward_risk") or 0))
    if rr["net_rr"] < required:
        return fail("net RR %.3f < required %.2f after fees: ticket not written" %
                    (rr["net_rr"], required), **rr)

    qty = float(args["qty"]) if args.get("qty") else None
    quote_amount = float(args["quote_amount"]) if args.get("quote_amount") else None
    if qty is None and quote_amount is None:
        return fail("need quote_amount or qty")
    if qty is None:
        qty = quote_amount / entry
        qty = common.floor_qty(pair, qty)
        quote_amount = qty * entry
    elif quote_amount is None:
        qty = common.floor_qty(pair, qty)
        quote_amount = qty * entry
    if qty <= 0:
        return fail("qty rounded to zero at venue stepSize")
    if quote_amount > float(cfg.get("max_order") or cfg.get("budget") or 0) + 1e-9:
        return fail("quote_notional %.4f over max_order %.4f" %
                    (quote_amount, float(cfg.get("max_order") or 0)))
    loss = rr["net_risk"] * qty
    if loss > float(cfg.get("max_loss_per_trade") or 0) + 1e-9:
        return fail("loss at stop %.4f > max_loss_per_trade %.4f" %
                    (loss, float(cfg.get("max_loss_per_trade") or 0)),
                    loss_at_stop=loss)

    warnings = []
    try:
        px = float(common.vision("/ticker/price?symbol=" + pair)["price"])
        dev = abs(entry / px - 1) * 100
        if dev > 1.0:
            warnings.append("entry deviates %.2f%% from live price %.8g" % (dev, px))
    except common.AdapterError as exc:
        warnings.append("live price unavailable: %s" % exc)

    packet_time = args.get("packet_time")
    if packet_time:
        try:
            import datetime as dt
            pt = dt.datetime.strptime(str(packet_time), "%Y-%m-%dT%H:%M:%SZ")
            age = (dt.datetime.now(dt.timezone.utc)
                   - pt.replace(tzinfo=dt.timezone.utc)).total_seconds()
            if age > 900:
                warnings.append("packet is %.0f s old; refresh before entry" % age)
        except ValueError:
            warnings.append("packet_time not ISO Z; ignored")

    order = {
        "symbol": pair,
        "side": side,
        "qty": round(qty, 12),
        "quote_notional": round(quote_amount, 8),
        "quote": common.quote_of(pair),
        "entry": entry,
        "stop": stop,
        "target": target,
        "decided_by": "tv-lens",
        "note": str(args.get("note") or "tv-lens packet").strip(),
    }
    fp = common.order_fingerprint(order)
    doc = common.read_json(common.ORDERS_FILE, {"orders": []}) or {"orders": []}
    doc.setdefault("orders", [])
    for i, o in enumerate(doc["orders"]):
        if common.order_fingerprint(o) == fp:
            return ok(already_queued=True, index=i + 1, fingerprint=fp, order=order,
                      net_rr=rr, warnings=warnings,
                      next_steps=["tv_risk_check", "tv_broker_show --index %d" % (i + 1)])
    doc["orders"].append(order)
    common.write_json(common.ORDERS_FILE, doc)
    idx = len(doc["orders"])
    return ok(already_queued=False, index=idx, fingerprint=fp, order=order,
              net_rr=rr, warnings=warnings,
              next_steps=[
                  "tv_risk_check",
                  "tv_broker_show --index %d" % idx,
                  "tv_broker_approve --index %d --reply <exact user yes>" % idx,
                  "tv_broker_place --index %d   (paper default)" % idx,
              ])


def t_risk_check(args):
    paths = common.default_paths()
    risk_args = ["--json", "--orders", paths["orders"], "--risk", paths["risk"],
                 "--portfolio", paths["portfolio"], "--config", paths["config"]]
    res = common.run_script(common.RISK_SCRIPT, risk_args, timeout=90)
    parsed = None
    if res["stdout"].startswith("{") or res["stdout"].startswith("["):
        try:
            parsed = json.loads(res["stdout"])
        except ValueError:
            parsed = None
    return ok(**res, verdicts=parsed, note="PASS != edge; tetap butuh gate + user yes")


def t_broker_show(args):
    idx = ["--index", str(args["index"])] if args.get("index") else []
    res = common.run_script(common.BROKER_SCRIPT,
                            ["show", "--json"] + idx + common.skill_args(common.default_paths()),
                            timeout=30)
    return ok(**res)


def t_broker_approve(args):
    if not str(args.get("reply") or "").strip():
        return fail("reply (the user's exact words) is required")
    idx = str(args.get("index") or 1)
    res = common.run_script(common.BROKER_SCRIPT,
                            ["approve", "--index", idx, "--reply", str(args["reply"])]
                            + common.skill_args(common.default_paths()), timeout=30)
    return ok(**res)


def t_broker_place(args):
    idx = str(args.get("index") or 1)
    live = bool(args.get("live"))
    cfg = common.read_json(common.CONFIG_FILE, {}) or {}
    try:
        status = common.bridge_trading_status()
        health = common.bridge_health()
    except common.AdapterError as exc:
        return fail("bridge unavailable: %s" % exc)
    if not health.get("ok"):
        return fail("bridge health not ok; start Analisa Pasar with Agent Bridge ON")
    if live:
        if bool(cfg.get("paper", True)):
            return fail("config paper=true: live disabled. Only flip it to false "
                        "together with app Mode Real + PIN session.")
        if str(args.get("confirm_live") or "") != "LIVE":
            return fail("live order needs confirm_live='LIVE' from the user")
        if not status.get("isRealBuyEnabled") or not status.get("isPinAuthenticated"):
            return fail("app Mode Real / PIN session not ready",
                        isRealBuyEnabled=status.get("isRealBuyEnabled"),
                        isPinAuthenticated=status.get("isPinAuthenticated"))
    cmd = ["place", "--index", idx] + (["--live"] if live else [])
    res = common.run_script(common.BROKER_SCRIPT,
                            cmd + common.skill_args(common.default_paths()), timeout=40)
    return ok(mode="LIVE" if live else "PAPER", **res)


def t_flatten(args):
    cmd = ["flatten", "--symbol", common.normalize_symbol(args["symbol"])]
    if args.get("qty"):
        cmd += ["--qty", str(float(args["qty"]))]
    res = common.run_script(common.BROKER_SCRIPT,
                            cmd + common.skill_args(common.default_paths()), timeout=30)
    return ok(**res, next_steps=["tv_risk_check", "tv_broker_approve",
                                 "tv_broker_place (SELL always allowed by day limits)"])


def t_order_status(args):
    pair = common.normalize_symbol(args["symbol"]) if args.get("symbol") else ""
    q = "?symbol=%s&isSimulation=%s&exchange=TOKOCRYPTO" % (
        pair, "true" if args.get("is_simulation", True) else "false")
    data = common.bridge_get(
        "/api/v1/trading/orders/%s%s" % (args["orderId"], q), auth=True, timeout=10)
    return ok(order=data)


def t_gate(args):
    pair = common.normalize_symbol(args["symbol"])
    interval = str(args.get("interval") or "1h")
    default_horizon = {"1m": 2880, "5m": 576, "15m": 192, "1h": 24, "4h": 6}
    horizon = int(args.get("horizon") or default_horizon.get(interval, 24))
    cmd = ["run", "--gate", "--symbol", pair,
           "--entry", str(float(args["entry"])),
           "--stop", str(float(args["stop"])),
           "--target", str(float(args["target"])),
           "--interval", interval, "--horizon", str(horizon),
           "--config", common.CONFIG_FILE, "--json"]
    res = common.run_script(common.TIMEMACHINE_SCRIPT, cmd, timeout=300)
    verdict = None
    for line in reversed(res["stdout"].splitlines()):
        if "verdict:" in line.lower():
            verdict = line.split(":", 1)[-1].strip()
            break
    return ok(**res, verdict=verdict, passed=(res["exit_code"] == 0),
              note="exit 1 = gate gagal = NO TRADE")


def t_journal(args):
    rows = []
    if os.path.isfile(common.PAPER_JOURNAL):
        try:
            with open(common.PAPER_JOURNAL, encoding="utf-8") as fh:
                by_id = {}
                for line in fh:
                    if not line.strip():
                        continue
                    try:
                        t = json.loads(line)
                    except ValueError:
                        continue
                    if t.get("tradeId"):
                        by_id[t["tradeId"]] = t
                rows = list(by_id.values())
        except OSError as exc:
            return fail("journal unreadable: %s" % exc)
    sym = common.normalize_symbol(args["symbol"]) if args.get("symbol") else None
    if sym:
        rows = [r for r in rows if r.get("symbol") == sym]
    open_rows = [r for r in rows if r.get("closedAt") is None]
    closed = [r for r in rows if r.get("closedAt") is not None]
    pnls = [r.get("pnlPct") for r in closed if isinstance(r.get("pnlPct"), (int, float))]
    wins = [p for p in pnls if p > 0]
    return ok(
        open_count=len(open_rows),
        closed_count=len(closed),
        win_rate_pct=round(100 * len(wins) / len(pnls), 2) if pnls else None,
        avg_pnl_pct=round(sum(pnls) / len(pnls), 4) if pnls else None,
        open_trades=[{k: r.get(k) for k in
                      ("tradeId", "symbol", "entryPrice", "quantity",
                       "targetNetPct", "stopLossPct", "openedAt")}
                     for r in open_rows[-10:]],
        last_closed=[{k: r.get(k) for k in
                      ("tradeId", "symbol", "pnlPct", "exitReason", "closedAt")}
                     for r in closed[-10:]],
    )


TOOLS = [
    {"name": "tv_status",
     "description": "Kesehatan bridge analisa-pasar, mode paper/live, dan usia paket AI Agent Lens terbaru.",
     "inputSchema": {"type": "object", "properties": {}},
     "handler": t_status},
    {"name": "tv_quote",
     "description": "Ticker harga 24h dari data-api.binance.vision (buku identik Tokocrypto; host app TIDAK dipanggil).",
     "inputSchema": {"type": "object", "required": ["symbol"],
                     "properties": {"symbol": {"type": "string", "description": "BTCUSDT / BINANCE:BTCUSDT / TOKOCRYPTO:BTCUSDT"}}},
     "handler": t_quote},
    {"name": "tv_ohlcv",
     "description": "Klines + summary (EMA20/50, RSI14, MACD hist, ATR, RVOL) — port dari data_get_ohlcv tradingview-mcp.",
     "inputSchema": {"type": "object", "required": ["symbol"],
                     "properties": {"symbol": {"type": "string"},
                                    "interval": {"type": "string", "default": "1h", "description": "TV code: 1/5/15/60/240/D atau 1m/1h"},
                                    "limit": {"type": "integer", "default": 100, "maximum": 500},
                                    "raw": {"type": "boolean", "default": False}}},
     "handler": t_ohlcv},
    {"name": "tv_chart_state",
     "description": "State chart sekarang: harga + study values + paket lens (+ engine analysis opsional).",
     "inputSchema": {"type": "object", "required": ["symbol"],
                     "properties": {"symbol": {"type": "string"},
                                    "interval": {"type": "string", "default": "1h"},
                                    "with_engine": {"type": "boolean", "default": False}}},
     "handler": t_chart_state},
    {"name": "tv_lens_latest",
     "description": "Paket AI Agent Lens terbaru (kandle tertutup) + pembacaan skor/regime. Analisa saja, bukan order.",
     "inputSchema": {"type": "object",
                     "properties": {"symbol": {"type": "string"},
                                    "timeframe": {"type": "string"},
                                    "include_raw": {"type": "boolean", "default": False}}},
     "handler": t_lens_latest},
    {"name": "tv_lens_history",
     "description": "Riwayat paket AI Agent Lens dari history.jsonl (event/skor/regime per kandle).",
     "inputSchema": {"type": "object",
                     "properties": {"symbol": {"type": "string"},
                                    "timeframe": {"type": "string"},
                                    "limit": {"type": "integer", "default": 50, "maximum": 200}}},
     "handler": t_lens_history},
    {"name": "tv_engine_analysis",
     "description": "Analisis 3 engine (scalping/intraday/swing) + indikator dari bridge analisa-pasar untuk satu pair.",
     "inputSchema": {"type": "object", "required": ["symbol"],
                     "properties": {"symbol": {"type": "string"}}},
     "handler": t_engine_analysis},
    {"name": "tv_prepare_order",
     "description": "Tulis ticket BUY ke trader/orders.json: geometri net RR>=2 setelah fee, qty/budget. TIDAK mengirim order — lanjut risk/broker.",
     "inputSchema": {"type": "object",
                     "required": ["symbol", "entry", "stop", "target"],
                     "properties": {"symbol": {"type": "string"},
                                    "side": {"type": "string", "default": "BUY"},
                                    "entry": {"type": "number"},
                                    "stop": {"type": "number"},
                                    "target": {"type": "number"},
                                    "quote_amount": {"type": "number", "description": "nominal quote (USDT)"},
                                    "qty": {"type": "number"},
                                    "packet_time": {"type": "string"},
                                    "note": {"type": "string"}}},
     "handler": t_prepare_order},
    {"name": "tv_risk_check",
     "description": "Jalankan trader-risk check.py terhadap orders.json (PASS/BLOCK per fingerprint, hari ini).",
     "inputSchema": {"type": "object", "properties": {}},
     "handler": t_risk_check},
    {"name": "tv_broker_show",
     "description": "Tampilkan ticket + status broker (READY/BLOCKED/DONE).",
     "inputSchema": {"type": "object", "properties": {"index": {"type": "integer"}}},
     "handler": t_broker_show},
    {"name": "tv_broker_approve",
     "description": "Rekam yes eksplisit user untuk SATU ticket (tidak mengirim apa pun).",
     "inputSchema": {"type": "object", "required": ["reply"],
                     "properties": {"index": {"type": "integer"},
                                    "reply": {"type": "string", "description": "kata yes user apa adanya"}}},
     "handler": t_broker_approve},
    {"name": "tv_broker_place",
     "description": "Kirim ticket ke app bridge (paper default). live hanya bila config paper=false + Mode Real + confirm_live='LIVE'.",
     "inputSchema": {"type": "object",
                     "properties": {"index": {"type": "integer"},
                                    "live": {"type": "boolean", "default": False},
                                    "confirm_live": {"type": "string"}}},
     "handler": t_broker_place},
    {"name": "tv_flatten",
     "description": "Buat ticket SELL untuk menutup posisi (tetap lewat risk + yes user).",
     "inputSchema": {"type": "object", "required": ["symbol"],
                     "properties": {"symbol": {"type": "string"}, "qty": {"type": "number"}}},
     "handler": t_flatten},
    {"name": "tv_order_status",
     "description": "Cek status order di bridge (paper/live) berdasarkan order ID.",
     "inputSchema": {"type": "object", "required": ["orderId"],
                     "properties": {"orderId": {"type": "string"},
                                    "symbol": {"type": "string"},
                                    "is_simulation": {"type": "boolean", "default": True}}},
     "handler": t_order_status},
    {"name": "tv_gate",
     "description": "First-passage barrier test (trader-timemachine --gate) dengan entry/stop/target EKSPLISIT. Exit 1 = NO TRADE.",
     "inputSchema": {"type": "object",
                     "required": ["symbol", "entry", "stop", "target"],
                     "properties": {"symbol": {"type": "string"},
                                    "entry": {"type": "number"},
                                    "stop": {"type": "number"},
                                    "target": {"type": "number"},
                                    "interval": {"type": "string", "default": "1h"},
                                    "horizon": {"type": "integer"}}},
     "handler": t_gate},
    {"name": "tv_journal",
     "description": "Ringkasan jurnal paper app (win rate, avg PnL, posisi terbuka).",
     "inputSchema": {"type": "object",
                     "properties": {"symbol": {"type": "string"}}},
     "handler": t_journal},
]

TOOL_MAP = {t["name"]: t for t in TOOLS}
INSTRUCTIONS = (
    "Alur TradingView AI Agent Lens -> Tokocrypto via Analisa Pasar: "
    "(1) tv_lens_latest/tv_ohlcv/tv_engine_analysis untuk analisa; "
    "(2) tv_gate wajib — net EV >0 atau NO TRADE; "
    "(3) tv_prepare_order menulis ticket (belum terkirim); "
    "(4) tv_risk_check harus PASS; "
    "(5) tv_broker_approve hanya dari yes eksplisit user untuk ticket itu; "
    "(6) tv_broker_place paper default; live perlu config paper=false + app Mode Real + confirm_live='LIVE'. "
    "Paket/sinyal tidak pernah jadi order otomatis. Spot Tokocrypto tidak bisa short."
)


# ---------------------------------------------------------------------------
# stdio JSON-RPC (newline-delimited)
# ---------------------------------------------------------------------------

def send(msg):
    sys.stdout.write(json.dumps(msg, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def reply(req_id, result):
    send({"jsonrpc": "2.0", "id": req_id, "result": result})


def reply_err(req_id, code, message):
    send({"jsonrpc": "2.0", "id": req_id, "error": {"code": code, "message": message}})


def handle(req):
    method = req.get("method")
    rid = req.get("id")
    params = req.get("params") or {}
    if method == "initialize":
        return reply(rid, {
            "protocolVersion": params.get("protocolVersion") or PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            "instructions": INSTRUCTIONS,
        })
    if method in ("notifications/initialized", "notifications/cancelled", "initialized"):
        return
    if method == "ping":
        return reply(rid, {})
    if method == "tools/list":
        return reply(rid, {"tools": [{k: t[k] for k in ("name", "description", "inputSchema")}
                                     for t in TOOLS]})
    if method == "tools/call":
        name = params.get("name")
        tool = TOOL_MAP.get(name)
        if not tool:
            return reply_err(rid, -32602, "unknown tool: %s" % name)
        args = params.get("arguments") or {}
        try:
            result = tool["handler"](args)
            text = json.dumps(result, ensure_ascii=False, default=str)
            is_err = not result.get("ok", True)
            return reply(rid, {"content": [{"type": "text", "text": text}],
                               "isError": is_err})
        except common.AdapterError as exc:
            return reply(rid, {"content": [{"type": "text",
                                            "text": json.dumps({"ok": False, "error": str(exc)})}],
                               "isError": True})
        except Exception as exc:  # noqa: BLE001 — surface, never crash the server
            return reply(rid, {"content": [{"type": "text",
                                            "text": json.dumps({"ok": False, "error": repr(exc)})}],
                               "isError": True})
    if method == "resources/list":
        return reply(rid, {"resources": []})
    if method == "prompts/list":
        return reply(rid, {"prompts": []})
    if rid is None:
        return
    return reply_err(rid, -32601, "method not found: %s" % method)


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except ValueError:
            continue
        if isinstance(req, dict):
            handle(req)


if __name__ == "__main__":
    main()
