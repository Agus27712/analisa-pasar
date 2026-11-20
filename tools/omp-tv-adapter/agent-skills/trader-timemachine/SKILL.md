---
name: trader-timemachine
description: Replay a Tokocrypto trade plan against the coin's own history and run the first-passage gate; use before any entry, or when the user asks "would this have worked" or "what are the odds".
---

# Time Machine

Replays the rest of the session from the coin's own candles before you risk
anything, then re-runs the same rule on every past session in the window.

```sh
python3 "$(realpath skill://trader-timemachine/timemachine.py)" run
python3 "$(realpath skill://trader-timemachine/timemachine.py)" run --gate
python3 "$(realpath skill://trader-timemachine/timemachine.py)" run --symbol BTCUSDT --entry 65000 --stop 64000 --target 67000
python3 "$(realpath skill://trader-timemachine/timemachine.py)" run --sims 2000 --days 10 --seed 7
```

## Three numbers, three questions

**Monte Carlo** — if the next hours look like this coin's own recent hours, how
often does the target come before the stop? For each future candle slot, a
candle is drawn from the ones this coin printed at the *same time-of-day slot*
over the last `--days` sessions, and its shape is scaled onto the current price.
That keeps the intraday shape instead of pretending returns are independent.
Deterministic for a given `--seed` (default 19).

**History** — when this exact rule fired on past sessions, what happened? First
touch of target or stop, stop first on an ambiguous candle, **non-overlapping**
windows (overlapping ones inflate the sample and the apparent edge).

**`--gate`** — the first-passage expectancy of the plan's own target, stop and
horizon after the 0.9688% round trip, with the break-even hit rate. This is the
number that decides whether the trade is allowed at all.

## Reading the verdict

The verdict line never promises an edge:

- gate negative → `NO TRADE`, and nothing else matters
- simulation and history disagree → treat the optimistic one as noise
- both negative → `NO TRADE`
- both positive → "a reason to look closer, not a reason to size up"

Exit code is `1` when the gate fails, so a script can gate on it.

## Measured on this venue

On 2026-11-17, BTCUSDT/ETHUSDT/SOLUSDT at TP2/SL3/12 bars all came out
**−1.02% to −1.06% net** with 3.6–4.8% realized hits against a 79.4% break-even.
TP1/SL1 was −0.99% to −1.05% with 9.6–18.1% against 98.4%. The gate is not
pessimistic; the friction is 0.9688% and the moves are smaller than that.

`--write` saves the full report to `trader/timemachine.json`.