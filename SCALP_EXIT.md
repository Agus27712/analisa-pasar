# Scalping Exit Strategy (Spot) — 2026-10-09

Dokumen ini adalah **sumber kebenaran tujuan** untuk agen yang melanjutkan kerja scalping exit. Baca bersama `AGENTS.md` dan `APP_CONTEXT.md`.

## Tujuan produk (WAJIB sama)

Setelah sinyal **BUY** (long) dan posisi spot terbuka, engine harus bisa memicu **SELL** sendiri untuk:

1. **Hard SL** — wajib cut loss
2. **TP1 partial** + geser SL ke **breakeven** (setelah fee buffer)
3. **TP2** — target penuh
4. **Trailing** dari peak setelah profit trigger
5. **Early profit SELL** — bila profit **net** (setelah fee + slippage) ≥ threshold **dan** momentum lemah
6. Opsional **time-stop** (`max_hold_bars_m1`)

Ini **exit dari long spot** (beli bawah → jual atas), **bukan short selling**.
Jangan pakai “SL/TP terbalik” kecuali suatu saat ada short/futures (di luar scope saat ini).

Profit net harus lewat logika setara `FeeCalculator` (buy fee + sell fee + 2× slippage).

## Replay offline (tuning dulu, baru port ke Kotlin)

Tool: `tools/scalp_replay.py`

Config: `data/config/scalp_config_exit_*.json` → objek `exit`:

| mode | Perilaku |
|------|----------|
| `classic` | SL / TP2 saja (baseline lama) |
| `tp1_be` | TP1 → partial + SL ke BE; lalu TP2/SL |
| `early` | Early SELL (min net profit + momentum lemah) |
| `trail` | Trailing % di bawah peak setelah BE trigger |
| `full` | Kombinasi tp1_be + trail + early |

Parameter `exit`:
- `min_early_profit_net_pct` (default 0.45)
- `breakeven_trigger_net_pct` (default 0.40)
- `trail_pct` (default 0.35)
- `max_hold_bars_m1` (0 = hanya lookahead)

Contoh:
```bash
python tools/scalp_replay.py --pair XRPUSDT --data data/tokocrypto_v2 \
  --config data/config/scalp_config_exit_full.json --assume-orderbook-ok \
  --out data/hasil/hasil_xrp_exit_full.json
```

Summary JSON menambah `exit_reasons_count` dan `by_exit_reason`:
`SL`, `TP2`, `BE_SL`, `TRAIL_SL`, `EARLY_PROFIT`, `TIMEOUT`.

## Hasil uji data tokocrypto_v2 (baseline entry thresholds + exit modes)

| Pair | Mode | WR% | Avg R | PF |
|------|------|-----|-------|-----|
| XRP | classic | 21.4 | -0.52 | 0.34 |
| XRP | full | 38.9 | -0.09 | 0.85 |
| SOL | classic | 14.6 | -0.67 | 0.21 |
| SOL | full | 32.7 | -0.22 | 0.67 |
| BTC | classic | 3.7 | -0.92 | 0.05 |
| BTC | full | 25.0 | -0.44 | 0.42 |

**Kesimpulan:** exit BE/trail/early **memperbaiki** WR & expectancy vs classic, tetapi long-only entry di data ini masih −EV. Jangan hanya menaikkan `min_score_long` (uji score70 justru memperburuk).

## Port ke app (langkah berikutnya)

1. Samakan perilaku `resolve_trade` di replay dengan monitor posisi di app (`SignalLifecycleManager` / `PositionCoordinator` / trailing di foreground service).
2. Early profit harus cek profit **net** + momentum (EMA/RSI), bukan sekadar “hijau di chart”.
3. Output `SignalAction.SELL` dengan `exit_reason` jelas untuk UI/notifikasi.
4. Entry tetap long-only sampai ada desain short terpisah; fokus dulu exit long.

## Catatan tool

- `tools/scalp_replay.py` di repo harus berisi implementasi `resolve_trade` + mode exit (bukan PLACEHOLDER).
- Config exit sudah ada di `data/config/scalp_config_exit_{classic,tp1_be,early,trail,full}.json`.
- Workbench lokal agen: `/home/workdir/artifacts/scalp_run/` punya script + hasil uji.
