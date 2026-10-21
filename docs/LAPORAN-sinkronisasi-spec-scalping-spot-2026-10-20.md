# Laporan Sinkronisasi Spec Scalping Spot v2

> **Status:** dokumentasi tahap awal. **Kode belum disentuh.**
> Laporan ini memetakan **Spec v2** ke codebase, mencatat hasil verifikasi, dan menetapkan urutan
> kerja. Laporan versi pertama (untuk Spec v1) sudah digantikan seluruhnya oleh dokumen ini.
>
> **Status 2026-10-21:** Spec v3 sudah dibuat, diukur, dan **dihentikan** owner; commit v3
> dibuang dari `patch`. Dokumen ini tetap menjadi jejak keputusan Spec v2. Lihat
> `workplan2.md` §63 dan branch `backup/patch-v3-2026-10-21` (`796a9d1`) untuk arsip v3.

| | |
|---|---|
| Tanggal | 2026-10-20 |
| Branch | `patch` |
| HEAD saat dokumen ditulis | `1498c02` |
| Sumber spec | `docs/exp/scalping_spot_strategy_spec.md` |
| Versi spec | **v2** (menggantikan v1) |
| Ukuran sumber | 649 baris |
| SHA-256 sumber | `1c5f5e1b5e5434e5bfbaa2b9a6463c30b5b4425db8b787fbb950271a732d7ecf` |
| Status berkas spec | belum di-commit (untracked) |
| Lingkup | Tokocrypto saja; Indodax tidak disentuh |
| Mode yang disiapkan | BACKTEST dan PAPER; LIVE tidak dibangun |

---

## 1. Kesimpulan utama

Spec v2 adalah definisi engine long-only baru, bukan tambahan kecil pada engine scalping yang ada.
Spec ini sudah memuat keputusan penting yang sebelumnya masih berupa usulan:

- **Taker-only.** Maker/limit hanya tersisa sebagai `TODO(spec)` dan tidak diaktifkan.
- **Fee dari `FeeSchedule`.** Tidak ada konstanta 0,10% di spec v2.
- **Lantai friksi 6× dan `PairEdgeGate`** sudah menjadi gerbang resmi (G0 dan G1).
- **Timeframe M15/H1**, dengan M5 hanya on-demand. Alasan yang dicatat spec: friksi sekitar 1%
  membuat target sub-2% tidak layak.
- **Gerbang G0–G8 berurutan** dengan kode alasan dan **funnel counter**.
- **Engine baru di balik flag** `LEGACY`/`SPOT_SPEC`, default `LEGACY`.
- **Jurnal tabel baru** dengan migrasi Room, bukan menimpa tabel lama.

Yang belum selesai bukan lagi pemilihan arah, melainkan:

1. enam koreksi kecil di spec agar cocok dengan API kode yang benar-benar ada;
2. lima temuan kritis yang memengaruhi validitas backtest dan integrasi;
3. implementasi Fase 1 dan seterusnya.

---

## 2. Aturan yang tidak boleh dilanggar

Aturan berikut diambil dari `AGENTS.md` dan sudah tercermin di Spec v2:

- **Tokocrypto saja.** Tidak ada perubahan pada jalur Indodax, termasuk cache, ticker, WebSocket,
  watchlist, dan config Indodax.
- **Taker-only.** Entry, TP, dan SL dianggap taker + slippage.
- **Satu rumus P&L.** P&L, break-even, `costBasisNet`, dan `netSellValue` memakai `FeeSchedule`.
  `FeeCalculator.roundTrip` hanya untuk R:R, bukan untuk P&L.
- **Lantai friksi 6× dan `PairEdgeGate` tetap berlaku.** Pelemahan hanya boleh lewat replay baru
  dan keputusan owner.
- **No lookahead.** Sinyal hanya dari candle yang sudah close. Candle setelah `i` tidak boleh dibaca.
- **Tidak ada dua engine yang tampil bersamaan.** Satu simbol hanya punya satu sinyal aktif, satu
  kartu status, dan satu mesin exit.
- **Tidak ada LIVE.** Fase 8 spec tidak dikerjakan.
- **Kontrol negatif wajib.** Untuk setiap gerbang dan perbaikan bug, matikan gerbang/perbaikan itu
  dan pastikan tes yang relevan gagal.
- **Angka negatif wajib dilaporkan.** Funnel dan hasil backtest harus menunjukkan gerbang mana yang
  membunuh sinyal, bukan hanya hasil akhir yang bagus.

---

## 3. Ringkasan Spec v2

