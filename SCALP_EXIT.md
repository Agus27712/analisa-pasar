# Scalping Exit Strategy (Spot) — 2026-10-09 · disinkronkan 2026-10-12

Dokumen ini adalah **sumber kebenaran tujuan** untuk agen yang melanjutkan kerja scalping exit. Baca bersama `AGENTS.md` dan `APP_CONTEXT.md`.

> **Status sinkronisasi 2026-10-12:** port ke engine paper **selesai** (P2). Lihat §"Pemetaan tujuan
> ke kode app" dan §"Port ke app". Gerbang kini dibedakan jelas antara **paper** (tidak ada gate
> profit) dan **uang riil** (wajib lolos gate). `max_hold_bars_m1` disamakan menjadi **90**.

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

## Replay offline (paritas engine — sudah di-port ke Kotlin)

Tool: `tools/scalp_replay.py`

Config: `data/config/scalp_config_exit_*.json` → objek `exit`:

| mode | Perilaku |
|------|----------|
| `classic` | SL / TP2 saja (baseline lama) |
| `tp1_be` | TP1 → partial + SL ke BE; lalu TP2/SL |
| `early` | Early SELL (min net profit + momentum lemah) |
| `trail` | Trailing % di bawah peak setelah BE trigger |
| `full` | Kombinasi tp1_be + trail + early |

Parameter `exit` (nilai efektif per 2026-10-12, disamakan antara kode app dan JSON):
- `min_early_profit_net_pct` (default **0.45**)
- `breakeven_trigger_net_pct` (default **0.40**)
- `trail_pct` (default **0.35**)
- `max_hold_bars_m1` (default **90** = time-stop 90 bar M1; `0` = nonaktif/hanya lookahead)

> **Catatan sinkronisasi:** `max_hold_bars_m1` semula `0` (tanpa time-stop), kini **90** —
> disamakan di tiga tempat: `ScalpExitParams.DEFAULT_MAX_HOLD_BARS_M1` (`ScalpExitConfig.kt`),
> blok `exit` di kelima `data/config/scalp_config_exit_*.json`, dan default replay
> `tools/scalp_replay.py`. Sebelum disamakan, paper trade di app dan replay offline
> menghasilkan angka berbeda untuk deret bar yang sama.

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

> **Status keterbatasan (2026-10-12):** dua keterbatasan pertama sudah **ditutup di sisi app** oleh
> P2 — `PaperTradeStats` tidak lagi membuang TIMEOUT diam-diam dan memberi label **BREAKEVEN**
> terpisah untuk r = 0. Tabel angka di atas tetap **tidak dihitung ulang** dan masih memakai
> konvensi lama, jadi jangan dibandingkan langsung dengan keluaran `PaperTradeStats` atau
> replay terbaru tanpa menghitung ulang dengan `max_hold_bars_m1 = 90`.

## Pemetaan tujuan ke kode app (diperbarui 2026-10-12 — P2 selesai)

Dua jalur exit hidup berdampingan. **Engine paper (P2) memegang exit untuk posisi simulasi**;
jalur lama (`positionStore`) tetap melayani posisi uang riil dan posisi yang tidak dipegang paper.

| Tujuan | Engine paper (P2) | Jalur lama (`positionStore`) |
|---|---|---|
| 1. Hard SL | ✅ `PaperExitResolver` — SL net (fee-aware) | ✅ `stopLossPrice` (belum fee-aware) |
| 2. TP1 partial + BE | ✅ TP1 50% + geser SL ke BE net | ⚠️ `markTp1Triggered` hanya set flag |
| 3. TP2 | ✅ Target penuh net | ✅ auto-sell TP2 (harga kotor) |
| 4. Trailing dari peak | ✅ setelah trigger net ≥ `breakeven_trigger_net_pct` | ✅ `calculateTrailingLimitPrice` (aktif begitu di-enable) |
| 5. Early profit SELL | ✅ `min_early_profit_net_pct` + momentum M1 | ❌ tidak ada |
| 6. Time-stop | ✅ `max_hold_bars_m1` = 90 bar M1 | ❌ tidak ada |

**Lokasi kode engine paper:** `PaperExitResolver.kt`, `PaperExitStateMachine` /
`PaperAutoTradeCoordinator.kt` (evaluasi per-tick), `PaperTradeStats.kt`, `PaperTradeJournalStore.kt`,
UI `PaperExitFsmCard` + `PaperTradeStatsCard`.

