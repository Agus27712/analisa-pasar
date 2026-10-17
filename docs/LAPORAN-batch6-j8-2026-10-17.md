# Laporan Batch 6c — J8: P&L trade real memakai satu rumus `FeeSchedule`

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** J8 — `docs/bugs/temuan_bugs_jurnal_pnl.md`

## Ringkasan

Jalur eksekusi trade real menulis rumus P&L sendiri (`TradingViewModelSync.kt:56-65`):

```kotlin
val netSell = effectivePrice * (1.0 - sellRate)
val cost = before.entryPrice * (1.0 + buyRate)
customPnlPct = (netSell / cost - 1.0) * 100.0
```

Tidak ada slippage, sedangkan `FeeSchedule.netProfitPctFor` menambahkan slippage **dua sisi**
(default 0,08%/sisi). Untuk Tokocrypto IDR selisihnya ≈ **0,16 pp** — satu trade menghasilkan dua
angka berbeda: jurnal vs portofolio/radar jual. Pengecualian yang sah hanya
`SimulationTradeStore`/`SimulationOrderEngine`.

## Perbaikan

Resolver murni baru `app/src/main/java/agu/analys/trading/RealTradePnl.kt`:

```kotlin
RealTradePnlResolver.resolve(quantity, entry, exitPrice, exchange, quoteAsset, useMaker, slippagePct)
```

- `pnlIdr = netSellValue − costBasisNet` (keduanya sudah termasuk fee + slippage);
- `pnlPct = FeeSchedule.netProfitPctFor(...)` — **identik** dengan `(netSell/cost − 1) × 100`,
  jadi rupiah dan persen tidak mungkin berbeda;
- input tidak valid (`quantity/entry/exitPrice ≤ 0`, NaN/Inf) → `RealTradePnl.ZERO`.

`TradingViewModelSync` memanggil resolver itu; variabel lokal `buyRate`/`sellRate` dihapus.

## Uji & kontrol negatif

- **Uji baru:** `app/src/test/java/agu/analys/trading/RealTradePnlTest.kt` — **12 kasus**:
  paritas persis dengan `FeeSchedule` (TK IDR, TK USDT, Indodax), selisih ≈0,16 pp terhadap rumus
  lama (bukti slippage ikut), konsistensi rupiah↔persen, rugi negatif, harga sama tetap rugi
  karena friksi, tiga input tidak valid, penskalaan kuantitas, taker≠maker.
- **Kontrol negatif:** resolver dikembalikan ke rumus lama tanpa slippage ⇒ **5 dari 12 uji
  GAGAL** — tiga paritas bursa, selisih slippage, konsistensi rupiah↔persen. Tujuh yang tetap
  lulus memang tidak membedakan versi. Dicatat apa adanya.
- **Angka terukur (dari XML):** 76 kelas / 607 uji → **77 kelas / 619 uji**, 0 gagal, 1 dilewati.

## Batas

- Hanya jalur **jual trade real**. `recordSell` di `TradeHistoryRecorder` sudah memakai
  `FeeSchedule` untuk fee; angka `customPnl*` yang dikirim sekarang konsisten dengannya.
- `SimulationTradeStore`/`SimulationOrderEngine` sengaja tidak disentuh (pengecualian yang sah).
- Wiring di `TradingViewModelSync` tidak diuji otomatis; yang diuji resolver-nya.