| Bagian | Isi |
|---|---|
| Pasar | Spot, long-only, tanpa leverage |
| Timeframe | Eksekusi default **M15**, HTF **H1**; opsi H1/H4; M5 on-demand |
| Warm-up | 150 candle eksekusi; 60 candle HTF |
| Setup | `PULLBACK_VWAP`, `BREAKOUT_RETEST`, `SWEEP_RECLAIM`, `RANGE_BOUNCE`, `SQUEEZE_BREAKOUT` |
| Entry | `CONFIRM_BREAK` (stop-buy), `MARKET_NEXT_OPEN`; `LIMIT_AT_LEVEL` nonaktif |
| Gerbang | G0 `PairEdgeGate`, G1 lantai friksi, G2 SL ATR, G3 ATR, G4 R:R TP1, G5 R:R final, G6 reward/friksi, G7 struktur, G8 spread |
| Exit | SL, TP1 partial, TP2, breakeven, trailing EMA9/higher-low/Chandelier, setup invalidation, momentum fail, time-stop, regime change |
| Risiko | 0,5% equity per trade; maksimal 2 posisi; rugi harian 2%; 3 loss beruntun pause 60 menit; cooldown simbol 15 menit |
| Sizing | Berbasis jarak entry–SL, fee, equity, saldo; lot step; minNotional |
| TP1 | `tp1Fraction = 0.6` sebagai parameter, uji sensitivitas 0,5 versus 0,6 |
| Backtest | Agregasi CSV 1m, aturan fill, SL sebelum TP, funnel, metrik per setup/simbol/regime, split 70/30 |
| Jurnal | Tabel Room baru; snapshot indikator, fill, exit reason, fee, PnL, R, MFE/MAE |
| Fase | 8 fase; satu fase per sesi; Fase 8 tidak dikerjakan |

---

## 4. Pemetaan Spec v2 ke codebase

| # | Area spec | Kondisi codebase sekarang | Status |
|---|---|---|---|
| 1 | Model `SpotCandle`, `SpotSignal`, `SpotTrade` | `CandleBar` ada; belum ada model Spot dengan `PENDING_ENTRY`, fill, dan `stopInitial` | Perlu model domain baru |
| 2 | 5 setup | `ScalpSetupType` hanya 4: `BREAKOUT`, `BREAKOUT_RETEST`, `LIQUIDITY_SWEEP`, `TREND_PULLBACK` | 3 setup belum ada; 2 setup berbeda definisi |
| 3 | `SpotIndicatorSeries` | `IndicatorMath` berbasis TA4J; seed, Wilder, VWAP, Bollinger/Keltner, percentile, volMA belum sesuai definisi spec | Perlu seri indikator murni baru; jangan ubah `IndicatorMath` |
| 4 | `SpotLevelProvider` | `MarketStructureAnalyzer` punya swing/support/resistance terbatas; belum ada equal low, PDH/PDL, Asia low | Perlu level provider baru |
| 5 | `SpotRegimeClassifier` | `MarketRegimeEngine` punya 6 state berbeda | Perlu classifier terpisah |
| 6 | Fee | `FeeSchedule` sudah per bursa/quote; `FeeCalculator.roundTrip` untuk R:R | Spec v2 sudah benar; tinggal adapter |
| 7 | Lantai friksi | `PaperStopResolver.frictionFloorPct` + `resolve` adalah fungsi kanonik | Spec v2 masih menyebut kelas yang salah (lihat §5) |
| 8 | Sizing | `PaperAutoTradeConfig` punya `RISK_BASED`, tetapi `riskPerUnit` belum memasukkan fee dan belum memakai lot step/minNotional | Perlu `SpotPositionSizer` |
| 9 | Exit | `PaperExitResolver`: TP1 50%, time-stop 90 bar, trailing lama | Perlu `SpotPositionManager`; jangan dicampur |
| 10 | Pending entry | Belum ada state `PENDING_ENTRY` di paper runtime | Perlu state dan aturan kedaluwarsa/pembatalan |
| 11 | Backtest | `BacktestEngine` hanya baseline SMA breakout | Belum ada backtester spec |
| 12 | Replay | `tools/scalp_replay.py` mencerminkan engine lama | Engine baru butuh backtester/paritas sebelum paper |
| 13 | Data | M15/H1/H4/M1 sudah di-prefetch; M5 dikenal tetapi tidak di-prefetch | M15/H1 siap; M5 on-demand sesuai spec |
| 14 | Jurnal | `signal_logs` dan `trade_history_records` ada; `AppDatabase` versi 9 | Perlu tabel baru + migrasi |
| 15 | UI/notifikasi | Kartu scalping membaca `ScalpingSignal` lama | Adapter UI hanya pada fase integrasi |
| 16 | `PairEdgeGate` | Ada API `evaluate(pair, quote, friction)`; tabel MFE hanya USDT | IDR tidak eligible (lihat §6) |
| 17 | Indodax | Jalur Indodax aktif di aplikasi | Tidak boleh disentuh |

---

## 5. Hasil verifikasi Spec v2 terhadap kode

### 5.1 Yang sudah cocok

- **Rumus F konsisten.** `PairEdgeGate.frictionRoundTripPct` = beli + jual + 2×slippage =
  **0,9688%** (USDT) dan **0,8144%** (IDR). Angka ini sama dengan `pair_gate.json` dan
  `FeeSchedule` (`0,4044 + 0,4044 + 0,16` dan `0,2222 + 0,4322 + 0,16`).
- **Rumus R:R spec setara dengan `FeeCalculator.roundTrip`.** Untuk entry 100, SL 5,8128%, TP1 1R:
  hitungan aditif spec = **0,714286**; hitungan `FeeCalculator.roundTrip` = **0,713094**. Selisih
  ini kecil dan tidak memerlukan rumus fee kedua.
- **M15/H1 sudah tersedia** di daftar prefetch `MtfCacheManager` (H1, M15, M1, H4). M5 memang hanya
  dikenal di `isCacheValid`, belum di-prefetch.
