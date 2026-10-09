#!/usr/bin/env python3
"""
Dump keputusan ENTRY per bar dari tools/scalp_replay.py (evaluate_bar),
untuk dibandingkan dengan ScalpParityEntryTest.kt (ScalpingMtfEvaluator).

Aturan window (HARUS sama dengan test Kotlin):
  m1w  = 250 bar M1 terakhir s/d bar saat ini (inklusif)
  m15w = 60 bar M15 terakhir yang sudah CLOSED (t + 15m <= t_now)
  h1w  = 60 bar H1 terakhir yang sudah CLOSED  (t + 60m <= t_now)
Fee: Tokocrypto 0,10/0,10. Orderbook diabaikan (assume_ob=True).

Output: satu baris per bar: PARITY|t|ready|setup|score|entry|sl|tp1|tp2
"""
import argparse, bisect, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scalp_replay as s

START, COUNT = 300, 2000
FEE = {"buy_taker_pct": 0.10, "sell_taker_pct": 0.10, "buy_maker_pct": 0.10, "sell_maker_pct": 0.10}

def closed_last(bars, ts_list, t_now, span_ms, n):
    end = bisect.bisect_right(ts_list, t_now - span_ms)  # bar dengan t + span <= t_now
    return bars[max(0, end - n):end]

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pair", default="XRPUSDT")
    ap.add_argument("--data", default="data/tokocrypto_v3")
    ap.add_argument("--config", default="data/config/scalp_config.json")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    import json
    cfg = json.load(open(a.config))
    m1 = s.load_csv(os.path.join(a.data, f"{a.pair}_1m.csv"))
    m15 = s.load_csv(os.path.join(a.data, f"{a.pair}_15m.csv"))
    h1 = s.load_csv(os.path.join(a.data, f"{a.pair}_1h.csv"))
    m15_ts = [c["t"] for c in m15]
    h1_ts = [c["t"] for c in h1]
    lines = []
    for i in range(START, START + COUNT):
        c = m1[i]
        t = c["t"]
        m1w = m1[max(0, i - 249):i + 1]
        m15w = closed_last(m15, m15_ts, t, 15 * 60_000, 60)
        h1w = closed_last(h1, h1_ts, t, 60 * 60_000, 60)
        r = s.evaluate_bar(c["c"], m1w, m15w, h1w, cfg, FEE, True)
        if r is None:
            lines.append(f"PARITY|{t}|NULL")
            continue
        lines.append("PARITY|{}|{}|{}|{}|{:.6f}|{:.6f}|{:.6f}|{:.6f}".format(
            t, 1 if r["ready"] else 0, r["setup"], r["score"],
            r["entry"], r["sl"], r["tp1"], r["tp2"]))
    open(a.out, "w").write("\n".join(lines) + "\n")
    ready = sum(1 for l in lines if "|1|" in l)
    print(f"wrote {len(lines)} lines, ready={ready}, out={a.out}")

if __name__ == "__main__":
    main()
