# Laporan — Perbaikan P0: Rehidrasi State Machine Exit Paper

**Tanggal:** 2026-10-13 · **Repo:** `Agus27712/analisa-pasar` (branch `main`, HEAD `67f0efd`)
**Dasar:** audit kode yang Anda kirimkan (P0 — posisi paper orphan setelah restart)
**Status:** kode selesai & terverifikasi · **belum di-commit / belum di-push**

---

## A. Ringkasan

Saya memverifikasi audit Anda ke kode sebenarnya. **Semua klaim P0 terbukti benar.** Namun saat
verifikasi saya menemukan **satu cacat tambahan yang tidak tertangkap audit** — dan cacat itu
membuat rekomendasi awal ("rehidrasi dari `positionStore`") justru **berbahaya**.

| Item | Hasil |
|---|---|
| Klaim P0 (machines in-memory, `auto_sell_enabled` persisten) | ✅ Terverifikasi di kode |
| Rekomendasi audit (rehidrasi dari `positionStore`) | ⚠️ **Tidak cukup** — lihat §C |
| Perbaikan yang diterapkan | Persistensi SL asli + penanda `_paper_engine` + `PaperRehydrate` |
| Test | **335 test · 0 gagal · 1 dilewati** (baseline 311 → **+24**) |
| Build | **BUILD SUCCESSFUL** — APK 26,5 MB |
| P1 (akar `EARLY_ENTRY`) | Tetap ditunda — sesuai keputusan Anda |

---

## B. Verifikasi audit (bukan asumsi)

Saya lacak langsung ke kode, bukan mempercayai laporan:

| Klaim audit | Bukti di kode | Verdict |
|---|---|---|
| `machines` hanya di memori | `PaperAutoTradeCoordinator.kt:39` — `private val machines = ConcurrentHashMap<...>()` | ✅ Benar |
| Posisi persisten | `SpotPositionStore.kt:62` — `SharedPreferences` terenkripsi | ✅ Benar |
| `auto_sell_enabled` persisten | `SpotPositionStore.kt:577` — `.putBoolean("${key}_auto_sell_enabled", enabled)` | ✅ Benar |
| Saat buy, auto-sell lama dimatikan | `PaperAutoTradeCoordinator.kt:236` — `enabled = !p2OwnsExit` | ✅ Benar |
| `ownsExit` + guard ada | `PaperAutoTradeCoordinator.kt:94`, dipakai di `TradingViewModelAlerts.kt:150` & `:419` | ✅ Benar |
| FSM dilepas bila posisi hilang | `PaperAutoTradeCoordinator.kt:334` | ✅ Benar |

**Kesimpulan:** setelah app di-kill, ketiga kondisi bertemu dan posisi menggantung —
engine paper kosong (memori), jalur lama mati (`auto_sell_enabled = false`), posisi masih `HOLDING`.

---

## C. Temuan baru: SL sintetis 1% membuat rehidrasi naif BERBAHAYA

Ini bagian terpenting dari laporan ini.

`SpotPositionStore.setHolding` menulis SL **sintetis**:

```kotlin
// SpotPositionStore.kt:331,362
val stopLossPrice = if (current.stopLossPrice > 0.0) current.stopLossPrice
                    else if (entry > 0.0) entry * 0.99 else 0.0   // ← ≈1%
    .putString("${key}_stop_loss", stopLossPrice.toString())
```

`markBought` tidak menerima parameter SL, dan `setAutoSellParams` versi lama **tidak menulis
`_stop_loss` sama sekali**. Jadi untuk setiap posisi yang dibuka auto-buy paper, SL yang
tersimpan di disk adalah `entry × 0,99` — **bukan** SL asli dari sinyal.

Padahal SL asli engine minimal sebesar **lantai friksi**:

| Besaran | Nilai |
|---|---|
| `min_friction_multiple` | 6,0 |
| Friksi round-trip USDT Tokocrypto | 0,9688% |
| → SL minimal | **≈ 5,8%** |
| SL yang tersimpan di disk | **1,0%** |
| **Selisih** | **≈ 5,8× terlalu rapat** |

