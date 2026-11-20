# TV -> Tokocrypto adapter (omp + analisa-pasar)

Adaptasi:
- `tradesdontlie/tradingview-mcp` (konsep chart/quote/ohlcv/study, MCP tooling) dipetakan ke data pasar `data-api.binance.vision` (buku sama, tanpa host app `www.tokocrypto.site`).
- `roan-maverick/tradingview-indicator-for-ai-agents` (AI Agent Lens `ai-agent-lens/v1`, webhook per kandle tutup) dipetakan ke receiver lokal + pembacaan paket.
- Eksekusi order lewat Analisa Pasar app bridge `http://127.0.0.1:8765` (token `~/.omp/agent/extensions/analisa-pasar/agent-bridge-token.txt`) + gate `trader-risk` / `trader-broker` / `trader-timemachine`.

Upstream clone (referensi): `~/work/tv-mcp-upstream`, `~/work/tv-lens-upstream`.

## Komponen

| Path | Fungsi |
|---|---|
| `trader/tv-adapter/common.py` | normalisasi simbol TV (`BINANCE:X.P` -> `X`), vision klines/ticker/exchangeInfo, fee+stepSize, baca paket lens, wrapper risk/broker/timemachine |
| `trader/tv-adapter/receiver.py` | webhook AI Agent Lens (token wajib, validasi simbol TRADING), simpan `packets/latest.json`, `packets/latest/<SYM>_<TF>.json`, `packets/history.jsonl` |
| `trader/tv-adapter/mcp_server.py` | MCP stdio server `tv-tokocrypto` |
| `trader/tv-adapter/tv-lens-token.txt` | token webhook (dibuat otomatis) |
| `~/.omp/agent/mcp.json` | registrasi server MCP `tv-tokocrypto` |

## MCP tools (`tv-tokocrypto`)

- Analisa/pasar: `tv_status`, `tv_quote`, `tv_ohlcv`, `tv_chart_state`, `tv_engine_analysis`, `tv_journal`
- AI Agent Lens: `tv_lens_latest`, `tv_lens_history` (analisa saja, bukan order)
- Gate & order: `tv_gate` (first-passage, exit 1 = NO TRADE), `tv_prepare_order` (tulis ticket BUY, net RR dihitung, qty dibulatkan ke LOT_SIZE step), `tv_risk_check`, `tv_broker_show`, `tv_broker_approve`, `tv_broker_place`, `tv_flatten`, `tv_order_status`

Instruksi server (dikirim ke agent): paket/sinyal tidak pernah order otomatis; gate wajib; risk PASS + yes eksplisit per ticket; paper default; spot tidak bisa short.

## Jalankan receiver (AI Agent Lens -> packets)

```sh
cd ~/trader/tv-adapter
python3 receiver.py --host 127.0.0.1 --port 8080
# token: cat tv-lens-token.txt  (atau TV_LENS_TOKEN / --token)
# health: curl http://127.0.0.1:8080/health
```

Webhook TradingView alert (indikator `ai-agent-lens.pine` dari upstream, condition `Any alert() function call`):
`http(s)://<host-anda>:8080/webhook?token=<token>`

Catatan: TradingView butuh webhook HTTPS publik (port 80/443, plan berbayar + 2FA). Di Termux butuh tunnel (mis. cloudflared) ke `127.0.0.1:8080`; tunnel tidak termasuk adapter ini. Untuk uji lokal bisa POST packet contoh langsung ke receiver.

## Alur eksekusi (paper / real)

1. Analisa: `tv_lens_latest` / `tv_quote` / `tv_ohlcv` / `tv_engine_analysis` (konfirmasi live).
2. Gate wajib: `tv_gate` dengan `entry/stop/target/interval/horizon` eksplisit (geometri gate = geometri eksekusi). `passed:false` / exit 1 => **NO TRADE**.
3. Ticket: `tv_prepare_order` (BUY spot, `quote_amount` atau `qty`) -> tulis `trader/orders.json`, hitung net RR (fee+slip), butuh >= `min_reward_risk`.
4. Risiko: `tv_risk_check` -> butuh verdict PASS untuk fingerprint ticket hari ini.
5. Konfirmasi: `tv_broker_approve --reply "<kata yes user persis>"` (rekam saja, tidak kirim).
6. Eksekusi: `tv_broker_place` (paper default). Menolak bila bridge down / tanpa PASS / tanpa approve.
7. Cek: `tv_order_status`, `tv_journal`.

Shortcut app-side: tool `buy_and_watch_paper` / `watch_paper_position` (analisa-pasar extension) untuk siklus paper terkelola; tetap patuhi gate+risiko sebelum entry.

## Aktifkan order riil (bukan default)

Semua gate harus lolos:
- `trader/config.json`: set `"paper": false` (broker ticket path menolak live tanpa ini).
- App Analisa Pasar: Agent Bridge ON, **Mode Real** aktif, sesi PIN valid (`isRealBuyEnabled` & `isPinAuthenticated` true di `tv_status` / trading status).
- Tetap wajib: `tv_gate` lulus, `tv_risk_check` PASS, `tv_broker_approve` dengan yes eksplisit user untuk ticket itu.
- Panggil `tv_broker_place` dengan `live=true` dan `confirm_live="LIVE"` (konfirmasi eksplisit). Tanpa itu -> ditolak.

`tv_status.live_gates` merangkum status saat ini. Real order = risiko modal nyata; adapter menolak bypass gate.

## Verifikasi terukur (sesi adaptasi)

- Receiver: POST `work/tv-lens-upstream/examples/sample_packet.json` (`BINANCE:HYPEUSDT.P` 60m LONG_SETUP) -> 200 saved sebagai `HYPEUSDT`.
- MCP stdio: `initialize/tools/list` ok; `tv_status` bridge ok + paper; `tv_quote BTCUSDT` dan `tv_ohlcv BTCUSDT 1h` dari vision; `tv_lens_latest HYPEUSDT` -> decision LONG (analisa).
- Flow temp: `tv_prepare_order` (qty step-size) -> `tv_risk_check` PASS -> `tv_broker_place live=true` ditolak (paper config). `tv_gate` 1h/12 contoh -> NO TRADE (bukan rekomendasi entry).

## Keterbatasan

- Tidak ada TradingView Desktop/CDP/Electron di environment ini: bagian UI automation/Pine editor/screenshot/replay dari `tradingview-mcp` tidak di-port; data & sinyal diganti vision + AI Agent Lens packets + engine bridge.
- Sinyal/paket bisa basi; confidence/score bukan edge. `available:false` engine = transient blindness, bukan bukti pair hilang (cek `bridge_health` lalu ulang).
- Hindari host app `tokocrypto.site`/mirror/`cloudme-toko.2meta.app`; 429 pertama => stop, pakai cache.
- SELL/exit butuh gate konfirmasi & broker flow sama; spot Tokocrypto tidak mendukung SHORT.