#!/usr/bin/env python3
"""Time Machine for the Tokocrypto day-trader pack. Standard library only.

    python3 timemachine.py run
    python3 timemachine.py run --symbol BTCUSDT --entry 65000 --stop 64000 --target 67000
    python3 timemachine.py run --sims 2000 --seed 7
    python3 timemachine.py run --gate

Replays the rest of the session from the coin's own candles before you risk
anything, then re-runs the same rule on every past session in the window.

Two numbers, and they answer different questions:

  Monte Carlo  If the next hours look like this coin's own recent hours, how
               often does the target come before the stop? Paths are drawn by
               sampling, for each future candle slot, from the candles this coin
               printed at the same time-of-day slot over the last --days
               sessions. That keeps the intraday shape instead of pretending
               returns are independent.

  History      When this exact rule fired on past sessions, what happened?
               First touch of target or stop, stop first on an ambiguous
               candle. This is the only part that is evidence rather than
               simulation.

  --gate       The first-passage expectancy of the plan's own target, stop and
               horizon, after the 0.9688% round trip, with the break-even hit
               rate. This is the number that decides whether the trade is
               allowed at all. If it is negative, nothing else matters.

The verdict line never promises an edge. If the simulation and the history
disagree, it says so.
"""

import argparse
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_trader-lib"))
import venue  # noqa: E402

DEFAULTS = {
    "quote": "USDT",
    "interval": "5m",
    "session": "00:00",
    "flat_by": "23:55",
}


class TMError(Exception):
    pass


def load_config(path="trader/config.json"):
    cfg = dict(DEFAULTS)
    doc = venue.read_json(path, {}) or {}
    for k in DEFAULTS:
        if k in doc:
            cfg[k] = doc[k]
    return cfg


# ---------------------------------------------------------------------------
# slot bootstrap
# ---------------------------------------------------------------------------