**Kepemilikan exit tunggal:** saat engine paper aktif untuk sebuah pair, jalur auto-sell lama
dikunci untuk pair itu (`PaperAutoTradeRuntime.ownsExit()` → `paperEngineOwnsExit()`), termasuk
tombol trailing manual di `DetailChartScreen`. Penjaga **hanya** berlaku untuk `isReal = false`;
jalur uang riil tidak tersentuh.

Fee memakai `FeeSchedule` per bursa (SSOT di `config/AppConfiguration.kt`, didokumentasikan di
`FEE_TRUTH.md`). Tokocrypto USDT all-in 0,4044%/sisi; IDR 0,1222–0,4322%; Indodax IDR
0,11–0,42% — tidak boleh memakai satu angka yang sama untuk semua bursa.

## Port ke app — [SELESAI ✅ untuk jalur paper]

Keempat langkah port sudah dikerjakan dan masuk P2:

1. ✅ Perilaku `resolve_trade` di replay disamakan dengan monitor posisi di app, diimplementasikan
   sebagai `PaperExitResolver` + state machine per-pair di `PaperAutoTradeCoordinator`
   (bukan di `SignalLifecycleManager`, yang hanya mengurus sinyal BUY).
2. ✅ Early profit memakai profit **net** (fee all-in + slippage) + momentum M1, bukan "hijau di chart".
3. ✅ `exit_reason` jelas (`SL`, `TP2`, `BE_SL`, `TRAIL_SL`, `EARLY_PROFIT`, `TIMEOUT`) dipakai
   untuk UI (FSM + kartu statistik) dan jurnal.
4. ✅ Entry tetap long-only; fokus exit long.
5. ✅ **Rehidrasi setelah restart (2026-10-13).** State machine exit paper hidup di memori
   (`PaperAutoTradeRuntime`), sedangkan posisi + `auto_sell_enabled` persisten di
   `SpotPositionStore`. Sebelumnya posisi **menggantung** setelah app di-kill: engine paper
   kosong, jalur auto-sell lama juga mati. Sekarang penanda persisten `_paper_engine` +
   `PaperRehydrate` membangun ulang mesin saat startup. SL asli sinyal kini ikut dipersistenkan
   lewat `setAutoSellParams(stopLossPrice = ...)` — tanpa itu rehidrasi akan memakai SL sintetis
   `entry * 0.99` dan **memalsukan stop-out**. Batasan: `barsHeld`/`isBeArmed`/`isPartialDone`/`peak`
   reset, dan posisi yang dibeli sebelum penanda ada sengaja tidak disentuh. Rincian: `workplan2.md` §12.

**Yang belum di-port dan sengaja ditahan:** auto-execute **uang riil** dari sinyal scalp.
Itu menunggu gate di bawah terpenuhi.

## Gerbang: paper vs uang riil (diperjelas 2026-10-12)

Dokumen ini semula menulis gerbang tanpa membedakan paper dan riil, sehingga terlihat seolah
port ke **paper** pun harus lolos gate — padahal justru sebaliknya: **paper adalah alat untuk
menguji**, dan tidak masuk akal menuntutnya membuktikan edge sebelum dipakai.

| Cakupan | Gerbang | Status |
|---|---|---|
| **Engine paper (decision support)** | Tidak ada gate profit. Boleh berjalan selama hasilnya dilaporkan jujur dan tidak diklaim profitable. | ✅ Aktif (P2) |
| **Auto-execute uang riil dari sinyal scalp** | **Wajib lolos semua** syarat di bawah, diukur dengan fee all-in + slippage. | ⛔ Belum lolos |

Syarat gerbang **uang riil** (tidak berubah dari versi asli):

- Diuji di kedua dataset: `tokocrypto_v2` (naik) dan `tokocrypto_v3` (turun), per pair.
- avg R positif setelah fee dan slippage.
- Profit factor minimal 1,2.
- Minimal 30 trade resolved.
- Trade TIMEOUT tidak boleh dibuang diam-diam. Harus dihitung ke statistik atau dilaporkan terpisah.

### Status gerbang setelah lantai friksi (2026-10-12)

