# Temuan Audit — Siklus Hidup Posisi Paper Trading & Mesin Exit

**Audit:** 2026-10-16 · **Status disinkronkan:** 2026-10-16 (verifikasi ulang ke kode + review owner)
**Isi dokumen:** **hanya temuan yang MASIH hidup.** Temuan yang sudah ditutup dipangkas dari badan
dokumen dan diringkas di §4 (dengan pointer ke laporan eksekusinya).
**Verifikasi terakhir:** branch `patch` @ `547048d`, dibaca ulang langsung ke kode pada 2026-10-16
dan **sudah direview owner**. Status di bawah **otoritatif — agen tidak perlu cek kode lagi** untuk
memastikannya; cukup baca tabel ini. Nomor baris refers ke `547048d`.
**Cakupan:** `PaperAutoTradeCoordinator.kt` · `PaperRehydrate.kt` · `PaperExitResolver.kt` ·
`SpotPositionStore.kt` · `TradingViewModelSync.kt` · `TradingViewModel.kt` ·
`TradingViewModelAlerts.kt` · `PaperAutoTradeRuntime`

| Status | Temuan | Ringkas |
|---|---|---|
| 🟠 **Hidup sebagian** | **P6** | Jendela momentum sudah satu sumber (batch 5c); `peak` masih hanya melihat harga tick, bukan high M1 |
| 🟠 **Hidup** | **P7** | `clearPaperExitState` tidak punya satu pun pemanggil |
| 🟠 **Hidup** | **P8** | `barsHeld` +1 pada tick pertama setelah rehidrasi ⇒ time-stop 1 bar lebih awal |
| ✅ Ditutup | P1, P2, P3, P4, P5 | Lihat §4 — ketiga temuan kritis (P1–P3) sudah tertutup |

### Catatan pengukuran (penting sebelum mulai memperbaiki)

`app/build/test-results/testDebugUnitTest/` di repo ini **basi** — berisi hasil **2026-10-09**
(358 test), yaitu **sebelum** batch 1 & 2 notifikasi. Jadi angka 462 test di
`docs/PANDUAN-LANJUT-AGEN.md` **belum bisa dikonfirmasi** dari sandbox yang dipakai audit ini
(sandbox tidak punya Android SDK, jadi `./gradlew testDebugUnitTest` tidak bisa dijalankan).
Ukur ulang baseline lewat `git stash` di mesin yang punya SDK sebelum membandingkan.

## 1. Peta jalur (hasil pemetaan)

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

## 2. 🟠 Temuan serius (yang masih hidup sebagian)

### P6 — Dua jalur pemantauan menghasilkan exit berbeda untuk trade yang sama

> ⚠️ **STATUS: SEBAGIAN.** Bagian "satu sumber jendela" sudah tertutup batch 5c. Bagian kedua —
> `peak` tidak pernah melihat high M1 — **masih hidup** dan butuh temuan/uji sendiri (lihat catatan
> ✅ di bawah). Baris kode di blok di bawah sengaja ditulis seperti **keadaan awal** (sebelum
> batch 5c) supaya Akar masalahnya tetap terbaca.

**Lokasi (keadaan awal):** `viewmodel/TradingViewModel.kt:157`, `viewmodel/TradingViewModelSync.kt:629-637`, `viewmodel/PaperAutoTradeCoordinator.kt:645-660`

```kotlin
// TradingViewModel.kt:157 — pair dibuka: jendela momentum DIKIRIM
runCatching { evaluatePaperAutoExit(symbol, currentEx, price, recentCandles.takeLast(15)) }

// TradingViewModelSync.kt:629-637 — posisi di latar: jendela momentum KOSONG
for (tick in ticks) { ... runCatching { evaluatePaperAutoExit(tick.symbol, currentEx, tick.price) } ... }
```

Selain itu, bar sintetis dibuat dengan OHLC identik dan `volume = 0.0`
(`PaperAutoTradeCoordinator.kt:645-660`), `peak = max(peak, bar.high)` tidak pernah bisa melewati
harga tick, dan sampling jalur latar 10 detik.

**Dampak.**
- `EARLY_PROFIT` mustahil terjadi pada posisi yang **tidak sedang dibuka** ⇒ jurnal mencatat `TP2`
  di trade yang sama akan tercatat `EARLY_PROFIT` bila kebetulan user membuka pair-nya.
- `peak` di jalur latar tertinggal dari high M1 ⇒ trailing SL lebih longgar daripada replay.
- App dan `tools/scalp_replay.py` tidak sebanding, padahal paritas itu alasan utama jurnal ini ada.

**Perbaikan.** satu sumber tick untuk kedua pemanggil (atau satu scheduler), dan window M1 dari
`MtfCacheManager` bila tersedia.

