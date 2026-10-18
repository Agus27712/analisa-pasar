# Laporan Temuan Audit — Siklus Hidup Posisi Paper Trading & Mesin Exit

**Audit:** 2026-10-16 · **Ditutup seluruhnya:** 2026-10-18 (batch 6o) · **Dipindahkan ke sini:** 2026-10-18
**Branch:** `patch`
**Letak berkas:** `docs/laporan_temuan_bugs_paper_trade.md`. Berkas ini **tidak lagi** berada di
`docs/bugs/`, karena seluruh temuannya sudah tertutup. Aturannya: `docs/bugs/` hanya memuat temuan
yang **masih hidup**; berkas yang sudah beres dipindahkan keluar dengan nama
`docs/laporan_temuan_bugs_<topik>.md`, dan berkas itu **menjadi laporannya** — tidak ada lagi
laporan per batch.
**Isi dokumen:** laporan penutupan. Butir yang sudah ditutup dipangkas dari badan dokumen dan
diringkas di §2; peta jalur (§1) dan bagian "yang sudah benar" (§3) dipertahankan sebagai konteks;
ringkasan per batch ada di §6.
**Cakupan:** `PaperAutoTradeCoordinator.kt` · `PaperRehydrate.kt` · `PaperExitResolver.kt` ·
`SpotPositionStore.kt` · `TradingViewModelSync.kt` · `TradingViewModel.kt` ·
`TradingViewModelAlerts.kt` · `PaperAutoTradeRuntime`
**Batas penting:** seluruh temuan `P*` hanya menyentuh posisi **paper** (`isReal = false`) — bukan
kerusakan uang riil, tetapi posisi paper adalah dasar seluruh keputusan "apakah scalp punya edge".

| Status | Temuan | Jumlah |
|---|---|---|
| 🔴 Kritis | — | 0 |
| 🟠 Serius | — | 0 |
| 🟡 Sedang | — | 0 |
| ✅ Sudah diperbaiki | P1–P9 (P6 punya dua bagian) | 9 |
| ✅ Sudah benar | kepemilikan exit tunggal, rehidrasi lintas bursa, persistensi FSM, batas posisi bersamaan | — |

> **Tidak ada pekerjaan tersisa di berkas ini.** Kalau butuh pekerjaan baru di area paper trade,
> lakukan **audit baru** dan tulis temuannya ke berkas temuan baru di `docs/bugs/`
> (`temuan_bugs_<topik>.md`), jangan menghidupkan kembali nomor lama. Berkas temuan baru itu nanti
> dipindahkan ke sini dengan nama `docs/laporan_temuan_bugs_<topik>.md` begitu seluruh isinya tutup.

---

## 1. Peta jalur (hasil pemetaan, keadaan saat audit)

```
PEMBELIAN
├─ auto-buy   PaperAutoTradeCoordinator.tryPaperAutoBuy        (transition dari engine)
└─ manual    TradingViewModelSync.syncSimulationTradeToPositionStore
             └─ setHolding / markBought  →  armPaperExitEngine

PEMILIKAN EXIT (satu pemilik per posisi — invarian perangkap #20)
├─ engine paper:  PaperAutoTradeRuntime.machines (MEMORI SAJA)
│   └─ PaperExitStateMachine  (PaperExitResolver.kt)
│       └─ persistPaperFsm → key <key>_paper_fsm (SpotPositionStore)
└─ jalur legacy: checkAlertsAndTrailing → executeAutoSellOrder (TP1/TP2/SL/trailing)

PEMUTUSAN KAPAN EVALUASI
├─ tick pair dibuka     TradingViewModel.kt:157      (window M1 ikut)
└─ polling 10 detik    TradingViewModelSync.kt:635  (tanpa window M1)

REHIDRASI (sekali, saat init)
└─ initSubscriptionsAndPolling → rehydratePaperAutoExit
```

Catatan: nomor baris merujuk keadaan saat audit; setelah batch 3b/3c/5c/5d/6d/6e/6o, banyak yang
sudah bergeser — jangan pakai angka ini untuk navigasi kode.

## 2. Sudah ditutup (ringkasan + pointer)

