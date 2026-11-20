# Day Trader for Tokocrypto

A model-neutral port of [Jakeschincariol/day-trader-skill](https://github.com/Jakeschincariol/day-trader-skill)
to a crypto venue that never closes. Nine skills, plain Python 3, standard
library only, no API key.

The upstream pack trades US equities: Yahoo charts, Nasdaq symbol directories,
NYSE hours, SEC filings, Alpaca orders. None of that survives here. What
survives is the method: scan, time the entry, set the stop before you enter,
replay the odds, size to the loss, check the limits, and only then place a
paper order.

## What changed

| upstream | here |
| --- | --- |
| Yahoo Finance charts | Tokocrypto/Binance-compatible klines (`data-api.binance.vision` only; the app's own `tokocrypto.site` mirror is never called) |
| Nasdaq Trader symbol files | `exchangeInfo`, 516 TRADING USDT pairs, 28 IDR |
| 09:30–16:00 ET, NYSE holidays | 24/7, UTC, session boundary is a config choice |
| SEC EDGAR, House Clerk | dropped: no crypto analogue |
| Alpaca orders | Analisa Pasar Agent Bridge paper orders at `127.0.0.1:8765` |
| `${CLAUDE_SKILL_DIR}` | `skill://<skill-name>/<file>.py` |
| Claude Desktop, `/loop`, approval cards | plain CLI, explicit yes, no client-specific mechanics |

## The nine

| skill | script | what it does |
| --- | --- | --- |
| `trader-scanner` | `scan.py` | universe, daily setups, opening-range breakouts |
| `trader-entry` | `entry.py` | watches a setup candle by candle, reports the trigger |
| `trader-exit` | `exit.py` | sets the stop and target before entry |
| `trader-timemachine` | `timemachine.py` | Monte Carlo replay + the first-passage gate |
| `trader-budget` | `size.py` | sizes the position to the max loss |
| `trader-risk` | `check.py` | blocks orders over the limits |
| `trader-broker` | `ticket.py` | paper orders through the bridge |
| `trader-alerts` | `alerts.py` | price, stop, target and time alerts, 24/7 UTC |
| `trader-news` | `news.py` | crypto headlines + the venue's own 24h moves |

`_trader-lib/venue.py` is shared by all of them: hosts, symbol rules, fees,
cache, throttling, indicators, and the barrier test. `_trader-lib/CONTRACT.md`
is the interface every script follows.

## Costs are not optional

The venue charges 0.4044% per side on USDT pairs, and the app's journal assumes
0.08% slippage per side. A round trip costs **0.9688%**. That number is the
floor every setup has to clear, and it is why most of them do not:

- a 2% target against a 3% stop needs a **79.4%** hit rate to break even;
- the engine's own published scalping plan (+0.16% TP1 against a −5.8% stop)
  needs **108%**, which is impossible.

So the pack's default answer is `NO TRADE`, and it says so with the arithmetic
attached. A scanner hit, a bridge signal, or a model's opinion is never
evidence of an edge.

## Quick start

```sh
mkdir -p trader && cd trader/..
python3 "$(realpath skill://trader-scanner/scan.py)" daily --limit 120 --write
python3 "$(realpath skill://trader-entry/entry.py)" check BTCUSDT
python3 "$(realpath skill://trader-exit/exit.py)" plan --symbol BTCUSDT --entry 64500
python3 "$(realpath skill://trader-timemachine/timemachine.py)" run --gate
python3 "$(realpath skill://trader-budget/size.py)" plan
python3 "$(realpath skill://trader-risk/check.py)" check
python3 "$(realpath skill://trader-broker/ticket.py)" show
```

Every script takes `--offline DIR`, `--json`, `--now "YYYY-MM-DD HH:MM"` (UTC)
and `--config trader/config.json`. State lives under `trader/`; see
`_trader-lib/CONTRACT.md` for the exact shapes.

## Tests

```sh
cd ~/.agents/skills/_trader-lib && python3 -m unittest discover -s tests -t tests
```

Fixtures are real venue data captured from Tokocrypto, so the venue layer is
tested against the shape the exchange actually returns.

## Honest limits

- Paper only by default. Live requires `"paper": false` in the config, an
  explicit request, and `--live`; the bridge also has its own real-buy switch.
- Market data comes from `data-api.binance.vision`; the Tokocrypto mirror is a
  fallback only. Both serve the same book, but the mirror is the app's own
  feed — hitting it first throttled the app's scanner with HTTP 429. A host that
  answers 429 is quarantined for 60 s and skipped, never retried.
- The bridge must be running with the Agent Bridge switch on. If it is not,
  the broker refuses rather than inventing a fill.
- Headlines are leads, not facts. A 24h move is not a signal.
- Nothing here is financial advice. Most people who day trade lose money, and
  a 0.97% round trip makes that worse.