> ✅ **DIPERBAIKI 2026-10-16 (batch 5c)** — bagian "satu sumber" diikuti apa adanya: parameter
> `window` **dihapus** dari `evaluatePaperAutoExit`, jadi tidak ada lagi pemanggil yang bisa
> mengirim jendela berbeda. Jendela kini diresolusi di dalam dari cache M1 lewat
> `trading/PaperExitMomentumWindow.kt`, dengan ambang kesegaran yang **mengikuti
> `MtfCacheManager.isCacheValid(M1)`** (≥20 bar, ≤180 detik) — bukan ambang baru. Kedua pemanggil
> kini identik: `evaluatePaperAutoExit(symbol, currentEx, price)`.
> Efek yang disengaja: `EARLY_PROFIT` lebih jarang di jalur foreground — itulah inti temuan.
> Uji: `PaperExitMomentumWindowTest` (12 kasus); kontrol negatif 5/12 gagal.
> Laporan: `docs/LAPORAN-batch5-p6-2026-10-16.md`.
>
> ⚠️ **Bagian kedua P6 belum beres (diverifikasi ulang 2026-10-16 @ `547048d`):** `peak` di jalur
> latar masih tertinggal dari high M1. Bar yang dinilai tetap **sintetis dari tick** —
> `PaperAutoTradeCoordinator.kt:684-691` membuat `open = high = low = close = currentPrice`,
> `volume = 0.0` — lalu `PaperExitResolver.kt:587` menjalankan `peak = max(peak, bar.high)`. Jendela
> M1 yang sudah tersedia (`PaperExitMomentumWindow.resolve`, `PaperAutoTradeCoordinator.kt:696`)
> **hanya dipakai untuk pemeriksaan momentum**, tidak pernah disuntikkan ke `peak`.
> Ini bukan cabang kode yang berbeda antar pemanggil, melainkan **tidak adanya umpan intrabar**:
> perlakukan butuh temuan dan uji sendiri, sengaja tidak disisipkan di sini
> (lihat §6 laporan batch 5c).

## 3. 🟡 Temuan sedang (masih hidup)

| # | Temuan | Lokasi (verifikasi ulang @ `547048d`) | Dampak |
|---|---|---|---|
| P7 | `clearPaperExitState` **tidak punya satu pun pemanggil** | definisi di `PaperAutoTradeCoordinator.kt:803`; `grep -rn "clearPaperExitState" app/src` → **1 hasil (definisi saja)** | Jalur "user mengambil alih exit engine" yang menjanjikan AGENTS §12 tidak ada implementasinya; satu-satunya jalan keluar adalah `HandBackToLegacy`, yang justru melempar progres FSM (lihat P3) |
| P8 | `barsHeld` dihitung **dua kali** pada tick pertama setelah rehidrasi | `PaperRehydrate.kt:143` + `:165-169` (`mergedBarsHeld`), `PaperAutoTradeCoordinator.kt:933` (`markFirstTickAsNewBar = true`) → `:156` + `:241` (`FIRST_TICK_BUCKET = -1L`), `PaperExitResolver.kt:586` (`if (isNewBar) barsHeld++`) | `markFirstTickAsNewBar` membuat `onBar` menaikkan `barsHeld` lagi pada tick yang sama, padahal `mergedBarsHeld` sudah menghitung menit yang berjalan ⇒ **time-stop keluar 1 bar (1 menit) lebih awal** dari yang seharusnya |

## 4. Sudah ditutup (ringkasan + pointer laporan)

| Temuan | Commit | Laporan |
|---|---|---|
| P2 — auto-buy menerima sinyal non-scalping & SL di bawah lantai friksi | `8d22fbb` | `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` |
| P3 — DCA tidak pernah me-re-arm mesin exit | `8d22fbb` | `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` |
| P4 — `atrPct` tidak diteruskan di jalur beli manual | `8d22fbb` | `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` |
| P1 — posisi paper di bursa non-aktif menggantung setelah restart | `e33b238` | `docs/LAPORAN-batch3-rehidrasi-2026-10-16.md` |
| P6 (bagian jendela momentum) | `1665bf4` | `docs/LAPORAN-batch5-p6-2026-10-16.md` |
| P5 — kartu FSM dihapus tick pair lain | `95e0448` | `docs/LAPORAN-batch5-p5-2026-10-16.md` |

## 5. Yang sudah benar (jangan diutak-atik)

Semua aturan rehidrasi P4.4 **terpasang benar** dan sudah diuji:

- key `_paper_fsm` dihapus di **setiap** jalur pelepasan kepemilikan — `SpotPositionStore.kt:416`
  dan `:452` (`markSold`), `PaperAutoTradeCoordinator.kt:630` ("posisi sudah tidak ada"),
  `:750` (`finalizePaperExit`), `:759` (`clearPaperExitState`). Dijaga
  `app/src/test/java/agu/analys/trading/SpotPositionStorePaperFsmTest.kt`.
- Penanda `_paper_engine` ditulis saat engine mengambil alih (`:342-346`) dan dilepas di
  `:627`, `:747`, `:758`, `markSold:415`. Dijaga `SpotPositionStorePaperMarkerTest.kt`.