| Temuan | Ringkas | Batch | Commit |
|---|---|---|---|
| P2 | Auto-buy menerima sinyal non-scalping & SL di bawah lantai friksi | batch 3b | `8d22fbb` |
| P3 | DCA tidak pernah me-re-arm mesin exit | batch 3b | `8d22fbb` |
| P4 | `atrPct` tidak diteruskan di jalur beli manual | batch 3b | `8d22fbb` |
| P1 | Posisi paper di bursa non-aktif menggantung setelah restart | batch 3c | `e33b238` |
| P6 bagian 1 | Jendela momentum satu sumber untuk kedua pemanggil | batch 5c | `1665bf4` |
| P5 | Kartu FSM dihapus tick pair lain | batch 5d | `95e0448` |
| P8 | `barsHeld` dihitung dua kali pada tick pertama sesudah rehidrasi | batch 6d | — |
| P7 | `clearPaperExitState` tanpa pemanggil (kontrak dihapus, bukan diimplementasikan) | batch 6e | — |
| P6 bagian 2 | `peak`/level atas kini melihat high intrabar M1 — `trading/PaperIntrabarPeakResolver.kt` | batch 6o | — |
| P9 | Mematikan auto-exit saat posisi terbuka kini menyerahkan posisi ke jalur auto-sell lama — `trading/PaperHandBackResolver.kt` + `handBackAllPaperEnginesToLegacy()` | batch 6o | — |

## 3. ✅ Yang sudah benar (jangan diutak-atik)

Semua aturan rehidrasi P4.4 **terpasang benar** dan sudah diuji:

- key `_paper_fsm` dihapus di **setiap** jalur pelepasan kepemilikan — `markSold` (dua blok),
  `finalizePaperExit`, cabang "posisi sudah tidak ada" di `evaluatePaperAutoExitLocked`, dan
  (sejak batch 6o) `handBackAllPaperEnginesToLegacy`. Dijaga
  `app/src/test/java/agu/analys/trading/SpotPositionStorePaperFsmTest.kt`.
- Penanda `_paper_engine` ditulis saat engine mengambil alih dan dilepas di setiap pelepasan
  (termasuk serah-terima batch 6o). Dijaga `SpotPositionStorePaperMarkerTest.kt`.
- SL asli sinyal ditulis lewat `setAutoSellParams(stopLossPrice = …)`, bukan `_stop_loss` sintetis
  dari `setHolding`.
- `atrPct` dioper saat mesin dibangun di `PaperRehydrate.decide()` — **bukan** lewat `restore()`.
  Dijaga `PaperRehydrateTest.kt`.
- `barsHeld` dihitung dari jam dinding (`mergedBarsHeld`).
- State tersimpan yang gagal validasi **dibuang**, bukan dipakai setengah (`restoreRejection` +
  warning dicatat).
- Batas posisi paper bersamaan (global, lintas bursa) memakai **semua** posisi paper termasuk yang
  dibeli manual: `PaperAutoTradeCoordinator` + `SpotPositionStore.countActivePaperPositions`.
- Normalisasi simbol runtime konsisten: `PaperAutoTradeRuntime.key()` hanya buang `_`, tetapi
  `PairRules.normalizeBidr` sudah membuang `_ / - / space`, dan `TradingPair.fromCustomSymbol`
  memakainya sebelum mesin disimpan.

## 4. Prasyarat & pola kerja yang berlaku

Logika penting terkubur di extension `TradingViewModel.*` yang butuh ViewModel + Android, sehingga
**tidak bisa diuji**. Pola yang sudah dipakai berulang: **angkat keputusan ke resolver murni**,
jangan sisipkan `if` di lambda ber-`ViewModel`. Resolver murni yang sekarang ada:

1. `PaperStopResolver.resolve(signalSl, entry, floorPct)` — satu penentu harga SL (P2, P3, P4).
2. `PaperRehydrate.decide(...)` — keputusan rehidrasi (P3, P1).
3. `PaperRehydrateScope.decide(...)` — urutan bursa rehidrasi (P1).
4. `PaperAtr.of(atr, price)` — ATR% (P4).
5. `PaperFirstBarResolver.countsAsNewBar(isNewBar, firstTickAfterRehydrate)` — bar pertama setelah
   rehidrasi (P8, batch 6d).
6. `PaperIntrabarPeakResolver.effectiveHigh(candles, openedAtMs, nowMs, currentPrice)` — high
   intrabar M1 untuk `peak` (P6 bagian 2, batch 6o).
7. `PaperHandBackResolver.resolveStopLoss(entry, storedSl, activeSl, bePx, floorPct)` — level SL
   serah-terima ke jalur lama (P9, batch 6o).

Setiap perbaikan **wajib** kontrol negatif (matikan perbaikan → buktikan uji GAGAL → kembalikan),
dan angka uji **diukur** dari XML `app/build/test-results/testDebugUnitTest/*.xml` (di sandbox CI),
bukan dari pesan lama.

## 5. Batas yang jujur (jangan diklaim lebih)

- Posisi yang diserahkan ke jalur lama — lewat restart (`PaperRehydrate`) **maupun** lewat batch 6o
  saat runtime — dievaluasi **saat pair-nya dibuka**, atau di latar bila trailing aktif. Tidak ada
  pemantauan latar khusus yang diciptakan batch 6o; itu perilaku jalur legacy yang berlaku umum.
  Setelah serah-terima, SL/TP **ada dan aktif**, tetapi pemantauan latarnya mengikuti aturan di
  atas.