- **`SpotPositionStore` ada** di `agu/analys/trading/SpotPositionStore.kt`, jadi rujukan Fase 7 valid.
- **Candle app dan tool offline berasal dari API yang sama.** `TokocryptoMarketService.fetchCandles`
  memakai interval `15m`/`1h`; `tools/fetch_tokocrypto_klines.py` memakai host
  `https://www.tokocrypto.site/api/v3` (bukan binance.com).
- **Format CSV cocok untuk agregasi.** Header: `open_time_ms,open,high,low,close,volume`.

### 5.2 Yang perlu dikoreksi di spec

1. **G1 menyebut kelas yang salah.** `ScalpingRiskEngine` tidak punya fungsi `(entry, sl)` menjadi
   SL efektif; yang ada adalah `evaluate(input)` untuk seluruh pipeline legacy. Fungsi kanonik lantai
   adalah `PaperStopResolver.frictionFloorPct(buyFee, sellFee, slippage, multiple)` lalu
   `PaperStopResolver.resolve(signalSl, entry, floorPct)`.
   **Tindakan:** ubah teks G1 agar memanggil dua fungsi itu, bukan `ScalpingRiskEngine`.
2. **P&L dan break-even menyebut `FeeCalculator`, padahal tidak ada.** `FeeCalculator` hanya punya
   `roundTrip` (helper R:R). Break-even harus memakai `FeeSchedule.breakevenPrice`; P&L memakai
   `FeeSchedule.netProfitPctForDirected`, `costBasisNet`, dan `netSellValue`.
   **Tindakan:** ganti "menurut `FeeCalculator`" di §7, §10, dan §14.6 dengan `FeeSchedule`.
3. **`SymbolRules` dan `SpotHtfSnapshot` belum ada.** Keduanya harus dibuat di domain. `SymbolRules`
   diisi dari `TokocryptoSymbolInfo`: `priceFilter.tickSize`, `lotSizeFilter.stepSize`,
   `lotSizeFilter.minQty`, dan `minNotionalFilter.minNotional`.
4. **`perUnitRisk` aditif meleset 0,42%.** Contoh entry 100, SL 94,1872, F 0,9688%: rumus aditif
   menghasilkan 6,7816, sedangkan risiko net multiplikatif 6,7534. Tidak fatal karena kriteria
   penerimaan mengukur risiko aktual, tetapi pembulatan qty harus memakai risiko net nyata dari
   `FeeSchedule`, bukan angka aditif.
5. **Relasi antar berkas.** Spec v2 berada di `docs/exp/scalping_spot_strategy_spec.md`; laporan ini
   berada di `docs/`. Rujukan di §3 spec ke laporan ini sudah benar. Yang perlu diselaraskan hanya
   deskripsi tautan di `docs/PANDUAN-LANJUT-AGEN.md`.
6. **Config cermin.** `AGENTS.md` menetapkan satu berkas config cermin
   (`data/config/scalp_config.json`) yang dikunci uji. `SpotConfig` tidak boleh menjadi config kedua
   tanpa kunci uji; atau biarkan murni di kode dan ekspor JSON hanya untuk offline.

---

## 6. Temuan kritis

### 6.1 TP1 = 1R bertentangan dengan G4

Spec §6.1 mewajibkan `netRr1 >= 1.0`, dengan:

```
netRr1 = (reward1Pct - F) / (riskPct + F)
```

Jika `tp1` didefinisikan sebagai `entry + 1.0R` (seperti di §8.2, §8.4, §8.5, dan syarat minimum
§8.3), maka `reward1Pct = riskPct = R`, sehingga:

```
netRr1 = (R - F) / (R + F) < 1     untuk setiap F > 0
```

Jadi konflik ini **tidak bergantung pada lantai 6×**. Dengan F = 0,9688% dan SL di lantai 6×
(5,8128%), TP1 harus minimal `R + 2F` = **7,7504%** atau sekitar **1,333R** agar lolos G4.

Spec §6.2 sendiri sudah mencatat angka ini ("TP1 harus ≥ ±7,75% (8F)"), tetapi §8.2 sampai §8.5
tetap menulis `tp1 = entry + 1.0R`. Jadi yang perlu diperbaiki adalah definisi TP1 di bagian setup,
bukan gerbang G4-nya.

Angka pembanding:

- SL 5,81%, TP1 2,9% → netRr1 = 0,28 (ditolak);
- SL 5,81%, TP1 8,0% → netRr1 = 1,04 (lolos).

**Tindakan:** TP1 tetap di level struktural, lalu G4 yang menolak bila jaraknya kurang dari `R + 2F`.
Jangan meregangkan TP1 hanya agar lolos. Konsekuensinya jumlah sinyal bisa sangat kecil; itu harus
terlihat di funnel, bukan disembunyikan.

### 6.2 G0 membuat seluruh data lokal tidak bisa dipakai

`data/config/pair_gate.json`:

- USDT: 40 pair, **14 eligible** (ZRO, NEAR, MUBARAK, PUMP, W, STRK, ENA, QNT, PLUME, APT, SUSDT,
  FET, WLD, FIL).
- IDR: 14 pair, **0 eligible**.

Delapan pair yang ada di data lokal semuanya tidak eligible:

| Pair | Rasio MFE/friksi | Eligible |
|---|---|---|
| ADAUSDT | 1,6378 | tidak |
| DOGEUSDT | 1,0334 | tidak |
| XRPUSDT | 0,9954 | tidak |
| SOLUSDT | 0,9056 | tidak |
| BNBUSDT | 0,6552 | tidak |
| ETHUSDT | 0,6475 | tidak |
| BTCUSDT | 0,5178 | tidak |
| TRXUSDT | 0,2423 | tidak |

