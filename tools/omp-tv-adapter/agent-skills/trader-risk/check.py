#!/usr/bin/env python3
"""Risk Manager for the Tokocrypto day-trader pack. Standard library only.

    python3 check.py                          # trader/orders.json -> trader/state/risk.json
    python3 check.py --json
    python3 check.py --no-write               # look without recording
    python3 check.py --now "2026-11-17 04:15" # pin the clock (UTC)

Every order written by trader-budget (or trader-broker's flatten) passes
through here before the broker may show it to the user. A BLOCK is final for
that order. Getting OUT is never blocked by a day limit: a sell that only
reduces a position is checked for format, quote and short sales only.

A PASS is a rules check, not a recommendation, and not the user's yes.
"""

import argparse
import hashlib
import json
import math
import os
import sys
from decimal import Decimal

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_trader-lib"))
import venue  # noqa: E402

DEFAULTS = {
    "quote": "USDT",
    "budget": 100.0,
    "max_loss_per_trade": 2.0,
    "max_position_pct": 25.0,
    "min_reward_risk": 1.5,
    "max_trades_per_day": 3,
    "daily_loss_limit": 6.0,
    "cash_buffer_pct": 10.0,
    "no_entries_before": "00:15",
    "no_entries_after": "23:30",
    "blocked": [],
    "paper": True,
}

PRICE_MAX_AGE_S = 900.0   # a price older than this cannot raise the entry


def fingerprint(order):
    """The identity trader-broker computes, so a PASS binds to one exact order."""
    key = "|".join(str(order.get(k, "")) for k in
                   ("symbol", "side", "qty", "quote_notional", "entry", "stop", "target"))
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]


def num(value, name, zero=False):
    if isinstance(value, bool) or value is None:
        raise ValueError("malformed %s" % name)
    try:
        n = float(value)
    except (TypeError, ValueError):
        raise ValueError("malformed %s" % name)
    if not math.isfinite(n) or n < 0 or (n == 0 and not zero):
        raise ValueError("malformed %s" % name)
    return n


def minute_of(text, name):
    if text is None:
        return None
    try:
        hh, mm = str(text).split(":")
        h, m = int(hh), int(mm)
    except (ValueError, AttributeError):
        raise ValueError("malformed %s" % name)
    if not (0 <= h < 24 and 0 <= m < 60):
        raise ValueError("malformed %s" % name)
    return h * 60 + m


def load_config(path):
    cfg = dict(DEFAULTS)
    doc = venue.read_json(path, {}) or {}
    if not isinstance(doc, dict):
        raise ValueError("malformed config")
    for k in DEFAULTS:
        if k in doc:
            cfg[k] = doc[k]
    return cfg


def fresh_price(sym, entry, offline, now):
    """(price, source, age_seconds). trader/prices.json first, then the venue."""
    doc = venue.read_json("trader/prices.json", {}) or {}
    row = (doc.get("quotes") or {}).get(sym)
    if isinstance(row, dict) and row.get("price"):
        try:
            at = venue.parse_now(row.get("as_of") or "")
        except venue.VenueError:
            at = None
        if at is not None:
            age = (now - at).total_seconds()
            if 0 <= age <= PRICE_MAX_AGE_S:
                return float(row["price"]), "trader/prices.json", age
    tick = venue.ticker24h(sym, offline=offline)
    return float(tick["price"]), ("offline fixture" if offline else "venue ticker24h"), None