- SL asli sinyal ditulis lewat `setAutoSellParams(stopLossPrice = …)` (`:354-364`, `:536-551`),
  bukan `_stop_loss` sintetis dari `setHolding`.
- `atrPct` dioper saat mesin dibangun di `PaperRehydrate.decide()` (`:135`) — **bukan** lewat
  `restore()`. Dijaga `PaperRehydrateTest.kt:296`.
- `barsHeld` dihitung dari jam dinding (`mergedBarsHeld`, `PaperRehydrate.kt:165-169`).
- State tersimpan yang gagal validasi **dibuang**, bukan dipakai setengah
  (`PaperRehydrate.kt:144-147` + warning dicatat `:877-882`).
- Batas posisi paper bersamaan(global, lintas bursa) memakai **semua** posisi paper termasuk yang
  dibeli manual: `PaperAutoTradeCoordinator.kt:441-442` + `SpotPositionStore.kt:845`.
- Normalisasi simbol runtime konsisten: `PaperAutoTradeRuntime.key()` hanya buang `_`, tetapi
  `PairRules.normalizeBidr` (`PairRules.kt:40-45`) sudah membuang `_ / - / space`, dan
  `TradingPair.fromCustomSymbol` memakainya sebelum mesin disimpan.

## 6. Rekomendasi perbaikan (urut nilai ÷ risiko)

| Urut | Temuan | Alasan |
|---|---|---|
| 1 | **P6 (sisa `peak`)** | App dan `tools/scalp_replay.py` belum sebanding — dan paritas itu alasan utama jurnal ini ada. Butuh umpan intrabar: high M1 dari cache harus ikut memperbarui `peak`, bukan hanya bar sintetis dari tick |
| 2 | **P8** | Time-stop keluar 1 bar lebih awal setelah restart — merusak determinisme app vs replay |
| 3 | **P7** | Jalur "user mengambil alih exit engine" masih kosong: implementasikan atau hapus kontraknya |

### Prasyarat yang berulang muncul di semua temuan ini

Logika penting terkubur di extension `TradingViewModel.*` yang butuh ViewModel + Android, sehingga
**tidak bisa diuji**. Semua yang benar (PaperRehydrate, PaperAutoTradeRuntime, SpotPositionStore)
sudah **murni dan teruji**; yang belum murni adalah **keputusan**-nya. Prasyarat yang dulu dipakai
P1–P4 **sudah ada semua**:

1. `PaperStopResolver.resolve(signalSl, entry, floorPct)` — murni, dipakai jalur arm mesin exit (P2, P3, P4).
2. `PaperRehydrate.decide(...)` — murni (P3, P1).
3. `PaperRehydrateScope.decide(...)` — murni (P1).
4. `PaperAtr.of(atr, price)` — murni (P4).

Untuk P8, keputusan yang perlu dibuat murni adalah "apakah tick pertama sesudah rehidrasi boleh
menghitung satu bar" — itu kandidat resolver baru, bukan sekadar angka yang diubah di tempat.

Setiap perbaikan **wajib** kontrol negatif (matikan perbaikan → buktikan test GAGAL → kembalikan),
dan angka test **diukur** dari `app/build/test-results/testDebugUnitTest/*.xml`, bukan dari pesan lama.

## 7. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit read-only siklus hidup posisi paper & mesin exit (verifikasi ke kode, branch `patch` @ `a842e35`) |
| 2026-10-16 | Temuan diekspor ke `docs/bugs/temuan_bugs_paper_trade.md` (dokumen ini) — **belum ada perbaikan kode** |
| 2026-10-16 | **Batch 3b:** P2 + P3 + P4 diperbaiki + diuji di `8d22fbb` (16 uji di `PaperStopResolverTest`, kontrol negatif 4/16 gagal). Lihat `docs/LAPORAN-batch3-paper-safety-2026-10-16.md` |
| 2026-10-16 | **Batch 3c:** P1 diperbaiki + diuji di `e33b238` (13 uji baru, kontrol negatif 8/13 gagal) ⇒ **ketiga temuan kritis (P1, P2, P3) tertutup.** Lihat `docs/LAPORAN-batch3-rehidrasi-2026-10-16.md` |
| 2026-10-16 | **Batch 5c:** P6 diperbaiki **sebagian** di `1665bf4` (jendela momentum satu sumber; sisa `peak` dicatat eksplisit). Lihat `docs/LAPORAN-batch5-p6-2026-10-16.md` |
| 2026-10-16 | **Batch 5d:** P5 diperbaiki di `95e0448`. Lihat `docs/LAPORAN-batch5-p5-2026-10-16.md` |
| 2026-10-16 | **Sinkronisasi dokumen (tanpa perubahan kode):** butir yang sudah ditutup (P1–P5) dipangkas dari badan dokumen dan diringkas di §4; P6, P7, P8 dipertahankan dengan nomor baris terverifikasi ulang di `patch` @ `547048d` + review owner. Lihat `docs/LAPORAN-sync-dokumen-temuan-2026-10-16.md` |
