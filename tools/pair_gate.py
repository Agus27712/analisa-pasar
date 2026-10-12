#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Gerbang kelayakan pair berbasis edge (P4.3).

Masalah yang dijawab: friksi round-trip Tokocrypto USDT = 0,9688%, tetapi median
pergerakan harga 7 jam di sebagian pair (BTC, XAUT, PAXG) hanya 0,2–0,5%. Di pair
seperti itu **tidak ada** strategi yang bisa profit — biaya lebih besar daripada
pergerakan median. Tool ini menyaring pair yang secara matematis mustahil.

Aturan: layak  ⟺  median MFE(7 jam) ≥ MIN_RATIO × friksi round-trip.

Input: JSON keluaran `tools/universe_replay.py` (mis. `data/hasil/u3_toko_usdt_after.json`).
Keluaran: `data/config/pair_gate.json` + potongan Kotlin untuk `PairEdgeGate.kt`.

Contoh:
  python3 tools/pair_gate.py --usdt data/hasil/u3_toko_usdt_after.json \
      --idr data/hasil/u5_toko_idr.json --out data/config/pair_gate.json
"""
import argparse
import json
import os
import sys

MIN_RATIO = 2.0


def load_rows(path):
    if not path or not os.path.exists(path):
        return None
    with open(path) as f:
        d = json.load(f)
    return d


def build(rows_doc, friction_pct, label, min_ratio):
    rows = []
    for p in rows_doc.get("pairs", []):
        mfe = p.get("mfe_7h_median_pct")
        if mfe is None:
            continue
        ratio = mfe / friction_pct if friction_pct > 0 else None
        rows.append({
            "pair": p["pair"],
            "median_mfe_7h_pct": round(mfe, 4),
            "friction_pct": round(friction_pct, 4),
            "ratio": round(ratio, 4) if ratio is not None else None,
            "eligible": bool(ratio is not None and ratio >= min_ratio),
            "trades_resolved": p.get("trades_resolved", 0),
            "avg_r": p.get("avg_r"),
        })
    rows.sort(key=lambda x: -(x["ratio"] or -1))
    ok = sum(1 for r in rows if r["eligible"])
    return {
        "label": label,
        "source": rows_doc.get("summary", {}).get("data_dir"),
        "friction_roundtrip_pct": round(friction_pct, 4),
        "min_ratio": min_ratio,
        "pairs_total": len(rows),
        "pairs_eligible": ok,
        "pairs_ineligible": len(rows) - ok,
        "pairs": rows,
    }


def kotlin_map(rows, indent="        "):
    return "\n".join(f'{indent}"{r["pair"]}" to {r["median_mfe_7h_pct"]},' for r in rows)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--usdt", default="data/hasil/u3_toko_usdt_after.json")
    ap.add_argument("--idr", default="data/hasil/u5_toko_idr.json")
    ap.add_argument("--min-ratio", type=float, default=MIN_RATIO)
    ap.add_argument("--out", default="data/config/pair_gate.json")
    a = ap.parse_args()

    doc = {"min_ratio": a.min_ratio, "horizon_bars_m1": 420, "quotes": {}}

    usdt = load_rows(a.usdt)
    if usdt:
        blk = build(usdt, 0.9688, "Tokocrypto USDT", a.min_ratio)
        doc["quotes"]["USDT"] = blk
    else:
        print(f"! USDT: file tidak ada ({a.usdt})", file=sys.stderr)

    idr = load_rows(a.idr)
    if idr:
        blk = build(idr, 0.8144, "Tokocrypto IDR (taker)", a.min_ratio)
        doc["quotes"]["IDR"] = blk
    else:
        print(f"! IDR: file tidak ada ({a.idr}) — dilewati", file=sys.stderr)

    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w") as f:
        json.dump(doc, f, indent=2)

    for q, blk in doc["quotes"].items():
        print(f"\n=== {q} — {blk['label']} | friksi {blk['friction_roundtrip_pct']}% "
              f"| layak {blk['pairs_eligible']}/{blk['pairs_total']} ===")
        print(f"{'pair':<14}{'MFE7h%':>9}{'ratio':>8}{'layak':>7}")
        for r in blk["pairs"]:
            print(f"{r['pair']:<14}{r['median_mfe_7h_pct']:>9.4f}{r['ratio']:>8.2f}"
                  f"{'YA' if r['eligible'] else 'tidak':>7}")
        print("\n--- potongan Kotlin untuk PairEdgeGate.kt ---")
        print(kotlin_map(blk["pairs"]))

    print(f"\nDitulis: {a.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
