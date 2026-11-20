---
name: trader-broker
description: Execute approved Tokocrypto paper tickets through the local Analisa Pasar bridge; use when the user asks to place, paper-buy, sell, flatten, approve, or inspect a crypto order.
---

# Tokocrypto Broker

This is the final execution gate for the pack. It is paper-only by default.
A model BUY, scanner hit, or bridge signal is never sufficient: the exact order
must have a PASS from `trader-risk` for today and an explicit user yes for that
order. A yes to a different symbol, amount, or stop does not transfer.

The bridge is `http://127.0.0.1:8765`; its `/api/v1/trading/*` routes require
`X-Agent-Token`, loaded from `ANALISA_PASAR_TOKEN` or the token file installed
with the Analisa Pasar OMP extension. The bridge's current trading status is
the source of truth for paper availability. If it is unreachable, do not fake a
fill: return a clear refusal.

## Commands

```sh
python3 "$(realpath skill://trader-broker/ticket.py)" show
python3 "$(realpath skill://trader-broker/ticket.py)" approve --index 1 --reply "yes"
python3 "$(realpath skill://trader-broker/ticket.py)" place --index 1
python3 "$(realpath skill://trader-broker/ticket.py)" flatten --symbol BTCUSDT
python3 "$(realpath skill://trader-broker/ticket.py)" fill --symbol BTCUSDT --side buy --qty 0.001 --price 64500
python3 "$(realpath skill://trader-broker/ticket.py)" log
```

`approve` records the user's words but does not send anything. `place` sends a
paper order only after risk PASS + approval + bridge health. `--live` is not a
shortcut: live remains disabled unless the config explicitly sets
`"paper": false` and the user explicitly requests live. Never invent an order
ID or fill; the bridge response is reported verbatim.

Outputs are persisted in `trader/state/broker.json` and simulated portfolio
fills in `trader/portfolio.json`. SELL/flatten also requires the same risk and
explicit-confirmation gates; getting out is urgent, but a fabricated success
is worse than a clear bridge error.
