# Shared contract for the Tokocrypto day-trader pack

Every script in this pack imports `venue.py` from this directory. Nothing else
is shared: each skill owns its own script and its own `SKILL.md`.

## Importing the shared library

```python
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "_trader-lib"))
import venue
```

## Venue API (`venue.py`)

Clock (UTC everywhere; crypto never closes):
- `utc_now()`, `utc_of(ms)`, `ms_of(dt)`, `iso(dt)`, `parse_now(text)`
- `day_start(dt)`, `session_start(dt, hhmm="00:00")`

Symbols:
- `split_symbol(sym) -> (base, quote)`, `pair(sym)`, `quote_of(sym)`
- `fees(quote) -> {buy, sell, quote, kind}`, `round_trip_pct(quote)`

Market data (all raise `venue.VenueError` on failure; never return junk):
- `klines(sym, interval="5m", limit=200, end_ms=None, offline=None, cache_root="trader/cache", ttl=None, host_out=None)`
  → list of `{open_ms, open, high, low, close, volume, close_ms, closed}`, oldest first.
  `closed` is computed against the wall clock, so a forming candle is never
  mistaken for a finished one. `host_out` (a list) receives the host that answered.
- `closed_only(rows)` → only finished candles
- `ticker24h(sym, offline=None, cache_root=...)` → `{symbol, price, change_pct, quote_volume, high, low, trades}`
- `tickers24h_all(offline=None, cache_root=...)` → `{symbol: row}` in one request
- `exchange_info(...)`, `trading_symbols(quote="USDT", ...)`, `symbol_filters(sym, ...)`
  → `{min_qty, step, min_notional, tick}`

Indicators (plain float lists, oldest first):
- `sma(values, n)`, `ema(values, n)`, `rsi(values, n=14)`, `atr(rows, n=14)`,
  `stdev_pct(values)`, `round_step(value, step)`

The no-trade gate:
- `barrier_ev(rows, target_pct, stop_pct, horizon_bars, cost_pct, step=1)`
  → `{samples, target_hits, stop_hits, timed_exits, gross_pct, net_pct, cost_pct, target_pct, stop_pct, horizon_bars}`
- `breakeven_hit_rate(target_pct, stop_pct, cost_pct)`

IO:
- `read_json(path, default=None)`, `write_json(path, data)`, `money(x, quote)`, `pct(x)`

## Data hosts

`https://data-api.binance.vision/api/v3` **only**. The Tokocrypto mirror
`https://www.tokocrypto.site/api/v3` is never contacted by this pack: on
2026-11-18 the whole mirror edge (CloudFront, including `/` and `/ping`)
answered 429 with no rate-limit headers, and the standing instruction is to
stop calling it so the household IP is not banned. The app's own feed already
hits that host; every request we skip is a request it does not have to absorb.

Both hosts serve the same book (identical klines, tickers and `exchangeInfo`,
verified 2026-11-18), so nothing is lost by dropping the mirror. `MIRROR` still
exists in `venue.py` as a constant — a test or an explicit `hosts=(...)` may
name it — but it is not in `HOSTS` and must never be a fallback.

`venue.py` therefore throttles to 0.12 s between calls,
**quarantines** a host for 60 s the moment it answers 429/418 and fails over
instead of retrying it, and keeps a disk cache under `trader/cache/venue/`. A
429 never triggers a backoff: waiting 2 s per symbol is how a 120-symbol scan
turns into a five-minute crawl, and it keeps the throttled host throttled.

## Files under `trader/`

| file | written by | shape |
| --- | --- | --- |
| `universe.txt` | scanner | one symbol per line |
| `watchlist.json` | scanner | `{scan, exchange, quote, generated_at, scanned, hits:[{symbol, quote, setups, price, change_24h_pct, rsi14, ma20, high_20d, as_of}], errors}` |
| `entry.json` | entry | `{symbol, quote, setup, status, time, bar_open_ms, price, level, range_high, range_low, interval, session_start}` |
| `plan.json` | exit | `{symbol, quote, entry, stop, target, risk_per_unit, reward_risk, horizon_bars, flat_by, created_at, source}` |
| `timemachine.json` | timemachine | `{symbol, quote, plan, simulations, target_first_pct, stop_first_pct, neither_pct, avg_r, history:{fired, target, stop, neither}, verdict}` |
| `orders.json` | budget | `{orders:[{symbol, side, quote_notional, qty, entry, stop, target, max_loss, decided_by, created_at}], source}` |
| `state/risk.json` | risk | `{date, verdicts:[{symbol, verdict, reason, order_fingerprint, checked_at}]}` |
| `state/broker.json` | broker | append-only `{decisions:[{symbol, side, quote_notional, qty, status, order_id, reply, at}]}` |
| `portfolio.json` | broker | `{quote, cash, positions:{SYMBOL:{qty, avg_price, opened_at}}}` |
| `prices.json` | alerts | `{source, as_of, quotes:{SYMBOL:{price, change_pct, as_of}}}` |
| `alerts.json` | alerts | `{alerts:[{id, symbol, type, price, pct, at, label, date, rearm, fired_at}]}` |
| `config.json` | user | see below |

## `trader/config.json`

```json
{
  "quote": "USDT",
  "min_price": 0.0,
  "min_volume": 250000.0,
  "limit": 120,
  "bars": 60,
  "or_minutes": 15,
  "session": "00:00",
  "interval": "5m",
  "budget": 100.0,
  "max_loss_per_trade": 2.0,
  "max_position_pct": 25.0,
  "min_reward_risk": 1.5,
  "max_trades_per_day": 3,
  "daily_loss_limit": 6.0,
  "cash_buffer_pct": 10.0,
  "flat_by": "23:55",
  "no_entries_before": "00:15",
  "no_entries_after": "23:30",
  "blocked": [],
  "paper": true
}
```

All money values are in the quote asset. `session` and the entry window are UTC.

## Invoking a script

`skill://<name>/<file>` resolves for `read`, `realpath`, and as a Python
argument, but bash cannot exec it directly. Always wrap it:

```sh
python3 "$(realpath skill://trader-scanner/scan.py)" daily --limit 120
```

`python3 skill://trader-scanner/scan.py` fails with "cannot resolve ... for an
external command". This is the one form that works everywhere.

## Rules every module follows

1. Only closed candles count. A wick is not a close.
2. Never mix USDT and IDR rows in one ranking, plan, or backtest.
3. Report the data source and its age; a stale cache is a data failure.
4. No order is placed without an explicit user instruction. Paper is the default.
5. A signal, score, or model opinion is not an edge: the first-passage gate
   decides. State `NO TRADE` when it fails.
6. Never poll indefinitely: bound every watch and return `open` when it expires.
7. Standard library only. No pip installs.
