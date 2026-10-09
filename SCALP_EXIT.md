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

## Hasil uji data tokocrypto_v2 (R seragam net, 2026-10-09)

Dihitung ulang setelah fix satuan R (lihat `tools/README_SCALP_REPLAY.md`).
Isi sel: **avg R / profit factor**. Data: 8 pair, mode exit 5 varian, entry threshold baseline.

| Pair | classic | tp1_be | early | trail | full |
|------|------|------|------|------|------|
| ADA | -0.85 / 0.36 | -0.74 / 0.29 | -0.71 / 0.31 | -0.67 / 0.35 | -0.71 / 0.31 |
| BNB | -1.42 / 0.10 | -0.97 / 0.15 | -0.96 / 0.21 | -0.87 / 0.23 | -0.91 / 0.20 |
| BTC | -1.57 / 0.05 | -1.20 / 0.09 | -1.21 / 0.10 | -1.13 / 0.14 | -1.15 / 0.11 |
| DOGE | -1.12 / 0.22 | -0.86 / 0.23 | -0.79 / 0.25 | -0.79 / 0.27 | -0.75 / 0.28 |
| ETH | -1.52 / 0.07 | -1.16 / 0.10 | -1.24 / 0.09 | -1.11 / 0.12 | -1.23 / 0.10 |
| SOL | -1.15 / 0.21 | -0.97 / 0.18 | -0.94 / 0.22 | -0.89 / 0.23 | -0.90 / 0.22 |
| TRX | -1.72 / 0.00 | -1.72 / 0.00 | +0.00 / 0.00 | -1.72 / 0.00 | +0.00 / 0.00 |
| XRP | -0.89 / 0.34 | -0.72 / 0.31 | -0.70 / 0.33 | -0.71 / 0.31 | -0.71 / 0.30 |

Hasil lama (SL = -1R tanpa fee, BE setengah biaya) terlalu optimis dan tidak dipakai lagi.

**Kesimpulan:**
- Semua pair dan semua mode masih **negatif** (avg R < 0). Exit saja belum membuat profit.
- Di dalam tiap pair, exit modes tetap memperbaiki (lebih sedikit rugi) dibanding `classic`.
- `trail` paling konsisten terbaik. `early` tanpa refinement momentum sering memperburuk (ADA, DOGE, SOL tidak lebih baik dari `trail`).
- Entry long-only tetap -EV. Jangan naikkan `min_score_long` sebagai solusi (sudah terbukti memperburuk).

**Keterbatasan (wajib dibaca sebelum memakai angka ini):**
- Banyak trade `TIMEOUT` dikeluarkan dari statistik. Contoh XRP full: 28 dari 155. BNB, BTC, ETH bahkan lebih dari separuh. Ini bias seleksi, angkanya bisa terlalu baik atau terlalu buruk.
- Win rate kurang akurat untuk mode `early` dan `tp1_be`. Exit BE di r = 0 dihitung LOSS, karena klasifikasi memakai `r > 0`. Perlu label BREAKEVEN terpisah.
- TRX hampir tidak menghasilkan trade (0 hingga 2 resolved). Jangan dipakai sebagai bukti.
- Pair yang dicoba berbeda dengan `tokocrypto_v3` (14 hari, 5 pair) di app. Angka belum tentu sama.

## Pemetaan tujuan ke kode app (2026-10-09)

| Tujuan | Ada di app? | Lokasi | Gap |
|---|---|---|---|
| 1. Hard SL | Ya | `stopLossPrice`, default entry × 0,99 | Tidak fee-aware |
| 2. TP1 partial + BE | Sebagian | `markTp1Triggered` | Hanya set flag, SL tidak digeser ke BE |
| 3. TP2 | Ya | Auto-sell TP2 | Pakai harga kotor |
| 4. Trailing dari peak | Ya | `calculateTrailingLimitPrice` | Aktif begitu di-enable, belum menunggu net ≥ trigger |
| 5. Early profit SELL | Tidak | — | Belum ada, termasuk cek momentum (EMA/RSI) |
| 6. Time-stop | Tidak | — | Belum ada |

Fee harus memakai `TradingFeeConfig` per bursa. Tokocrypto (0,10/0,10) dan Indodax (0,21/0,42) tidak boleh memakai satu angka yang sama.

## Port ke app (langkah berikutnya)

1. Samakan perilaku `resolve_trade` di replay dengan monitor posisi di app. Lokasi yang benar: `TradingViewModelAlerts.kt` (fungsi auto-sell, sekitar baris 236–290), `PositionCoordinator`, dan trailing di `TradingForegroundService`. `SignalLifecycleManager` hanya mengurus sinyal BUY, jadi bukan tempat exit.
2. Early profit harus cek profit **net** + momentum (EMA/RSI), bukan sekadar “hijau di chart”.
3. Output `SignalAction.SELL` dengan `exit_reason` jelas untuk UI/notifikasi.
4. Entry tetap long-only sampai ada desain short terpisah; fokus dulu exit long.

## Gerbang sebelum port ke app

Setting exit tidak boleh di-port ke engine app sebelum lolos semua syarat berikut:

- Diuji di kedua dataset: `tokocrypto_v2` (naik) dan `tokocrypto_v3` (turun), per pair.
- avg R positif setelah fee dan slippage.
- Profit factor minimal 1,2.
- Minimal 30 trade resolved.
- Trade TIMEOUT tidak boleh dibuang diam-diam. Harus dihitung ke statistik atau dilaporkan terpisah.

Kalau belum lolos, setting tidak di-port.

## Catatan tool

- `tools/scalp_replay.py` di repo harus berisi implementasi `resolve_trade` + mode exit (bukan PLACEHOLDER).
- Config exit sudah ada di `data/config/scalp_config_exit_{classic,tp1_be,early,trail,full}.json`.
- Workbench lokal agen: `/home/workdir/artifacts/scalp_run/` punya script + hasil uji.