**Akibatnya:** rehidrasi yang membaca `positionStore` apa adanya akan membangun mesin dengan SL
1%. Posisi yang seharusnya bertahan pada penurunan 5% akan **di-stop-out palsu** pada penurunan
1%, lalu tercatat di jurnal sebagai trade rugi. Itu **lebih buruk daripada tidak melakukan
apa-apa** — ia mengarang data yang tampak sah.

Karena itu perbaikannya harus mencakup **memperbaiki apa yang dipersistenkan**, bukan hanya
membacanya kembali.

---

## D. Yang diimplementasikan

### D.1 SL asli ikut dipersistenkan

`SpotPositionStore.setAutoSellParams(..., stopLossPrice: Double = 0.0)` — menulis SL asli sinyal,
menimpa nilai sintetis. Default `0.0` mempertahankan perilaku lama (backward compatible).
`tryPaperAutoBuy` kini meneruskan `signal.stopLoss`.

**Efek samping yang menguntungkan:** saat pengguna mematikan auto-exit dan kepemilikan kembali ke
jalur auto-sell lama, jalur itu sekarang memakai SL 5,8% yang benar, bukan 1%.

### D.2 Penanda persisten `_paper_engine`

Tanpa penanda, tidak ada cara membedakan "posisi yang exit-nya dipegang engine paper" dari posisi
lain setelah restart.

| Fungsi | Peran |
|---|---|
| `setPaperEngineOwned(...)` | Ditulis saat engine mengambil alih; dilepas saat posisi ditutup |
| `isPaperEngineOwned(...)` | Pembacaan |
| `getPaperEngineSymbols(exchange)` | Enumerasi posisi bertanda yang masih `HOLDING` |

Dibersihkan di **empat** titik: `markSold`, `finalizePaperExit`, `clearPaperExitState`, dan saat
posisi hilang terdeteksi.

> Catatan penting: `getAllActiveTrailingSymbols` **tidak bisa** dipakai untuk ini. Fungsi itu
> mensyaratkan `trailing_enabled = true`, sedangkan posisi paper justru mematikan trailing saat
> engine mengambil alih exit. Ini salah satu alasan pendekatan rehidrasi naif akan gagal.

### D.3 `PaperRehydrate` — aturan keputusan yang bisa diuji

Model murni (`engine/scalping/PaperRehydrate.kt`) dengan tiga keluaran:

| Aksi | Kapan | Tindakan |
|---|---|---|
| `Restore` | auto-exit aktif ∧ `0 < SL < entry` | Bangun ulang mesin, `openedAt` asli dikembalikan |
| `HandBackToLegacy` | auto-exit mati **atau** SL tidak valid | `auto_sell_enabled` dinyalakan kembali + penanda dilepas |
| `Skip` | entry ≤ 0 / NaN | Dilewati |

Urutan prioritas disengaja: **auto-exit mati diperiksa lebih dulu**, karena kepemilikan exit
ditentukan oleh toggle, bukan oleh kondisi SL.

### D.4 Dijalankan sekali saat startup

`TradingViewModel.rehydratePaperAutoExit()` dipanggil dari `initSubscriptionsAndPolling()`,
dibungkus `runCatching` supaya kegagalan rehidrasi tidak menjatuhkan startup.

---

## E. Verifikasi

| Uji | Hasil |
|---|---|
| Unit test | **335 test · 0 gagal · 1 dilewati** (48 kelas) |
| Baseline HEAD `67f0efd` | **311 test · 0 gagal · 1 dilewati** (46 kelas) |
| Delta | **+24 test** (16 `PaperRehydrateTest` + 8 `SpotPositionStorePaperMarkerTest`) |
| Build APK debug | **BUILD SUCCESSFUL** — 26,5 MB |

> **Koreksi yang perlu Anda tahu.** Laporan sebelumnya menyebut "183 test". Angka itu **salah** —
> berasal dari direktori hasil test yang tidak lengkap. Saya ukur ulang baseline dengan menyimpan
> pekerjaan saya, menjalankan test di HEAD yang bersih, lalu mengembalikannya. Baseline yang benar
> adalah **311**. Selisih 183 → 311 bukan kemajuan, melainkan kesalahan pelaporan yang kini
> diperbaiki.

