---
name: tv-lens-tokocrypto-adapter
description: "Use when TradingView AI Agent Lens packets, tv-tokocrypto MCP tools, or the trader/tv-adapter webhook receiver are involved in Tokocrypto paper/live decisions."
---

# TV -> Tokocrypto Adapter (omp)

Adapter dir: `~/trader/tv-adapter` (README.md = full docs; agent_prompt.md = lens decision prompt).
Upstream reference clones: `~/work/tv-mcp-upstream` (tradesdontlie/tradingview-mcp), `~/work/tv-lens-upstream` (roan-maverick/tradingview-indicator-for-ai-agents).

## Components
- `receiver.py` — AI Agent Lens webhook, port 8080, token in `tv-lens-token.txt`; saves `packets/latest.json`, `packets/latest/<SYM>_<TF>.json`, `packets/history.jsonl`. Symbol normalized (`BINANCE:X.P` -> `X`) and validated TRADING via data-api.binance.vision.
- `mcp_server.py` — MCP stdio server `tv-tokocrypto`, registered in `~/.omp/agent/mcp.json`. Restart omp session to load.
- `common.py` — vision klines/ticker/exchangeInfo only (NEVER tokocrypto.site hosts), fee 0.4044%/side + slip 0.08%/side, net_rr after cost, LOT_SIZE floor.

## MCP tools
Analisa: `tv_status`, `tv_quote`, `tv_ohlcv`, `tv_chart_state`, `tv_engine_analysis`, `tv_journal`, `tv_lens_latest`, `tv_lens_history`.
Gate+order: `tv_gate` (trader-timemachine explicit entry/stop/target/interval/horizon, exit 1 = NO TRADE), `tv_prepare_order` (BUY spot only, net RR >= min_reward_risk, stepSize floor), `tv_risk_check`, `tv_broker_show`, `tv_broker_approve` (records exact user yes only), `tv_broker_place` (paper default), `tv_flatten`, `tv_order_status`.

## Hard flow (never skip)
1. `tv_lens_latest`/`tv_quote`/`tv_ohlcv`/`tv_engine_analysis` — re-fetch live before entry.
2. `tv_gate` with EXPLICIT geometry (same as execution). passed:false => NO TRADE, no matter the score.
3. `tv_prepare_order` (writes trader/orders.json only).
4. `tv_risk_check` — PASS bound to order fingerprint + today.
5. `tv_broker_approve` — exact per-order user yes, records nothing else.
6. `tv_broker_place` — paper default. Live requires trader/config.json paper=false AND app Mode Real+PIN (`tv_status.live_gates.allowed_now`) AND `confirm_live="LIVE"`.

## Gotchas
- Receiver empty token allows POST; keep `TV_LENS_TOKEN`/`tv-lens-token.txt` set in production.
- `available:false` engine = transient ticker blindness; check bridge_health, retry; never conclude symbol missing.
- Packets stale >15m => refresh live data before entry.
- TradingView webhooks need public HTTPS (cloudflared `start_tunnel.sh`); local test: POST `examples/sample_packet.json` to `http://127.0.0.1:8080/webhook?token=...`.
- SHORT setups: spot Tokocrypto = SKIP. Exits use `tv_flatten`, not `tv_prepare_order`.
- TradingView Desktop/CDP-only parts of tradingview-mcp (Pine editor inject, screenshot, replay, drawing/pane/tab UI) are NOT ported: no TradingView Desktop/CDP on Termux/Android; data/signals come from vision + AI Agent Lens + bridge engine.
- Verified end-to-end 2026-11-20: sample packet accepted; omp loads `tv-tokocrypto`; `tv_status`/`tv_quote`/`tv_ohlcv` OK; temp ticket PASS then live placement refused (paper config); `tv_gate` explicit geometry returns NO TRADE.
