# omp TradingView → Tokocrypto adapter (repo analisa-pasar)

Paket ini menyimpan **tool + skill + runbook** adaptasi:

- `tradesdontlie/tradingview-mcp` → konsep chart/quote/ohlcv/study; data pakai `data-api.binance.vision`.
- `roan-maverick/tradingview-indicator-for-ai-agents` → AI Agent Lens (`ai-agent-lens/v1`), webhook per candle close.
- Eksekusi: Analisa Pasar Agent Bridge (`127.0.0.1:8765`) + gate `trader-risk` / `trader-broker` / `trader-timemachine` di **omp (Termux/Android)**.

Tujuan: jika app/lingkungan di-install ulang, cukup **download/pull repo ini** lalu restore dari paket ini.

## Isi paket

| Path | Isi |
|---|---|
| `tv-adapter/` | Receiver webhook AI Agent Lens, MCP stdio server `tv-tokocrypto`, helper market/bridge/risk/broker, skrip start, contoh packet |
| `agent-skills/_trader-lib/` | `venue.py` (market data/cache Binance vision) + kontrak |
| `agent-skills/trader-risk/` | Gate risiko (`check.py`) |
| `agent-skills/trader-broker/` | Ticket/order helper (`ticket.py`) |
| `agent-skills/trader-timemachine/` | First-passage barrier gate (`timemachine.py`) |
| `agent-skills/tv-lens-tokocrypto-adapter/` | Managed skill omp (`SKILL.md`) |
| `upstream/` | `ai-agent-lens.pine` (MIT) + `PACKET_SCHEMA.md` + lisensi |
| `restore.sh` | Instalasi/restore idempotent ke lokasi runtime omp |
| `CHECKPOINT_OMP.md` | **Checkpoint detail**: cara pakai agen di omp setelah install ulang |

## Restore cepat (setelah clone/pull repo)

```sh
cd /path/ke/analisa-pasar
bash tools/omp-tv-adapter/restore.sh
```

Lalu **mulai/restart sesi omp** agar MCP `tv-tokocrypto` termuat, dan ikuti **[CHECKPOINT_OMP.md](CHECKPOINT_OMP.md)**.

## Keamanan & batas

- **Jangan commit**: token webhook `tv-lens-token.txt`, token bridge `~/.omp/agent/extensions/analisa-pasar/agent-bridge-token.txt`, `receiver.log`, `packets/`, `trader/orders.json`/state/portfolio/jurnal, kredensial apa pun.
- Data pasar: **hanya** `https://data-api.binance.vision` (tanpa host app tokocrypto mirror). 429 pertama → stop, pakai cache.
- Packet/lens = analisa saja, **bukan** order. Gate wajib (`tv_gate` eksplisit; `passed:false` = NO TRADE). `tv_risk_check` PASS + **yes eksplisit per order**. Paper default.
- Real butuh: `trader/config.json` `paper:false` + app Mode Real + PIN (`isRealBuyEnabled` & `isPinAuthenticated`) + `confirm_live="LIVE"`. Risiko modal nyata.
- Spot Tokocrypto tidak SHORT (`SHORT_SETUP` = SKIP). Fee USDT ~0.9688% round-trip (0.4044%/sisi + 0.08%/sisi slippage).
- Bagian `tradingview-mcp` yang butuh TradingView Desktop/CDP/Electron (Pine editor UI, screenshot, replay UI, drawing) **tidak di-port** (Termux/Android).