def slot_index(open_ms, interval_s):
    """Which candle of the UTC day this bar is."""
    return (open_ms // 1000 // interval_s) % (86400 // interval_s)


def build_slots(rows, interval_s):
    """{slot: [(open, high, low, close), ...]} over the whole window."""
    slots = {}
    for r in rows:
        slots.setdefault(slot_index(r["open_ms"], interval_s), []).append(
            (r["open"], r["high"], r["low"], r["close"]))
    return slots


def simulate(entry, stop, target, slots, start_slot, n_slots, sims, rng):
    """Draw n_slots future candles per path from the matching time-of-day slots."""
    target_first = stop_first = neither = 0
    r_sum = 0.0
    closes = []
    risk = entry - stop
    reward = target - entry
    for _ in range(sims):
        price = entry
        outcome = None
        for k in range(n_slots):
            slot = start_slot + k
            pool = slots.get(slot)
            if not pool:
                continue
            o, h, l, c = pool[rng.randrange(len(pool))]
            if o <= 0:
                continue
            # Scale the sampled candle's shape onto the current price.
            hi = price * (h / o)
            lo = price * (l / o)
            cl = price * (c / o)
            if lo <= stop:
                outcome = -1.0
                break
            if hi >= target:
                outcome = 1.0
                break
            price = cl
        if outcome is None:
            neither += 1
            r_sum += (price - entry) / risk if risk > 0 else 0.0
            closes.append(price)
        elif outcome > 0:
            target_first += 1
            r_sum += reward / risk if risk > 0 else 0.0
            closes.append(target)
        else:
            stop_first += 1
            r_sum += -1.0
            closes.append(stop)
    return {
        "simulations": sims,
        "target_first": target_first,
        "stop_first": stop_first,
        "neither": neither,
        "target_first_pct": round(target_first / sims * 100.0, 2),
        "stop_first_pct": round(stop_first / sims * 100.0, 2),
        "neither_pct": round(neither / sims * 100.0, 2),
        "avg_r": round(r_sum / sims, 4),
        "median_close": round(sorted(closes)[len(closes) // 2], 8) if closes else None,
    }


# ---------------------------------------------------------------------------
# history: the same rule on past sessions
# ---------------------------------------------------------------------------

def history(rows, entry_pct_stop, entry_pct_target, horizon_bars, interval_s, session_hhmm):
    """Replay the rule on every past session. Returns counts and the average R."""
    fired = tgt = stp = neither = 0
    r_sum = 0.0
    stop_pct, target_pct = entry_pct_stop, entry_pct_target
    i = 0
    n = len(rows)
    while i < n - horizon_bars:
        entry = rows[i]["close"]
        if entry <= 0:
            i += 1
            continue
        up = entry * (1 + target_pct / 100.0)
        dn = entry * (1 - stop_pct / 100.0)
        fired += 1
        outcome = None
        for j in range(i + 1, min(i + 1 + horizon_bars, n)):
            if rows[j]["low"] <= dn:
                outcome = -1.0
                break
            if rows[j]["high"] >= up:
                outcome = 1.0
                break
        if outcome is None:
            neither += 1
            r_sum += (rows[min(i + horizon_bars, n - 1)]["close"] - entry) / (entry - dn) if entry > dn else 0.0
        elif outcome > 0:
            tgt += 1
            r_sum += (up - entry) / (entry - dn) if entry > dn else 0.0
        else:
            stp += 1
            r_sum += -1.0
        i += horizon_bars   # non-overlapping: overlapping windows inflate the sample
    return {
        "fired": fired,
        "target": tgt,
        "stop": stp,
        "neither": neither,
        "target_pct": round(tgt / fired * 100.0, 2) if fired else None,
        "stop_pct": round(stp / fired * 100.0, 2) if fired else None,
        "avg_r": round(r_sum / fired, 4) if fired else None,
        "non_overlapping": True,
    }


# ---------------------------------------------------------------------------

def cmd_run(a, cfg):
    plan = venue.read_json(a.plan, {}) or {}
    symbol = a.symbol or plan.get("symbol")
    if not symbol:
        raise TMError("no symbol: pass --symbol or run trader-exit plan first")
    symbol = venue.pair(symbol)
    quote = venue.quote_of(symbol)
    entry = a.entry or plan.get("entry")
    stop = a.stop or plan.get("stop")
    target = a.target or plan.get("target")
    if not (entry and stop and target):
        raise TMError("need entry, stop and target (from trader/plan.json or the flags)")
    entry, stop, target = float(entry), float(stop), float(target)
    if not stop < entry < target:
        raise TMError("stop, entry and target are not in order (stop < entry < target)")

    interval = a.interval or plan.get("interval") or cfg["interval"]
    interval_s = venue.INTERVAL_SECONDS[interval]
    horizon = a.horizon or plan.get("horizon_bars") or 24
    cost = venue.round_trip_pct(quote)
    stop_pct = (entry - stop) / entry * 100.0
    target_pct = (target - entry) / entry * 100.0

    days = a.days
    want = max(200, int(days * 86400 / interval_s))
    rows = venue.closed_only(venue.klines_paged(symbol, interval, want=want, offline=a.offline))
    if len(rows) < horizon * 3:
        raise TMError("only %d closed %s candles; need at least %d" % (len(rows), interval, horizon * 3))

    now = venue.parse_now(a.now)
    session = venue.session_start(now, a.session or cfg["session"])
    # The replay starts at the NEXT candle from now, not at the session open:
    # the question is "what happens from here", not "what happened today".
    start_slot = slot_index(venue.ms_of(now), interval_s) + 1
    slots = build_slots(rows, interval_s)
    rng = random.Random(a.seed)
    mc = simulate(entry, stop, target, slots, start_slot, horizon, a.sims, rng)

    hist = history(rows, stop_pct, target_pct, horizon, interval_s, cfg["session"])

    doc = {
        "symbol": symbol,
        "quote": quote,
        "plan": {"entry": entry, "stop": stop, "target": target,
                 "stop_pct": round(stop_pct, 4), "target_pct": round(target_pct, 4),
                 "horizon_bars": horizon, "interval": interval},
        "seed": a.seed,
        "days": days,
        "candles": len(rows),
        "monte_carlo": mc,
        "history": hist,
        "as_of": venue.iso(now),
    }

    if a.gate:
        gate = venue.barrier_ev(rows, target_pct, stop_pct, horizon, cost, step=horizon)
        be = venue.breakeven_hit_rate(target_pct, stop_pct, cost)
        doc["gate"] = {
            "net_pct": round(gate["net_pct"], 4),
            "gross_pct": round(gate["gross_pct"], 4),
            "cost_pct": round(cost, 4),
            "samples": gate["samples"],
            "target_hits": gate["target_hits"],
            "stop_hits": gate["stop_hits"],
            "timed_exits": gate["timed_exits"],
            "realized_hit_pct": round(gate["target_hits"] / gate["samples"] * 100.0, 2),
            "breakeven_hit_pct": round(be, 2),
            "passes": gate["net_pct"] > 0,
        }

    # The verdict: honest, and it never promises an edge.
    mc_edge = mc["avg_r"] > 0
    hist_edge = (hist["avg_r"] or 0) > 0
    if a.gate and not doc["gate"]["passes"]:
        verdict = ("NO TRADE: the first-passage gate is %.3f%% net per trade after the %.4f%% round trip "
                   "(realized hit %.1f%% vs %.1f%% needed). Nothing else matters."
                   % (doc["gate"]["net_pct"], cost, doc["gate"]["realized_hit_pct"], doc["gate"]["breakeven_hit_pct"]))
    elif mc_edge and hist_edge:
        verdict = ("Both the simulation and the history are positive, which is unusual. "
                   "That is a reason to look closer, not a reason to size up.")
    elif mc_edge != hist_edge:
        verdict = ("The simulation and the history disagree (avg R %.2f vs %.2f). "
                   "Treat the optimistic one as noise." % (mc["avg_r"], hist["avg_r"] or 0.0))
    else:
        verdict = ("NO TRADE: both the simulation (avg R %.2f) and the history (avg R %.2f) are negative."
                   % (mc["avg_r"], hist["avg_r"] or 0.0))
    doc["verdict"] = verdict

    if a.json:
        import json
        print(json.dumps(doc, indent=2))
    else:
        print("%s  entry %.8g  stop %.8g (-%.3f%%)  target %.8g (+%.3f%%)  %d x %s bars"
              % (symbol, entry, stop, stop_pct, target, target_pct, horizon, interval))
        print("  seed %d, %d simulations, %d candles over %d days"
              % (a.seed, mc["simulations"], len(rows), days))
        print("  Monte Carlo: target first %.2f%%  stop first %.2f%%  neither %.2f%%  avg R %.3f"
              % (mc["target_first_pct"], mc["stop_first_pct"], mc["neither_pct"], mc["avg_r"]))
        print("  history:     fired %d  target %.2f%%  stop %.2f%%  neither %d  avg R %.3f"
              % (hist["fired"], hist["target_pct"] or 0.0, hist["stop_pct"] or 0.0,
                 hist["neither"], hist["avg_r"] or 0.0))
        if a.gate:
            g = doc["gate"]
            print("  gate:        net %+.3f%% per trade after %.4f%% cost  (n=%d, hit %.1f%% vs %.1f%% needed)"
                  % (g["net_pct"], g["cost_pct"], g["samples"], g["realized_hit_pct"], g["breakeven_hit_pct"]))
        print("  verdict: %s" % verdict)
    if a.write:
        venue.write_json(a.write, doc)
        print("-> %s" % a.write)
    return 0 if (not a.gate or doc["gate"]["passes"]) else 1


def main(argv=None):
    p = argparse.ArgumentParser(description="Tokocrypto time machine")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("run")
    s.add_argument("--plan", default="trader/plan.json")
    s.add_argument("--symbol")
    s.add_argument("--entry", type=float)
    s.add_argument("--stop", type=float)
    s.add_argument("--target", type=float)
    s.add_argument("--sims", type=int, default=10000)
    s.add_argument("--days", type=int, default=60)
    s.add_argument("--seed", type=int, default=19)
    s.add_argument("--horizon", type=int, help="bars to replay (default from the plan, else 24)")
    s.add_argument("--gate", action="store_true", help="also run the first-passage gate")
    s.add_argument("--interval")
    s.add_argument("--session")
    s.add_argument("--now")
    s.add_argument("--offline", metavar="DIR")
    s.add_argument("--json", action="store_true")
    s.add_argument("--write", nargs="?", const="trader/timemachine.json", metavar="PATH")
    s.add_argument("--config", default="trader/config.json")
    a = p.parse_args(argv)
    cfg = load_config(a.config)
    try:
        return cmd_run(a, cfg)
    except TMError as exc:
        print("time machine refused: %s" % exc, file=sys.stderr)
        return 1
    except venue.VenueError as exc:
        print("time machine failed: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