**Tindakan:** data 14 pair eligible harus diunduh sebelum backtest. Riset praktis menjadi
**USDT-only** sampai tabel MFE IDR dibuat ulang dengan data yang cukup.

### 6.3 Volume data belum memenuhi syarat statistik spec

Data lokal terbaik adalah `data/tokocrypto_v4`:

- 87.000 bar M1 per pair;
- rentang 2026-08-12 sampai 2026-10-12 (sekitar 60 hari);
- 8 pair USDT (semuanya tidak eligible G0).

Di M15, 60 hari hanya sekitar **5.760 candle eksekusi per pair**. Spec meminta minimal **100 trade**
per setup dan split 70/30 atau walk-forward. Jumlah itu belum cukup, apalagi setelah G0.

**Tindakan:** unduh 14 pair eligible, interval 15m dan 1h langsung dari bursa (jauh lebih murah
daripada 1m), rentang minimal 90 hari, ke folder baru seperti `data/tokocrypto_spot`. Jangan
mengandalkan agregasi 1m untuk M15/H1 karena candle 15m/1h bursa adalah yang benar-benar dibaca app.

### 6.4 Tabel MFE dibangun dari 7 jam M1, bukan M15

`PairEdgeGate` memakai `MFE_HORIZON_BARS_M1 = 420` (7 jam) dari data M1. Untuk eksekusi M15,
pergerakan MFE dalam 7 jam tetap 7 jam, tetapi lintasan M1 bisa lebih ekstrem daripada lintasan M15.
Artinya G0 bisa **terlalu longgar** untuk engine M15. Tabel MFE sebaiknya dihitung ulang dari candle
M15 (atau setidaknya diuji sensitivitasnya) sebelum hasil backtest dianggap sah.

### 6.5 Config cermin dan sumber data MFE

- `PairEdgeGate` memakai tabel MFE yang ditanam di kode (`MFE_7H_USDT`); `pair_gate.json` adalah
  cermin offline untuk `tools/pair_gate.py`. Sumber `data/tokocrypto_live_usdt` yang disebut di JSON
  tidak ada di lokal, jadi regenerasi tabel butuh data baru.
- `SpotConfig` yang dicerminkan ke JSON harus mengikuti aturan satu config cermin di `AGENTS.md`.

---

## 7. Arsitektur target

```text
app/src/main/java/agu/analys/engine/spot/
  model/        SpotConfig, SpotCandle, SpotSignal, SpotTrade, SpotFill,
                SpotSymbolRules, SpotHtfSnapshot, enum setup/regime/exit
  indicator/    SpotIndicatorSeries (EMA, ATR Wilder, RSI, ADX, BB, Keltner, VWAP, volMA)
  level/        SpotLevelProvider (swing/fractal, equal low, PDH/PDL, Asia low)
  regime/       SpotRegimeClassifier
  setup/        PullbackVwap, BreakoutRetest, SweepReclaim, RangeBounce, SqueezeBreakout
  trade/        SpotFeeAdapter, SpotPositionSizer, SpotPositionManager, SpotRiskManager
  backtest/     SpotBacktester, SpotFillEngine, SpotMetrics, SpotFunnel
  journal/      SpotJournal (interface)
```

Prinsip:

- Domain murni Kotlin, tanpa dependensi Android.
- `SpotFeeAdapter` membungkus `FeeSchedule`; `FeeCalculator` hanya untuk R:R.
- `SpotCandleSource` adalah antarmuka; implementasi pertama hanya Tokocrypto.
- `SpotEngine` menerima satu simbol dan hanya candle close.
- `SpotPositionManager` memiliki exit sendiri; jangan dicampur dengan `PaperExitResolver`.
- Model/enum baru terpisah dari `ScalpSetupType` dan `MarketRegime` agar tes engine lama tidak pecah.
- Backtester tinggal di Kotlin; harness Python, bila dibuat, wajib punya uji paritas.

---

## 8. Rencana fase

| Fase | Pekerjaan | Gerbang lolos |
|---|---|---|
| 0 | Dokumentasi, commit spec, baseline test | review owner |
| 1 | Model, config, indikator, helper | tes indikator dan no-lookahead hijau |
| 2 | Regime classifier, `SpotFeeAdapter`, gerbang §6, `SpotPositionSizer` | fee truth dan sizing lolos; ukur distribusi ATR% pair aktif |
| 3 | `SpotPositionManager`, aturan fill, backtester minimal + funnel | SL-sebelum-TP, next-candle, deterministik |
| 4 | `PULLBACK_VWAP` end-to-end | laporan hasil terukur beserta funnel |
| 5 | `BREAKOUT_RETEST`, `RANGE_BOUNCE`, `SQUEEZE_BREAKOUT`, `SWEEP_RECLAIM` | tiap setup punya fixture positif/negatif |
| 6 | Risk manager, jurnal Room, UI | migrasi dan uji UI |
| 7 | Paper Tokocrypto, satu engine aktif | paper versus backtest sebanding; tidak ada duplikasi sinyal/exit |
| 8 | LIVE | **tidak dikerjakan** |

**Sesi implementasi berikutnya: Fase 1 saja.** Isinya:

