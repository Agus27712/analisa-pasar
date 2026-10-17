# Temuan Audit — Jurnal Sinyal, Jurnal Trade & Angka P&L

**Audit:** 2026-10-16 · **Status disinkronkan:** 2026-10-16 (verifikasi ulang ke kode + review owner)
**Isi dokumen:** **hanya temuan yang MASIH hidup.** Temuan yang sudah ditutup dipangkas dari badan
dokumen dan diringkas di §3 (dengan pointer ke laporan eksekusinya) — supaya pembaca berikutnya
tidak membuang waktu membaca teks yang sudah tidak relevan.
**Verifikasi terakhir:** branch `patch` @ `547048d`, dibaca ulang langsung ke kode pada 2026-10-16
dan **sudah direview owner**. Status di bawah bersifat **otoritatif — agen tidak perlu cek kode
lagi** untuk memastikannya; cukup baca tabel ini. Nomor baris di bawah refers ke `547048d`.
**Cakupan:** `database/SignalLogRepository.kt` · `database/TradeHistoryRecorder.kt` ·
`trading/TradeSignalSnapshot.kt` · `viewmodel/SignalLogViewModel.kt` ·
`viewmodel/TradingViewModelSync.kt` · `ui/screens/SignalLogScreen.kt` ·
`ui/components/detail/RadarSellSection.kt` · `ui/components/detail/sell/SellCalculationCard.kt`

| Status | Temuan | Ringkas |
|---|---|---|
| 🟠 **Hidup** | **J8** | Rumus P&L sendiri tanpa slippage di jurnal trade real |
| ✅ Ditutup | J1–J7, J9, J10 | Lihat §3 |

> ✅ **J10 kini tertutup penuh** (batch 6a, `docs/LAPORAN-batch6-j10-2026-10-17.md`): jalur beli
> paper manual tidak lagi meng-*arm* mesin exit dengan SL/TP pair lain — memakai
> `trading/PaperArmSignal.kt`. Sebelumnya (batch 5a) hanya jalur jurnal yang tertutup.

### Catatan pengukuran

`app/build/test-results/testDebugUnitTest/` yang ada di repo **basi** (hasil 2026-10-09, 358
test — sebelum batch 1 & 2 notifikasi), dan sandbox audit ini tidak punya Android SDK sehingga
`./gradlew testDebugUnitTest` tidak bisa dijalankan. Ukur baseline ulang di mesin ber-SDK.

## 1. Peta jalur (hasil pemetaan)

```
PRODUKEN SINYAL
├─ engine/LearningTradingEngine.kt:251,284,328,372  → marketSymbol = tick.symbol  (BENAR)
└─ viewmodel/TradingViewModelSync.kt:664-684         → menimpa marketSymbol dgn selectedPair (J1)

JURNAL SINYAL (signal_logs)
├─ recordSignal()      :59-194   insert / update (entryPrice beku, TP/SL bisa berubah)
├─ processPriceTick()  :217-241  per simbol + bursa
├─ processBatchTicks() :246-269  per bursa
├─ updateTrackingLogWithPrice() :271-341  → status HIT_TP1/TP2/SL/EXPIRED + realizedPnlPct
└─ resolveLogManually() :346-…   resolusi manual

KONSUMEN ANGKA
├─ ui/screens/SignalLogScreen.kt:1587-1596   label "Net" untuk angka yang sebenarnya KOTOR
├─ calculateReliabilitySummary() :670-700     win/loss/avg/profitFactor
└─ buildTierStats()             :804-832     tabel tier confidence (tab yang dipakai N11)
    (bandingkan buildGroupStats() :770-803    — satu-satunya yang SUDAH benar & teruji)

JURNAL TRADE (trade_history_records)
├─ recordBuy()  :…  ·  recordSell() :258-…   ⚠️ ada fallback lintas bursa
└─ processPriceTick() :152-…                ⚠️ ada fallback lintas bursa
```