Bukti baru: `data/tokocrypto_live_usdt` — **40 pair, 30 hari M1 riil**, config
`scalp_config_exit_full.json` (lantai friksi 6×, target 2,0). Reproduksi dengan
`tools/universe_replay.py` + `tools/split_validate.py`.

| Uji | Hasil |
|---|---|
| Sebelum lantai friksi (baseline) | **0/28** pair avg R > 0; avg R terbobot **−1,2177** |
| Sesudah (dataset penuh 30 hari) | **28/32** pair avg R > 0; avg R terbobot **+0,0325** |
| Train 70% | avg R **+0,0350**, 28/32 pair positif |
| **Test 30% (out-of-sample)** | avg R **+0,0216**, 23/30 pair positif → **split LOLOS** |
| Gate ketat in-sample (avgR>0 ∧ PF≥1,2 ∧ n≥30) | 21/40 pair |
| **Gate ketat out-of-sample (di test)** | **5/40 pair** — APT, NEAR, SU, W, ZRO |

**Status: BELUM LOLOS untuk uang riil — meski jauh lebih baik daripada sebelumnya.**

Alasan utamanya bukan lagi "rugi", melainkan **profil skew negatif yang tipis**.
Distribusi exit dari 2.550 trade:

| Exit | Trade | Porsi | WR | avg R |
|---|---:|---:|---:|---:|
| `TRAIL_SL` (SL sudah di BE/peak) | 2.263 | 88,7% | 100% | **+0,090** |
| `EARLY_PROFIT` | 163 | 6,4% | 100% | +0,144 |
| `SL` (stop keras) | 124 | 4,9% | 0% | **−1,156** |

Menang kecil (+0,09R), kalah besar (−1,16R). Titik impas butuh win-rate ≈ 92,8%; engine
mencapai 95,1% — **margin hanya ~2,3 poin persentase**. Satu pergeseran kecil pada laju
kena stop (mis. karena latensi, fill parsial, atau rezim volatilitas berbeda) sudah cukup
untuk membalik tanda. Drawdown per pair juga dalam: **11,9R** (QNT), **10,5R** (MUBARAK),
dan STRK butuh ~2.000 trade untuk pulih dari DD 4,3R pada avg R +0,002.

**Konsekuensi:** auto-execute uang riil tetap **dilarang**. Yang perlu ditambahkan sebelum
lolos: (a) gerbang pair berbasis edge — hanya pair yang median MFE horizon-nya ≥ beberapa
kali friksi yang boleh ditradingkan (lihat kolom "capture wajib" di `data/hasil/u3_*`:
BTC 193%, XAUT/PAXG >400% = mustahil profit); (b) verifikasi walk-forward lintas bulan;
(c) preferensi maker untuk menurunkan friksi (IDR maker 0,61% vs USDT 0,97%).

**Catatan IDR Tokocrypto.** 14 pair IDR hanya menghasilkan 96 trade dalam 30 hari, dan gate
out-of-sample-nya hanya punya **6 trade di test** — terlalu tipis untuk disimpulkan
(`data/hasil/u5_toko_idr.*`, `u6_split_toko_idr.*`). Pair IDR Tokocrypto terlalu tidak likuid
untuk filter engine saat ini; jangan pakai angkanya sebagai bukti apa pun.

**Catatan Indodax.** Belum ada data historis sama sekali di `data/`, jadi jalur Indodax
(TAPI v2) belum bisa diuji. Sesuai arahan owner, Tokocrypto dimaksimalkan lebih dulu.

## Catatan tool

- `tools/scalp_replay.py` di repo berisi implementasi `resolve_trade` + mode exit yang lengkap
  (bukan PLACEHOLDER), dan sudah diverifikasi independen terhadap `PaperExitResolverParityTest`.
- Config exit ada di `data/config/scalp_config_exit_{classic,tp1_be,early,trail,full}.json`
  (`max_hold_bars_m1` = 90 di kelimanya).
- Harness riset entry terpisah: `tools/entry_hypothesis_lab.py` + `tools/selftest_entry_lab.py`
  (hasil P3: 0/8 lolos gate — lihat `data/hasil/p3_entry_hypothesis_lab.md`).
- Workbench lokal agen `/home/workdir/artifacts/scalp_run/` bersifat **lokal/efemeral** — bukan
  bagian repo, jangan dijadikan rujukan permanen. Artefak yang perlu dipertahankan disimpan di
  `data/hasil/`.