- `model/SpotConfig.kt`, `model/SpotModels.kt`, `model/SpotSymbolRules.kt`,
  `model/SpotHtfSnapshot.kt`;
- `indicator/SpotIndicatorSeries.kt` dengan definisi Wilder/SMA seed yang eksplisit;
- `level/SpotLevelProvider.kt` dengan fractal, equal low/high, PDH/PDL, Asia low;
- tes golden fixture indikator, tes no-lookahead, dan tes validitas swing.

Belum termasuk: regime, gerbang, sizing, exit, backtester, UI, paper.

---

## 9. Kriteria penerimaan

Mengacu pada §14 Spec v2, dengan koreksi `FeeSchedule`:

1. Indikator cocok dengan golden fixture; toleransi `1e-4` setelah warm-up.
2. No-lookahead: sinyal di `i` identik saat candle `> i` dibuang.
3. Setiap setup punya fixture positif dan negatif.
4. `slEff < entry < tp1 (< tp2)`; jumlah qty exit = qty total; stop tidak pernah turun.
5. SL dan TP kena di candle yang sama menghasilkan `STOP_LOSS`.
6. P&L dan break-even sama persis dengan `FeeSchedule`; tidak ada rumus fee kedua.
7. Position sizing: risiko aktual termasuk fee tidak lebih dari `riskAmount`; qty dibulatkan ke
   `lotStep`; nilai tidak di bawah `minNotional`.
8. Risk manager memblokir entry setelah batas rugi harian dan setelah 3 loss beruntun.
9. Backtest deterministik: data sama menghasilkan hasil identik.
10. Kontrol negatif: mematikan tiap gerbang/perbaikan membuat tes relevan gagal.
11. Setiap simbol hanya punya satu sinyal aktif dan satu mesin exit.

---

## 10. Data unduhan 2026-10-20

Dataset baru untuk mengukur Spec v2 ada di `data/tokocrypto_spot/`. Tujuannya mengukur dua kondisi
pasar yang berbeda: pair yang tenang (baseline) dan pair yang sedang naik tinggi (menguji apakah
setup bertahan saat momentum berbalik).

### 10.1 Cara memilih pair

Sumber statistik 24 jam adalah endpoint Tokocrypto sendiri (`/api/v3/ticker/24hr`). Bila endpoint
itu tidak terbaca, tool memakai snapshot cadangan `data/config/binance_24h_movers_reference.json`
dan mencatatnya di manifest. Candle selalu diunduh dari Tokocrypto; tool tidak memanggil Binance.

| Bucket | Kriteria | Jumlah |
|---|---|---|
| eligible | `pair_gate.json`, kuotasi USDT, eligible | 14 |
| stabil | volume 24 jam ≥ 1.000.000 USDT, mutlak perubahan 24 jam ≤ 2%, rentang 24 jam ≤ 3%, stablecoin dikecualikan | 12 |
| momentum | volume 24 jam ≥ 1.000.000 USDT, perubahan 24 jam ≥ 5% | 15 |
| **total unik** | | **41** |

- eligible: ZROUSDT, NEARUSDT, MUBARAKUSDT, PUMPUSDT, WUSDT, STRKUSDT, ENAUSDT, QNTUSDT,
  PLUMEUSDT, APTUSDT, SUSDT, FETUSDT, WLDUSDT, FILUSDT.
- stabil: BTCUSDT, ETHUSDT, SOLUSDT, XRPUSDT, BNBUSDT, TRXUSDT, XAUTUSDT, DOGEUSDT, LINKUSDT,
  HBARUSDT, ADAUSDT, NVDABUSDT.
- momentum: GNSUSDT, ALTUSDT, GTCUSDT, PHAUSDT, BEAMXUSDT, BMTUSDT, ERAUSDT, SOXSBUSDT, BATUSDT,
  METISUSDT, REZUSDT, AUSDT, IOUSDT, TIAUSDT, COMPUSDT.

Angka 24 jam hanya dipakai untuk **memilih** pair, bukan sebagai data pengukuran. Data pengukuran
adalah 90 hari candle 15m dan 1h.

### 10.2 Hasil unduhan (terukur)

| Item | Angka |
|---|---|
| Jendela | 2026-07-22 12:09 sampai 2026-10-20 12:09 UTC (90 hari) |
| Interval | 15m dan 1h |
| Berkas | 82 CSV (41 pair × 2 interval) |
| Bar | 441.880 |
| Celah | 0 |
| Duplikat | 0 |
| Ukuran folder | 23,32 MB |

Catatan cakupan: data `SOXSBUSDT` baru tersedia sejak 2026-07-29, sehingga hanya 7.968 bar 15m dan
1.992 bar 1h. Pair lain 8.639 bar 15m dan 2.159 bar 1h; angka itu bukan 8.640/2.160 karena candle
terakhir belum close saat unduhan.

### 10.3 Verifikasi independen

Tiga pair (BTCUSDT, GNSUSDT, SUSDT) diambil ulang 200 bar dari API lalu dibandingkan dengan CSV:
**0 perbedaan nilai**. `_manifest.json` memuat SHA-256 tiap berkas, jumlah bar, celah, duplikat, dan
contoh celah.

### 10.4 Perintah reproduksi