- Mesin untuk bursa non-aktif sudah dipulihkan tetapi **belum bisa dievaluasi**, karena
  `evaluatePaperAutoExit` hanya menerima harga bursa aktif (batas dari batch 3c, masih berlaku).
- Jangan mengklaim "posisi di bursa non-aktif terlindungi otomatis" sebelum ada umpan harga
  lintas bursa.

## 6. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit read-only siklus hidup posisi paper & mesin exit (verifikasi ke kode, branch `patch` @ `a842e35`) |
| 2026-10-16 | Temuan diekspor ke `docs/bugs/temuan_bugs_paper_trade.md` — **belum ada perbaikan kode** |
| 2026-10-16 | **Batch 3b:** P2 + P3 + P4 diperbaiki + diuji di `8d22fbb` (16 uji di `PaperStopResolverTest`, kontrol negatif 4/16 gagal). Lihat `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` |
| 2026-10-16 | **Batch 3c:** P1 diperbaiki + diuji di `e33b238` (13 uji baru, kontrol negatif 8/13 gagal) ⇒ **ketiga temuan kritis (P1, P2, P3) tertutup.** Lihat `docs/LAPORAN-batch3-rehidrasi-2026-10-16.md` |
| 2026-10-16 | **Batch 5c:** P6 diperbaiki **sebagian** di `1665bf4` (jendela momentum satu sumber; sisa `peak` dicatat eksplisit). Lihat `docs/LAPORAN-batch5-p6-2026-10-16.md` |
| 2026-10-16 | **Batch 5d:** P5 diperbaiki di `95e0448`. Lihat `docs/LAPORAN-batch5-p5-2026-10-16.md` |
| 2026-10-16 | **Sinkronisasi dokumen (tanpa perubahan kode):** butir yang sudah ditutup (P1–P5) dipangkas dari badan dokumen dan diringkas di §4; P6, P7, P8 dipertahankan dengan nomor baris terverifikasi ulang di `patch` @ `547048d` + review owner. Lihat `docs/LAPORAN-sync-dokumen-temuan-2026-10-16.md` |
| 2026-10-17 | **Batch 6d:** P8 diperbaiki di `PaperFirstBarResolver` + parameter `countAsNewBar` di `PaperExitStateMachine.onBar` (7 uji baru; kontrol negatif dua arah 3/7 dan 2/7 gagal). Lihat `docs/LAPORAN-batch6-p8-2026-10-17.md` |
| 2026-10-17 | **Batch 6e:** P7 ditutup dengan **menghapus** kontrak `clearPaperExitState` (tidak pernah dipanggil; apa adanya justru meninggalkan posisi tanpa exit). Sisa masalahnya diangkat sebagai **P9**. Lihat `docs/LAPORAN-batch6-p7-2026-10-17.md` |
| 2026-10-18 | **Batch 6o — P6 (sisa) + P9 ditutup, seluruh temuan P1–P9 tuntas ⇒ berkas ini dipindah ke `docs/laporan_temuan_bugs_paper_trade.md`.**<br>(a) **P6 bagian 2:** `trading/PaperIntrabarPeakResolver.kt` — bar sintetis di `evaluatePaperAutoExitLocked` kini memakai high intrabar dari cache M1 (hanya candle yang dibuka **setelah** posisi dibuka; cache wajib segar ≥ 20 bar / ≤ 180 dtk — ambang `PaperExitMomentumWindow` yang sama; `low` tetap harga tick). Evaluator membaca cache **sekali** untuk jendela momentum + peak.<br>(b) **P9:** `trading/PaperHandBackResolver.kt` + `TradingViewModel.handBackAllPaperEnginesToLegacy()` — transisi `autoExitEnabled true → false` di `updatePaperAutoTradeConfig` menyerahkan **semua** posisi paper (lintas bursa) ke jalur auto-sell lama: `auto_sell_enabled` diisi SL hasil resolver, penanda `_paper_engine` + progres `_paper_fsm` + mesin di memori dilepas. Resolver memakai `max(SL aktif mesin, SL tersimpan/lantai)` dan mempertahankan SL ≥ entry yang berasal dari `activeStopLoss` mesin (BE/trailing terkunci — pengecualian sadar terhadap invarian lama `0 < SL < entry`). `clearAll` kini hanya saat auto-buy **dan** auto-exit sama-sama mati, sehingga mematikan auto-buy tidak lagi menelantarkan posisi auto-exit.<br>Uji baru: `PaperIntrabarPeakResolverTest`, `PaperHandBackResolverTest` (murni), `PaperIntrabarWiringTest`, `PaperHandBackWiringTest` (pindai sumber) — jumlah suite penuh + angka terukur menyusul dari CI dan dicatat di `workplan2.md` §59. |
