---
name: trader-risk
description: Check Tokocrypto orders against the risk limits and block anything over them; use before any order or when the user asks whether a trade is allowed.
---

# Risk Manager

The gate every order passes through. A BLOCK is final for that order; it is
recorded with a fingerprint so it cannot be reused for a differently sized or
differently priced trade.

```sh
python3 "$(realpath skill://trader-risk/check.py)"
python3 "$(realpath skill://trader-risk/check.py)" --json
python3 "$(realpath skill://trader-risk/check.py)" --no-write
python3 "$(realpath skill://trader-risk/check.py)" --now "2026-11-17 04:15"
```

Reads `trader/orders.json`, writes `trader/state/risk.json`.

## What blocks an entry

- no stop, or stop/entry/target not in that order
- loss at the stop, measured from the **higher** of the plan entry and a fresh
  price, over `max_loss_per_trade` after fees and slippage
- reward:risk after costs under `min_reward_risk`
- the day's total risk over `daily_loss_limit`
- more than `max_trades_per_day` already taken
- outside the `no_entries_before` / `no_entries_after` UTC window
- a symbol in `blocked`
- position cap, cash buffer, a malformed order, a duplicate, or the venue's
  `minQty` / `notional` / `stepSize` violated
- a price older than 15 minutes, which cannot raise the entry

A **sell that only reduces a position is never blocked by a day limit**: getting
out is not the risky part. It is still checked for format, quote asset, and
short-selling.

A PASS means the order is inside the limits. It is not an edge, not a
recommendation, and not the user's yes — the broker still needs an explicit
confirmation for that exact order.