```sh
python3 tools/fetch_tokocrypto_klines.py --select mixed --list-only --out data/tokocrypto_spot
python3 tools/fetch_tokocrypto_klines.py --select mixed --days 90 --intervals 15m,1h \
    --out data/tokocrypto_spot --resume --write-readme
```

### 10.5 Batas yang harus diingat

- Pemilihan momentum memakai snapshot 24 jam saat unduhan. Pada unduhan pertama, `CRVUSDT`
  sempat masuk (5,24%), lalu turun di bawah ambang 5% beberapa menit kemudian; snapshot final
  berisi 41 pair tanpa `CRVUSDT`. Karena itu tool sekarang membekukan daftar pair saat `--resume`
  dipakai; pakai `--reselect` hanya bila memang ingin mengganti universe.
- Kalau diunduh di hari lain, daftar pair momentum bisa berbeda. `_selection.json` dan
  `_manifest.json` menyimpan daftar dan angka yang benar-benar dipakai.
- Tabel MFE `PairEdgeGate` sudah dihitung ulang pada M15 (§10.6). Hasilnya **4 dari 41 pair layak**,
  bukan 14. Tabel lama kedaluwarsa untuk jendela 90 hari.
- Backtest sudah dijalankan (§10.7). Hasilnya **negatif**: 0 trade pada gates produksi 15m,
  7 trade di 1h dan semuanya rugi; varian riset yang lebih longgar tetap negatif.
- Kedua angka berasal dari harness Python (`tools/spot_backtest.py`), **bukan** mesin Kotlin.
  Spec §11 mensyaratkan mesin Kotlin + uji paritas, jadi angka ini belum boleh disebut hasil
  produksi.

---

### 10.6 Hasil MFE 15m (terukur 2026-10-20)

Laporan lengkap: **`docs/exp/exp_mfe_m15_41pair.md`**. Artefak: `data/hasil/mfe_m15_spot.json`.

Perintah:

```sh
python3 tools/spot_mfe.py --data data/tokocrypto_spot --out data/hasil/mfe_m15_spot.json
```

| Item | Angka |
|---|---|
| Pair diukur | 41 |
| Layak (`median MFE 7 jam >= 2 x 0,9688%`) | **4** |
| Pair yang tabel lama anggap layak | 14 |
| Turun status | **10** (ENA, NEAR, WLD, STRK, W, S, FET, APT, FIL, QNT) |
| Naik status | **0** |
| Median MFE 7 jam lintas pair | 1,4371% |
| Median rasio lintas pair | 1,4834x |

Empat pair yang layak: MUBARAK (2,78x), PUMP (2,58x), ZRO (2,34x), PLUME (2,12x). Semuanya sudah
ada di tabel lama; tidak ada pair baru. Artinya, memperbarui `PairEdgeGate` akan **mempersempit**
universe, bukan memperluas.

Validasi sampling: `step=1` vs `step=2` pada BTC/MUBARAK/ZRO berbeda <0,03 pp, jadi hasil bukan
artefak sampling. Batas: M15 vs M1, 90 hari vs 30 hari, median tanpa syarat setup, tanpa spread.

---

### 10.7 Hasil backtest Spec v2 (terukur 2026-10-20)

Laporan lengkap: **`docs/exp/exp_spot_backtest_41pair.md`**. Artefak:
`data/hasil/spot_backtest_v2_matrix.txt` + JSON/CSV per varian.

Perintah inti:

```sh
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 15m \
    --pairs all --setups all --tp1-policy floor \
    --out data/hasil/spot_backtest_v2_m15_prod
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 1h \
    --pairs all --setups all --tp1-policy floor \
    --out data/hasil/spot_backtest_v2_h1_prod
```

| Varian | n | avg R | WR | Catatan |
|---|---:|---:|---:|---|
| 15m produksi (G0, lantai 6x, gates default) | **0** | - | - | tidak ada trade |
| 15m literal (kontrol TP1) | **0** | - | - | setup formula pasti ditolak G4 |
| 15m no-G0 | 1 | -0,8084 | 0,0% | satu trade, rugi |
| 15m riset longgar (lantai 5x, G3 1,0F, G4/G5/G6 longgar) | 44 | -0,2260 | 22,7% | in-sample -0,1542 / OOS -0,2978 |
| 15m riset longgar (lantai 4x) | 37 | -0,2758 | 21,6% | in-sample -0,1965 / OOS -0,3798 |
| 1h produksi (G0, lantai 6x, gates default) | **7** | -0,3835 | 0,0% | 6 invalidasi, 1 momentum fail |
| 1h produksi, 4 pair MFE baru | 5 | -0,3997 | 0,0% | dibatasi `--pairs`; tabel gate tidak diubah |
| 1h literal | 6 | -0,3841 | 0,0% | setup formula BREAKOUT hilang |
| 1h no-G0 | 13 | -0,4028 | 7,7% | sampel kecil |
| 1h no-G0, lantai 5x | 21 | -0,2970 | 19,1% | |
| 1h no-G0, G4-G6 longgar | 48 | -0,2320 | 18,8% | |
| 1h no-G0, lantai 5x + G4-G6 longgar | 60 | -0,2087 | 23,3% | IS -0,0869 / OOS -0,4046 |
| **1h no-G0, lantai 4x + G4-G6 longgar** | **74** | **-0,2055** | 23,0% | **IS -0,0912 / OOS -0,3828** |
| 1h no-G0, tanpa lantai | 54 | -0,3373 | 20,4% | lantai bukan satu-satunya penyebab |

