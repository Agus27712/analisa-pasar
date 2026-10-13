# Laporan Eksekusi P0–P2 — Integritas Log Audit

**Tanggal:** 14 Oktober 2026
**Basis:** `LAPORAN-verifikasi-ui-vs-candle-2026-10-14.md` §8 (tabel P0 / P1 / P2)
**Branch:** `patch`
**Status:** ✅ Selesai — `assembleDebug` **BUILD SUCCESSFUL**, unit test **344 lulus / 0 gagal / 1 skip** (48 kelas)

---

## Ringkasan

| # | Item | Status | Berkas utama |
|---|---|---|---|
| **P0-1** | RVOL: selaraskan basis volume | ✅ | `IndicatorMath.kt`, `ScalpingMtfEvaluator.kt`, `CandleTimeUtil.kt`, WS + 2 service |
| **P0-2** | Basis bar indikator tersimpan di log | ✅ | `TradingViewModels.kt`, `TradeSignalSnapshot.kt`, `TradeLogExporter.kt` |
| **P0-3** | `signalTime` dari engine, bukan `now` | ✅ | `TradeHistoryRecorder.kt`, `TradingViewModelSync.kt` |
| **P0-4** | `sellReason` sebenarnya | ✅ | `TradingViewModelSync.kt` |
| **P0-5** | `isTrailingUsed` + `trailingPercent` dari state nyata | ✅ | entity + migrasi Room 8→9, recorder, dialog |
| **P0-6** | Peak/Drawdown dari candle bursa | ✅ | recorder, `TradingViewModel.kt`, entity |
| **P1-7** | Label `Golden/Death Cross` → `MACD Histogram > 0 / < 0` | ✅ | `TradeHistoryRecorder.kt`, UI |
| **P1-8** | Format angka 4–6 digit signifikan (bukan `%E` mentah) | ✅ | `TradeLogExporter.kt`, recorder |
| **P1-9** | `VWAP 1M` kosong → `-` | ✅ | `ScalpingMtfEvaluator.kt` |
| **P1-10** | Pisahkan penamaan **stage** vs **score band** | ✅ | `ScalpingMtfEvaluator.kt` |
| **P2-11** | Flag `isSample` + penanda visual + keluar dari statistik | ✅ | entity + migrasi, recorder, `SignalLogScreen.kt`, `TradeHistoryJourneyCard.kt` |

---

## P0-1 — RVOL: basis volume diselaraskan

**Masalah:** pembilang memakai volume bar M1 yang **masih berjalan** (baru sebagian menit), penyebut memakai rata-rata bar penuh. RVOL jadi bias rendah **sebesar persentase waktu yang sudah berjalan**.

**Perubahan:**

1. `IndicatorMath.elapsedFraction(barStart, durasi, now)` — fraksi waktu berjalan, di-clamp `0,05 … 1,0`.
2. `IndicatorMath.projectVolumeToFullBar(volume, fraksi)` — `volume / fraksi`.
3. `IndicatorMath.relativeVolume(...)` — bar berjalan diproyeksikan dulu; **penyebut hanya dari bar yang sudah tutup** dan tidak memuat bar terakhir.
4. `ScalpingMtfEvaluator`:
   - `avgVol1M` → hanya bar tertutup (`dropLast(1).filter { isClosed }.takeLast(20)`).
   - `formingVolValid` → diproyeksikan ke setara bar penuh (berlaku juga untuk `isVSABreakout`).
   - `rvol` → memakai proyeksi yang sama.
   - Parameter baru `nowMs` (diisi `tick.timestamp` dari `LearningTradingEngine`).
5. `CandleBar.isClosed` kini benar-benar terisi:
   - `TokocryptoMarketWebSocket` — field `x` dari stream kline diteruskan (sebelumnya dibuang).
   - REST Tokocrypto & Indodax — `CandleTimeUtil.markLastCandleOpenIfForming()` menandai bar terakhir sebagai belum tutup.

**Verifikasi (data GNS/USDT nyata, 13 Okt 23:58 WIB):**

| | Nilai |
|---|---|
| Bar berjalan | 52% waktu (31,2 s), volume terkumpul 30.215 |
| Rata-rata 20 bar tutup | 39.818 |
| **RVOL lama** | 30.215 / 39.818 = **0,76×** ❌ di bawah gate 1,2× |
| **RVOL baru** | (30.215 / 0,52) / 39.818 = **1,46×** ✅ lolos gate |

6 unit test baru mengunci perilaku ini (`IndicatorMathScalpingExtTest`), termasuk satu test regresi khusus yang mereproduksi angka 0,76× → 1,46×.

> **Konsekuensi:** temuan "GNS entry tipis karena RVOL 0,76×" **batal** — entry-nya sehat; yang rusak pengukurannya.

---

## P0-2 — Basis bar indikator ikut tersimpan

`TechnicalIndicators` bertambah: `barTimestamp`, `barClosed`, `rvol`, `vwap` (plus `macdSignal` yang sebelumnya tidak diisi).

`TradeSignalSnapshot` bertambah: `rvol`, `vwap`, `indicatorBarTime`, `indicatorBarClosed` — ikut ke JSON, ke DB, dan ke ekspor markdown:

```
- Relative Volume (RVOL): 1.459x
- VWAP 1M: 0.7316
- Basis Bar Indikator: 14 Okt 23:58 (bar masih berjalan)
```

Kartu histori juga menampilkan `Basis bar M1 23:58 WIB (berjalan)` pada ringkasan perhitungan. Dengan ini angka RSI/MACD/RVOL **dapat direproduksi** oleh pihak ketiga dari candle bursa.

---

## P0-3 — `signalTime` dari engine

