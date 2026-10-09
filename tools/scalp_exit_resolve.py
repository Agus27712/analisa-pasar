#!/usr/bin/env python3
"""Exit resolution helpers for scalp_replay (spot long)."""
# Inlined into scalp_replay when restored. See SCALP_EXIT.md.

def net_profit_pct(entry, price, fee, slip):
    if entry <= 0 or price <= 0:
        return 0.0
    buy_cost = 1.0 + (fee.get("buy_taker_pct", 0.1) + slip) / 100.0
    sell_net = max(0.0, 1.0 - (fee.get("sell_taker_pct", 0.1) + slip) / 100.0)
    return ((price / entry) * sell_net / buy_cost - 1.0) * 100.0