**Kesimpulan terukur:**

1. Gates produksi tidak bisa dieksekusi pada 15m: 0 trade dari 41 pair dalam 90 hari. Penyebabnya
   aritmetika: lantai 6x (5,8128%) + G2 maksimum 5 ATR + G3 minimum 1,5F (1,4532%) saling
   mengunci, sedangkan ATR% median 15m sebagian besar 0,2–1,1%.
2. Gates produksi 1h menghasilkan 7 trade, semuanya rugi (WR 0%, avg R -0,3835). Sampel terlalu
   kecil untuk expectancy, tetapi cukup untuk menyatakan gates hampir tidak bisa dieksekusi.
3. Seluruh varian riset tetap negatif. Yang "terbaik" (lantai 4x + gates longgar) memberi
   -0,2055R dengan 74 trade; in-sample -0,0912, out-of-sample **-0,3828** (memburuk).
4. SWEEP_RECLAIM adalah sumber kerugian utama dan konsisten rugi di semua konfigurasi
   (6–67 trade, avg R -0,17 sampai -0,40). BREAKOUT_RETEST negatif pada 14–22 trade di 15m
   longgar, tetapi positif pada n=6 di 1h (+0,19 sampai +0,21R); SQUEEZE n=1–7 dengan tanda
   berubah-ubah; PULLBACK hanya 2 trade (keduanya rugi); RANGE 0.
5. Kontradiksi TP1/G4 terbukti: mode `literal` menghasilkan 0 trade untuk setup formula, karena
   `netRr1 = (R − F)/(R + F) < 1` selalu. Mode `floor` adalah satu-satunya cara setup formula lolos.
6. **Rekomendasi: jangan mulai Fase 1 Kotlin dulu.** Perbaiki kontradiksi spec dan rancang ulang
   pemicu/exit SWEEP, atau ubah arah ke horizon lebih panjang. Keputusan ini milik owner.

**Batas:** harness Python, bukan mesin Kotlin; harga fill = level mentah (slippage lewat
`FeeSchedule`); G8/orderbook tidak diuji; risiko per simbol, bukan portofolio; time-stop dan
lookback dalam candle sehingga 1h mengubah horizon; universe dipilih dari jendela yang sama
(bukan out-of-sample); G0 memakai tabel lama yang sudah kedaluwarsa. Uji mekanika
`tools/test_spot_backtest.py` (10 uji) lulus, dan determinisme sudah diverifikasi (SHA-256 CSV
identik pada dua kali jalan).

---

## 11. Verifikasi dan jejak

Karena tahap ini belum menyentuh kode aplikasi, yang dijalankan:

```sh
python3 tools/check_md_text.py
git status --short
wc -l docs/exp/scalping_spot_strategy_spec.md
sha256sum docs/exp/scalping_spot_strategy_spec.md
python3 tools/fetch_tokocrypto_klines.py --select mixed --list-only --out data/tokocrypto_spot
python3 tools/fetch_tokocrypto_klines.py --select mixed --days 90 --intervals 15m,1h \
    --out data/tokocrypto_spot --resume --write-readme
python3 tools/spot_mfe.py --data data/tokocrypto_spot --out data/hasil/mfe_m15_spot.json
python3 tools/test_spot_backtest.py
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 15m \
    --pairs all --setups all --tp1-policy floor \
    --out data/hasil/spot_backtest_v2_m15_prod
python3 tools/spot_backtest.py --data data/tokocrypto_spot --timeframe 1h \
    --pairs all --setups all --tp1-policy floor \
    --out data/hasil/spot_backtest_v2_h1_prod
```

Fakta terukur yang dipakai di laporan ini:

- Spec v2: 649 baris, SHA-256 `1c5f5e1b...d7ecf`.
- Dataset baru: 41 pair, 82 CSV, 441.880 bar, 0 celah, 0 duplikat, 23,32 MB (§10).
- Data lama: `data/tokocrypto_v4` 87.000 bar M1 per pair, 2026-08-12 sampai 2026-10-12.
- `pair_gate.json`: 14 eligible USDT, 0 eligible IDR.
- MFE 15m 90 hari: **4/41 layak**, 10 pair turun, 0 pair naik (§10.6).
- Backtest: 14 varian; produksi 15m **0 trade**, produksi 1h **7 trade semua rugi**
  (avg R -0,3835); varian riset terbaik -0,2055R dengan OOS -0,3828 (§10.7).
- Uji mekanika harness: **10/10 lulus** (`tools/test_spot_backtest.py`).
- Determinisme: dua kali jalan perintah 1h produksi menghasilkan CSV trade dengan SHA-256
  identik; indeks entry minimum 359 (di atas warmup 150).

Yang **belum** dijalankan dan **tidak boleh diklaim**:

- `./gradlew :app:testDebugUnitTest` untuk engine baru, karena engine Kotlin-nya belum ada.
- Uji paritas mesin Kotlin versus harness Python (Spec §11).
- Perbandingan paper versus backtest, karena belum ada integrasi.
- Uji dengan orderbook/G8 live, spread, dan dampak pasar; dataset candle tidak memuatnya.
- Backtest pada universe out-of-sample yang dipilih sebelum jendela pengukuran.

---

## 12. Yang masih terbuka

**Keputusan yang harus diambil owner sebelum Fase 1 Kotlin:**