## 2. Temuan yang masih hidup

| # | Temuan | Lokasi | Dampak / status |
|---|---|---|---|
| J8 | Rumus P&L ditulis ulang sendiri, **tanpa slippage**, di jalur eksekusi trade real | `viewmodel/TradingViewModelSync.kt:56-65` | `customPnlPct = (netSell / cost − 1) × 100`, dengan `netSell = harga × (1 − FeeSchedule.sellRate)` dan `cost = entry × (1 + FeeSchedule.buyRate)`. `FeeSchedule.netProfitPctFor` menambahkan **slippage dua sisi** (default 0,08%/sisi), jadi jurnal trade real berbeda ≈ **0,16 pp** dari angka portofolio/radar jual — pelanggaran "SATU rumus P&L". Pengecualian yang sah hanya `SimulationTradeStore`/`SimulationOrderEngine`. **Hidup** |

## 3. Sudah ditutup (ringkasan + pointer laporan)

| Temuan | Commit | Laporan |
|---|---|---|
| J2 — tiga definisi W/L tidak konsisten | `d97a539` | `docs/LAPORAN-batch3-jurnal-2026-10-16.md` |
| J1 — P&L kotor berlabel "Net" | `d97a539` | `docs/LAPORAN-batch3-jurnal-2026-10-16.md` |
| J7 — radar jual menulis "sudah bersih fee & pajak" | `d1097aa` | `docs/LAPORAN-batch4-j7-2026-10-16.md` |
| J4 + N10 — jurnal memakai simbol yang dievaluasi engine | `9767c88` | `docs/LAPORAN-batch5-j4n10-2026-10-16.md` |
| J3 — `SignalLogViewModel` mengikat bursa saat konstruksi | `95f184b` | `docs/LAPORAN-batch5-j3-2026-10-16.md` |
| J6 + N11 — klaim statistik hardcoded di UI | `b690208` | `docs/LAPORAN-batch5-j6n11-2026-10-16.md` |
| J5 — fallback lintas bursa di jurnal trade | `547048d` | `docs/LAPORAN-batch5-j5-2026-10-16.md` |
| J10 (sisa) — mesin exit paper memakai SL/TP pair lain | `d87be04` | `docs/LAPORAN-batch6-j10-2026-10-17.md` |
| J9 (N9) — default `?: 75` mengarang skor keyakinan | batch 6b | `docs/LAPORAN-batch6-j9-2026-10-17.md` |

> **Angka yang harus diingat dari J7:** friksi FSM Tokocrypto USDT adalah **0,9735%** (bentuk
> multiplikatif, sama dengan `FeeSchedule`), **bukan 0,8226%** seperti tertulis di audit awal.
> Angka 0,8226% tidak cocok dengan rumus mana pun; sudah dikoreksi di batch 4.

## 4. Yang sudah benar (jangan diutak-atik)

- **B5:** confidence di signal-log = nilai saat firing, bukan MAX — `SignalLogRepository.kt:107-111`.
- **B3:** sinyal `EXPIRED` memakai harga terakhir, bukan `peakPrice`.
- **B4:** `resolveLogManually` tidak lagi mengarang besaran (clamp ±1,5% dihapus) —
  `SignalLogRepository.kt:356-366`.
- **B6:** label gross/net sudah ada di `maxProfitPct`/`maxDrawdownPct` (yang **tetap** kotor dan
  berlabel gross) — J1 hanya menyangkut `realizedPnlPct`.
- **B1:** fallback yang mengarang trade sudah dihapus di `recordSell` (tetap ada fallback bursa J5).
- `buildGroupStats` (`:770-803`) adalah **satu-satunya** definisi W/L yang eksklusif — harus jadi
  acuan, dan sudah dijaga `app/src/test/java/agu/analys/database/SignalLogSetupStatsTest.kt:84`.