`recordBuy()` menerima parameter `signalTime: Long?`; kedua pemanggil (real & simulasi) mengirim `engine.signalState.value.timestamp`. `buyTime` tetap waktu eksekusi. Lag sinyal→eksekusi kini terukur (sebelumnya dua kolom selalu identik).

---

## P0-4 — `sellReason` sebenarnya

Konstanta `"SIMULATION_SELL"` diganti resolver `resolveSimulationSellReason(order, pos)`:

| Kondisi | Alasan |
|---|---|
| `isStopTriggered` + trailing aktif & terpicu | `TRAILING_STOP` |
| `isStopTriggered` | `STOP_LOSS` |
| Harga isi ≈ TP2 (±0,15%) | `HIT_TP2` |
| Harga isi ≈ TP1 (±0,15%) | `HIT_TP1` |
| Harga isi ≤ SL × 1,001 | `STOP_LOSS` |
| Selain itu | `MANUAL_SELL` |

Sekarang jurnal bisa membedakan exit manual / TP / SL / trailing — variabel paling penting untuk mengevaluasi strategi.

---

## P0-5 — Trailing dari state nyata

- Entity bertambah `trailingPercentUsed`, `trailingPeakPriceUsed` (kolom nyata, bukan konstanta).
- Getter `trailingPercent` dulu **hardcode `1.5`** — sekarang mengembalikan nilai sebenarnya, atau `null` bila trailing memang tidak dipakai.
- `recordBuy`/`recordSell` menerima & menyimpan nilai ini dari `SpotPositionStore` (`isTrailingEnabled`, `trailingPercent`, `peakPrice`, `trailingStopPrice`).
- `TradeLogDetailDialog` sekarang mengekspor nilai **hasil resolusi**, bukan parameter mentah — inilah sebab laporan selalu menulis "NONAKTIF" palsu.

---

## P0-6 — Peak/Drawdown dari candle

`processPriceTick()` menerima `candleHigh`/`candleLow`. `TradingViewModel` meneruskan ekstrem candle terakhir (`recentCandles.lastOrNull()`). Kolom baru `peakTrackedFromCandle` menandai sumbernya, dan UI menuliskan **`Peak (candle)`** vs **`Peak (dari tick)`** — tidak lagi menyiratkan akurasi yang tidak dimiliki.

---

## P1-7 … P1-10

| # | Sebelum | Sesudah |
|---|---|---|
| 7 | `MACD=Golden Cross (+9.010876114987423E-4)` | `MACD Histogram > 0 (0.0009011)` |
| 7 | `MACD=Death Cross (-6.5614555958088215E-7)` | `MACD Histogram < 0 (-6.561e-07)` |
| 8 | `Double.toString()` 16 digit | `%.4g` / `%.5g` (4–5 digit signifikan) |
| 9 | `VWAP 1M: 0.00` | `VWAP 1M: -` |
| 10 | `STRONG ENTRY: Skor 68` + `Skor 68/100 (WATCH)` | `TAHAP STRONG ENTRY — Skor 68/100` + `Skor 68/100 (kategori WATCH)` |
| — | Fallback kartu: `"RSI(14)=38.2 • MACD Golden Cross …"` (angka palsu) | `-` |

---

## P2-11 — Integritas sampel

- Entity bertambah `isSample`; 7 record seed ditandai `true`; migrasi Room juga menandai record lama ber-`tradeUuid` `seed-trade-*`.
- Kartu ringkasan: `records.filterNot { it.isSample }` → win rate, PnL, rata-rata hold **tidak lagi tercemar**. Badge `N SAMPEL DIABAIKAN` muncul bila ada.
- Kartu per-trade: badge **SAMPEL** warna amber.

---

## Migrasi database

Room **8 → 9** (`MIGRATION_8_9`, non-destruktif — jurnal pengguna tidak terhapus):

```sql
ALTER TABLE trade_history_records ADD COLUMN trailingPercentUsed REAL
ALTER TABLE trade_history_records ADD COLUMN trailingPeakPriceUsed REAL
ALTER TABLE trade_history_records ADD COLUMN isSample INTEGER NOT NULL DEFAULT 0
ALTER TABLE trade_history_records ADD COLUMN peakTrackedFromCandle INTEGER NOT NULL DEFAULT 0
UPDATE trade_history_records SET isSample = 1 WHERE tradeUuid LIKE 'seed-trade-%'
```

---

## Verifikasi

```
./gradlew :app:compileDebugKotlin --offline   → BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest --offline    → 344 tests, 0 failed, 1 skipped (48 kelas)
./gradlew :app:assembleDebug --offline        → BUILD SUCCESSFUL
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (27,8 MB)

---

## Belum dikerjakan (di luar cakupan P0–P2)

Temuan berikut ada di laporan verifikasi tetapi **tidak** masuk tabel P0/P1/P2, jadi sengaja tidak disentuh:

| Kode | Temuan |
|---|---|
| A1 | `volume24h` dilabeli satuan base asset, isinya USDT (selisih ~1.467×) |
| A2 | `signalPrice` benar, tetapi tanpa basis bar (kini sebagian tertutup P0-2) |
| A6 | `bbMiddle = ema20` saat Bollinger null → "Bollinger Middle" palsu |
| A8 | Nama bursa di header ekspor **ditebak** dari quote asset |
| A9 | Ekspor markdown membuang Kategori 6 (setup/skor/regime) — JSON memuatnya |
| B1 | Record fallback mengarang trade (`estBuyPrice = sellPrice × 0.98`, `signalConfidence = 80`) |
| B3 | Sinyal expired diberi kredit PEAK, bukan harga jual → bias optimistis |

Selain itu, **saran perbaikan strategi** dari audit GNS/AMP (lantai friksi, SL keras, trail proporsional ATR, RVOL wajib untuk BREAKOUT) masih disimpan sesuai instruksi sebelumnya.