def main(argv=None):
    p = argparse.ArgumentParser(description="Tokocrypto risk manager")
    p.add_argument("mode", nargs="?", default="check", choices=["check"])
    p.add_argument("--orders", default="trader/orders.json")
    p.add_argument("--risk", default="trader/state/risk.json")
    p.add_argument("--portfolio", default="trader/portfolio.json")
    p.add_argument("--config", default="trader/config.json")
    p.add_argument("--offline", metavar="DIR", help="read saved JSON from DIR instead of the network")
    p.add_argument("--now", help='pretend it is this UTC time, e.g. "2026-11-17 04:15"')
    p.add_argument("--no-write", action="store_true", help="print verdicts without recording them")
    p.add_argument("--json", action="store_true")
    a = p.parse_args(argv)

    try:
        cfg = load_config(a.config)
        now = venue.parse_now(a.now)
        date = now.strftime("%Y-%m-%d")
        stamp = venue.iso(now)
        quote = str(cfg["quote"]).upper()

        budget = num(cfg["budget"], "budget")
        max_loss = num(cfg["max_loss_per_trade"], "max_loss_per_trade")
        daily_limit = num(cfg["daily_loss_limit"], "daily_loss_limit", zero=True)
        trades_limit = int(num(cfg["max_trades_per_day"], "max_trades_per_day", zero=True))
        order_limit = int(num(cfg.get("max_orders_per_day", trades_limit), "max_orders_per_day", zero=True))
        min_rr = num(cfg["min_reward_risk"], "min_reward_risk", zero=True)
        cap_pct = num(cfg["max_position_pct"], "max_position_pct", zero=True)
        buffer_pct = num(cfg["cash_buffer_pct"], "cash_buffer_pct", zero=True)
        if cap_pct > 100 or buffer_pct > 100:
            raise ValueError("percentage limits must be at most 100")
        blocked = {str(s).upper() for s in (cfg.get("blocked") or [])}
        lo = minute_of(cfg.get("no_entries_before"), "no_entries_before")
        hi = minute_of(cfg.get("no_entries_after"), "no_entries_after")

        portfolio = venue.read_json(a.portfolio, {}) or {}
        if portfolio and portfolio.get("quote") and str(portfolio["quote"]).upper() != quote:
            raise ValueError("portfolio quote mismatch")
        cash = num(portfolio.get("cash", budget), "cash", zero=True)
        positions = {}
        for sym, pos in (portfolio.get("positions") or {}).items():
            positions[str(sym).upper()] = num((pos or {}).get("qty", 0), "position qty", zero=True)

        # Today's recorded verdicts: they reserve risk, cash and trades so a
        # second run cannot hand out the same day's limits twice.
        previous = venue.read_json(a.risk, {}) or {}
        history = previous.get("verdicts") if previous.get("date") == date else []
        if not isinstance(history, list):
            raise ValueError("malformed risk history")
        seen = set()
        daily_used = 0.0
        trades_today = 0
        orders_today = 0
        for v in history:
            if not isinstance(v, dict):
                raise ValueError("malformed risk history")
            seen.add(v.get("order_fingerprint"))
            if str(v.get("verdict", "")).upper() != "PASS":
                continue
            if str(v.get("side", "buy")).lower() == "sell":
                continue
            if "risk_reserved" not in v:
                raise ValueError("a recorded PASS has no risk reservation; cannot establish today's risk")
            daily_used += num(v["risk_reserved"], "reserved risk", zero=True)
            trades_today += 1
            orders_today += 1
            cash -= num(v.get("cash_reserved", 0), "reserved cash", zero=True)
            sym = str(v.get("symbol", "")).upper()
            positions[sym] = positions.get(sym, 0.0) + num(v.get("qty", 0), "reserved qty", zero=True)

        document = venue.read_json(a.orders)
        if not isinstance(document, dict) or not isinstance(document.get("orders"), list):
            raise ValueError("malformed orders.json")
        orders = document["orders"]
        if not orders:
            raise ValueError("no orders to check")

        cost = venue.round_trip_pct(quote) / 100.0
        buy_cost = 1.0 + venue.fees(quote)["buy"] + venue.SLIPPAGE_PER_SIDE
        verdicts = []

        for order in orders:
            sym = str(order.get("symbol", "?")).upper() if isinstance(order, dict) else "?"
            v = {"symbol": sym, "verdict": "BLOCK", "reason": "", "checked_at": stamp}
            try:
                if not isinstance(order, dict):
                    raise ValueError("malformed order: not an object")
                v["order_fingerprint"] = fingerprint(order)
                sym = venue.pair(order["symbol"])
                v["symbol"] = sym
                side = str(order["side"]).lower()
                v["side"] = side
                if side not in ("buy", "sell"):
                    raise ValueError("malformed order: side must be buy or sell")
                if venue.quote_of(sym) != quote:
                    raise ValueError("quote mismatch: %s is not a %s pair" % (sym, quote))
                qty = num(order["qty"], "qty")
                entry = num(order["entry"], "entry")
                notional = num(order["quote_notional"], "quote_notional")
                if not math.isclose(notional, qty * entry, rel_tol=1e-6, abs_tol=1e-9):
                    raise ValueError("malformed order: quote_notional is not qty x entry")
                if v["order_fingerprint"] in seen:
                    raise ValueError("duplicate order: already checked today")
                filters = venue.symbol_filters(sym, offline=a.offline)
                step = filters["step"]
                if step > 0:
                    units = Decimal(str(qty)) / Decimal(str(step))
                    if units != units.to_integral_value():
                        raise ValueError("venue stepSize: %s is not a multiple of %s" % (qty, step))
                if qty < filters["min_qty"]:
                    raise ValueError("venue minQty: %s is under %s" % (qty, filters["min_qty"]))
                if notional < filters["min_notional"]:
                    raise ValueError("venue minNotional: %.8g is under %.8g" % (notional, filters["min_notional"]))

                if side == "sell":
                    held = positions.get(sym, 0.0)
                    if qty > held + 1e-12:
                        raise ValueError("sell of %s exceeds the %s held; no short selling" % (qty, held))
                    positions[sym] = held - qty
                    v.update(verdict="PASS", qty=qty, risk_reserved=0.0, cash_reserved=0.0,
                             reason="exit: reduces the position; day limits never block getting out")
                else:
                    stop = num(order.get("stop"), "stop (required)")
                    target = num(order.get("target"), "target")
                    price, source, age = fresh_price(sym, entry, a.offline, now)
                    measured = max(entry, price)
                    if not stop < measured < target:
                        raise ValueError("stop, entry and target are not in order (stop < entry < target)")
                    loss_unit = measured - stop + measured * cost
                    reward_unit = target - measured - measured * cost
                    risk = loss_unit * qty
                    rr = reward_unit / loss_unit
                    if risk > max_loss + 1e-9:
                        raise ValueError("stop too far: %.8g at the stop after fees and slippage, over the %.8g max loss per trade"
                                         % (risk, max_loss))
                    if rr < min_rr - 1e-9:
                        raise ValueError("reward:risk %.4f after costs is under the %.4f minimum" % (rr, min_rr))
                    if daily_used + risk > daily_limit + 1e-9:
                        raise ValueError("daily loss limit: %.8g already risked today plus %.8g is over %.8g"
                                         % (daily_used, risk, daily_limit))
                    if trades_today >= trades_limit:
                        raise ValueError("max trades per day: %d already taken today" % trades_today)
                    if orders_today >= order_limit:
                        raise ValueError("daily order limit: %d already recorded today" % orders_today)
                    if lo is not None and hi is not None:
                        t = now.hour * 60 + now.minute
                        inside = lo <= t <= hi if lo <= hi else (t >= lo or t <= hi)
                        if not inside:
                            raise ValueError("entry outside the UTC window %s-%s (now %s)"
                                             % (cfg["no_entries_before"], cfg["no_entries_after"], now.strftime("%H:%M")))
                    if sym in blocked:
                        raise ValueError("blocked symbol")
                    if (positions.get(sym, 0.0) + qty) * measured > budget * cap_pct / 100.0 + 1e-9:
                        raise ValueError("position cap: %.8g would be over %.2f%% of the %.8g budget"
                                         % ((positions.get(sym, 0.0) + qty) * measured, cap_pct, budget))
                    if qty * measured > num(cfg.get("max_order", budget), "max_order") + 1e-9:
                        raise ValueError("max order: %.8g is over the %.8g cap" % (qty * measured, cfg.get("max_order", budget)))
                    spend = qty * measured * buy_cost
                    if cash - spend < budget * buffer_pct / 100.0 - 1e-9:
                        raise ValueError("cash buffer: %.8g left would be under the %.2f%% buffer" % (cash - spend, buffer_pct))
                    cash -= spend
                    positions[sym] = positions.get(sym, 0.0) + qty
                    daily_used += risk
                    trades_today += 1
                    orders_today += 1
                    v.update(verdict="PASS", qty=qty, risk_reserved=risk, cash_reserved=spend,
                             measured_entry=measured, price=price, price_source=source,
                             price_age_s=age, reward_risk=rr,
                             reason="within every entry limit; a PASS is not an edge and not your yes")
            except (ValueError, KeyError, TypeError, venue.VenueError) as exc:
                v["reason"] = str(exc)
            seen.add(v.get("order_fingerprint"))
            verdicts.append(v)

        result = {"date": date, "verdicts": verdicts}
        if not a.no_write:
            venue.write_json(a.risk, {"date": date, "verdicts": history + verdicts})

        passed = sum(1 for v in verdicts if v["verdict"] == "PASS")
        if a.json:
            print(json.dumps(result, indent=2))
        else:
            for v in verdicts:
                print("%-5s %s: %s" % (v["verdict"], v["symbol"], v["reason"]))
            print("%d passed, %d blocked at %s UTC" % (passed, len(verdicts) - passed, stamp))
        return 0 if passed == len(verdicts) else 1
    except (ValueError, KeyError, TypeError, OSError, venue.VenueError) as exc:
        if a.json:
            print(json.dumps({"verdict": "BLOCK", "reason": str(exc)}))
        else:
            print("BLOCK: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