- `SignalLogRepository.processPriceTick` sudah benar memakai query berversi exchange **tanpa
  fallback lintas bursa** (`:231-233`) — pola yang harus ditiru `TradeHistoryRecorder`.
- Anti-spam log memakai bursa yang sama (`:152-164`) — benar.

## 5. Rekomendasi perbaikan (urut nilai ÷ risiko)

| Urut | Temuan | Alasan |
|---|---|---|
| 1 | **J8** | Angka P&L di jurnal trade real berbeda 0,16 pp dari kartu jual/portofolio — satu trade, dua angka |

Prasyarat yang dulu disebut (satu definisi W/L `classifySignalOutcome` + satu sumber basis P&L
`FeeSchedule`) **sudah ada** — keduanya lahir di batch 3a dan batch 4, dan kini dijaga uji.
Yang tersisa hanya penerapan tiga butir di atas.

Setiap perbaikan **wajib** kontrol negatif (matikan perbaikan → buktikan test GAGAL → kembalikan),
dan angka test **diukur** dari `app/build/test-results/testDebugUnitTest/*.xml`, bukan dari pesan lama.

## 6. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit read-only jurnal sinyal, jurnal trade, dan angka P&L (branch `patch` @ `a842e35`) |
| 2026-10-16 | Temuan diekspor ke `docs/bugs/temuan_bugs_jurnal_pnl.md` (dokumen ini) — **belum ada perbaikan kode** |
| 2026-10-16 | **Batch 3a:** J2 + J1 diperbaiki + diuji di `d97a539` (14 uji baru, kontrol negatif 9/22 gagal). Lihat `docs/LAPORAN-batch3-jurnal-2026-10-16.md` |
| 2026-10-16 | **Batch 4:** J7 diperbaiki + diuji di `d1097aa` (17 uji baru, kontrol negatif 13/17 gagal). Sekaligus **koreksi angka audit**: friksi FSM Tokocrypto USDT adalah 0,9735%, bukan 0,8226%. Lihat `docs/LAPORAN-batch4-j7-2026-10-16.md` |
| 2026-10-16 | **Batch 5a:** J4 + N10 diperbaiki di `9767c88` (20 uji, kontrol negatif 10/20 gagal). Lihat `docs/LAPORAN-batch5-j4n10-2026-10-16.md` |
| 2026-10-16 | **Batch 5b:** J3 diperbaiki di `95f184b`. Lihat `docs/LAPORAN-batch5-j3-2026-10-16.md` |
| 2026-10-16 | **Batch 5e:** J6 + N11 diperbaiki di `b690208`. Lihat `docs/LAPORAN-batch5-j6n11-2026-10-16.md` |
| 2026-10-16 | **Batch 5f:** J5 diperbaiki di `547048d`. Lihat `docs/LAPORAN-batch5-j5-2026-10-16.md` |
| 2026-10-17 | **Batch 6b:** J9 (N9) diperbaiki — `TradeSignalConfidence.resolve` menggantikan default `?: 75` (6 uji, kontrol negatif 1/6 gagal). Lihat `docs/LAPORAN-batch6-j9-2026-10-17.md` |
| 2026-10-17 | **Batch 6a:** J10 (sisa) diperbaiki — jalur beli paper manual memakai `PaperArmSignal` (8 uji, kontrol negatif 3/8 gagal). Lihat `docs/LAPORAN-batch6-j10-2026-10-17.md` |
| 2026-10-16 | **Sinkronisasi dokumen (tanpa perubahan kode):** butir yang sudah ditutup dipangkas dari badan dokumen dan diringkas di §3; J10 diperjelas jadi **sebagian** (§2.1 — jalur `armPaperExitEngine` di `TradingViewModelSync.kt:343-350` masih membaca SL/TP mentah). Riwayat batch 5a–5f yang tadinya hilang di §6 ditambahkan. Verifikasi ulang ke `patch` @ `547048d` + review owner. Lihat `docs/LAPORAN-sync-dokumen-temuan-2026-10-16.md` |