1. **Lanjut, perbaiki, atau hentikan Spec v2?** Backtest menghasilkan 0 trade di 15m dan 7 trade
   (semua rugi) di 1h pada gates produksi; seluruh varian riset tetap negatif. Usulan penulis:
   jangan implementasikan apa adanya. Pilih salah satu arah: (a) perbaiki kontradiksi gate dan
   rancang ulang SWEEP, (b) pindah ke horizon lebih panjang dengan exit yang sesuai, atau
   (c) hentikan program scalping ini.
2. **Kontradiksi G3/lantai/G4 harus diselesaikan lebih dulu.** Di 15m, lantai 6x (5,8128%) + G2
   maksimum 5 ATR + G3 minimum 1,5F (1,4532%) membuat hampir semua sinyal gugur sebelum R:R
   dihitung. Pilih: pertahankan lantai 6x dan terima bahwa 15m tidak bisa dipakai, atau turunkan
   lantai/G3 dengan bukti replay baru. Jangan menurunkan lantai hanya untuk menambah sinyal.
3. **TP1 formula `entry + 1,0R` harus diganti.** Secara aljabar ia selalu memberi `netRr < 1`,
   sehingga G4 menolaknya. Usulan: tulis eksplisit `entry + max(1,0R, R + 2F)` di spec (mode
   `floor` yang dipakai harness), bukan dibiarkan sebagai kontradiksi.
4. **`PairEdgeGate` diperbarui atau tidak?** MFE 90 hari memberi 4 pair, bukan 14. Usulan:
   jangan perbarui dulu sebagai angka hardcoded; kalau tabel dipertahankan, ia harus dihitung
   berkala. Memperbarui ke 4 pair hanya mempersempit sampel dan tidak menghasilkan edge.
5. **Perluas universe ke 161 pair?** Tidak dulu. Funnel menunjukkan hambatan utama adalah
   aritmetika gate, bukan jumlah pair; menambah pair kebanyakan akan menambah pair ATR rendah yang
   langsung gugur di G2/G3.

**Keputusan desain yang lebih kecil:**

6. Apakah `tp1Fraction` 0,6 dipakai juga saat engine masuk paper, atau diselaraskan ke 0,5 agar
   memakai FSM lama? Usulan: 0,6 di `SpotPositionManager`, FSM lama tidak diubah.
7. Apakah engine baru menggantikan engine lama sepenuhnya setelah lolos, atau tetap menjadi
   alternatif yang bisa dipilih? Usulan: flag `LEGACY`/`SPOT_SPEC`, default legacy sampai Fase 7.
8. Apakah M5 on-demand dapat diterima dari sisi beban API dan baterai? Usulan: ya, hanya untuk
   simbol yang dibuka dan lolos G0.
9. Apakah riset memang USDT-only karena IDR tidak punya pair eligible? Usulan: ya, sampai tabel MFE
   IDR dibuat ulang.
10. Apakah Spec v2 diperbaiki langsung (v2.1) atau diterbitkan sebagai v3? Usulan: v2.1 dengan
    changelog, karena perubahannya hanya koreksi API dan satu konflik TP1/G4 — ditambah keputusan
    gate di atas.
11. Apakah `SpotConfig` boleh punya config cermin sendiri? Usulan: tidak dulu; ekspor JSON hanya
    untuk offline, dengan uji kunci bila nanti dijadikan cermin resmi.
12. Apakah spec tetap di `docs/exp/`? Saat ini ya, sesuai penempatan owner. Catatan: `AGENTS.md`
    menyebut `docs/exp/` untuk eksperimen terukur, jadi lokasi ini perlu keputusan final.

---

## 13. Riwayat

| Tanggal | Peristiwa |
|---|---|
| 2026-10-20 | Spec v1 dipetakan; laporan sinkronisasi pertama dibuat. |
| 2026-10-20 | Spec v2 menggantikan v1. Berkas v2 ditempatkan di `docs/exp/scalping_spot_strategy_spec.md`; laporan ini ditulis ulang untuk v2. |
| 2026-10-20 | Verifikasi v2 terhadap kode: 6 koreksi API dan 5 temuan kritis dicatat. Kode belum disentuh. |
| 2026-10-20 | Tool unduh diperluas: pemilihan pair (eligible/stabil/momentum), jendela tetap, resume dengan daftar pair dibekukan, manifest, README. Dataset `data/tokocrypto_spot` diunduh: 41 pair, 90 hari, 15m dan 1h, 441.880 bar, 0 celah, 0 duplikat. |
| 2026-10-20 | `tools/spot_lib.py`, `tools/spot_mfe.py`, `tools/spot_backtest.py`, dan `tools/test_spot_backtest.py` dibuat. MFE 15m dijalankan: **4/41 layak**, 10 pair turun, 0 pair naik. |
| 2026-10-20 | Backtest Spec v2 dijalankan (14 varian, 15m dan 1h): produksi 15m **0 trade**, produksi 1h **7 trade semua rugi** (avg R -0,3835); varian riset terbaik -0,2055R dengan OOS -0,3828. Laporan: `docs/exp/exp_mfe_m15_41pair.md` dan `docs/exp/exp_spot_backtest_41pair.md`. |
| 2026-10-20 | Uji mekanika harness 10/10 lulus; determinisme CSV diverifikasi. Kode aplikasi tetap belum disentuh. |