**Cakupan test baru:**

- `PaperRehydrateTest` (16) — jalur pulih **dan yang lebih penting jalur tolak**: SL nol, SL ≥ entry,
  SL NaN, SL ∞, entry nol/negatif/NaN, plus urutan prioritas auto-exit-mati.
- `SpotPositionStorePaperMarkerTest` (8, Robolectric) — SL asli benar-benar menimpa SL sintetis,
  `stopLossPrice = 0` mempertahankan perilaku lama, penanda bertahan, enumerasi hanya posisi
  bertanda + `HOLDING`, isolasi antar-bursa.

---

## F. Batasan yang diterima (jangan dianggap beres)

1. **`barsHeld` / `isBeArmed` / `isPartialDone` / `peak` reset.** Tidak dipersistenkan. Posisi yang
   sudah bergerak ke breakeven atau trailing akan dievaluasi ulang dari awal. Diterima untuk paper
   (sesuai catatan audit Anda), tetapi **wajib dicatat** — mesin yang dipulihkan tidak identik
   dengan mesin yang hilang.
2. **Posisi warisan tidak disentuh.** Posisi yang dibeli **sebelum** penanda ini ada tidak punya
   `_paper_engine`, jadi sengaja dilewati: SL-nya tersimpan salah (1%), dan memulihkannya akan
   memalsukan stop-out. Posisi seperti itu perlu ditangani manual.
3. **Parameter exit memakai konfigurasi saat ini.** Bila Anda mengubah `ScalpingRuntimeConfig`
   di antara dua sesi, mesin yang dipulihkan memakai parameter baru, bukan parameter saat posisi
   dibuka. Disengaja, tapi perlu diketahui.
4. **Rehidrasi hanya untuk `isReal = false`.** Tidak ada jalur ke uang riil — larangan tetap berlaku.

---

## G. Yang belum dikerjakan

| Item | Status | Alasan |
|---|---|---|
| **P1 — akar `EARLY_ENTRY` di engine** | ⛔ Ditunda | Mengubah statistik log sinyal + pemicu alert `isNewDetected` + `MonitorCard`. Butuh regresi penuh sebagai perubahan tersendiri. UI sudah aman lewat `ScalpReadiness`. |
| Refresh `PairEdgeGate` | ⛔ Belum | Tabel MFE masih statis 30 hari; regenerasi manual via `tools/pair_gate.py` |
| Walk-forward lintas bulan (P4.1) | ⛔ Belum | Pemisahan train/test masih satu potong |
| Data Indodax + replay TAPI v2 | ⛔ Belum | Sesuai arah: Tokocrypto dulu |

**Kesiapan uang riil: tetap BELUM.** Perbaikan ini menutup lubang operasional (posisi menggantung),
bukan menambah edge. Profil exit masih skew negatif dengan margin ~2,3 pp di atas titik impas.

---

## H. Dokumen yang diperbarui

| Berkas | Isi |
|---|---|
| `workplan2.md` §12 | Akar masalah, cacat SL sintetis, perbaikan, batasan, uji |
| `AGENTS.md` | Aturan wajib: tiga hal yang harus tetap sinkron di siklus hidup posisi paper |
| `SCALP_EXIT.md` | Poin 5 di "Port ke app" — rehidrasi & persistensi SL asli |

---

## Lampiran — berkas yang berubah

**Baru (3):**
- `app/src/main/java/agu/analys/engine/scalping/PaperRehydrate.kt`
- `app/src/test/java/agu/analys/engine/scalping/PaperRehydrateTest.kt`
- `app/src/test/java/agu/analys/trading/SpotPositionStorePaperMarkerTest.kt`

**Berubah (5):**
- `app/src/main/java/agu/analys/trading/SpotPositionStore.kt` — `stopLossPrice` + penanda + enumerasi
- `app/src/main/java/agu/analys/viewmodel/PaperAutoTradeCoordinator.kt` — `rehydratePaperAutoExit()` + `openedAt` + pembersihan penanda
- `app/src/main/java/agu/analys/viewmodel/TradingViewModelSync.kt` — hook startup
- `AGENTS.md`, `workplan2.md`, `SCALP_EXIT.md` — dokumen
