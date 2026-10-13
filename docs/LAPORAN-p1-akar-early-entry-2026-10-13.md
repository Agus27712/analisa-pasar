# Laporan — Perbaikan P1: Akar `EARLY_ENTRY` di Engine

**Tanggal:** 2026-10-13 · **Repo:** `Agus27712/analisa-pasar` (branch `main`)
**Dasar:** audit kode yang Anda kirimkan (P1 — akar `EARLY_ENTRY` masih ada di engine)
**Status:** kode selesai & terverifikasi · **belum di-commit / belum di-push**

---

## A. Ringkasan

Audit Anda benar: perbaikan sebelumnya (`workplan2.md` §11) hanya melindungi **lapisan UI**.
Engine masih bisa memancarkan rantai `step2Ok (orderbook) → EARLY_ENTRY → DETECTED` tanpa pola
valid. Sekarang akarnya ditutup **di dua lapis** — di producer sinyal dan di state machine.

| Item | Hasil |
|---|---|
| Klaim P1 (engine masih bisa `EARLY_ENTRY` tanpa setup) | ✅ Terverifikasi di kode |
| Blast radius | ⚠️ Lebih sempit dari dugaan — lihat §C |
| Perbaikan | Syarat pola di `ScalpingMtfEvaluator` + normalisasi stage di `SignalLifecycleManager` |
| Test | **338 test · 0 gagal · 1 dilewati** (baseline 335 → **+3**) |
| Kontrol negatif | ✅ Kedua test baru **terbukti gagal** saat perbaikan dimatikan |

---

## B. Verifikasi audit (bukan asumsi)

Saya lacak sendiri rantai yang Anda sebut, bukan menerima deskripsinya:

```
ScalpingMtfEvaluator.kt:246   early = !ready && !isDangerous && step2Ok
                              ^^^ step2Ok = step1Ok && isOrderBookValid → TIDAK memeriksa setup
ScalpingMtfEvaluator.kt:294   early -> ScalpingStage.EARLY_ENTRY
SignalLifecycleManager.kt:112 EARLY_ENTRY, WAIT_PULLBACK -> LifecycleState.DETECTED
```

`isOrderBookValid` (`ScalpingMtfEvaluator.kt:122-126`) hanya menuntut `buyPressure > 1.0`.
Jadi **tekanan orderbook saja** sudah cukup untuk memicu `EARLY_ENTRY`, sementara
`scalpingSetup` tetap `"NONE"`. Terkonfirmasi.

---

## C. Blast radius — lebih sempit dari yang tampak

Ini bagian yang mengubah cara perbaikan dikerjakan, jadi saya uraikan eksplisit.

### C.1 Yang **tidak** rusak (penting untuk tidak menakut-nakuti diri sendiri)

Dua konsumen `isNewDetected` ternyata **tidak terpengaruh**, karena keduanya mensyaratkan
`action == BUY`:

| Konsumen | Gerbang | Kenapa aman |
|---|---|---|
| `tryPaperAutoBuy` (`PaperAutoTradeCoordinator.kt:186`) | `if (signal.action != SignalAction.BUY) return false` | `EARLY_ENTRY` selalu dikirim dengan `action = HOLD` |
| `AlertNotificationHelper` (`LearningTradingEngine.kt:299`) | `if (trans.hasTriggeringTransition)` | `hasTriggeringTransition` = `(isNewDetected \|\| isNewReady) && action == BUY` |

Kenapa selalu `HOLD`? Karena di engine `direction = if (ready) LONG else WAIT` dan
`finalAction = if (direction == LONG) BUY else HOLD` — dan `early` secara definisi berarti
`!ready`. Jadi **tidak ada posisi paper yang terbuka salah dan tidak ada notifikasi palsu** yang
pernah dikirim akibat bug ini.

### C.2 Yang benar-benar rusak

| Jalur | Dampak nyata |
|---|---|
| `LearningTradingEngine.kt:295` → `AppLogManager.aiEngine("SignalEngine", "… IDLE ➔ DETECTED \| Sinyal: ⚪ HOLD …")` | Log transisi mencatat "DETECTED" untuk sinyal tanpa pola → **statistik log sinyal menyesatkan** |
| `TradingViewModelSync.kt:468` | `isNewDetected` dianggap transisi layak → memanggil `tryPaperAutoBuy` (yang lalu menolak sendiri) |
| `WaitingEntryRadarCard.kt:328` | `DETECTED` → "👁 TERDETEKSI" (sudah terlindungi UI sejak §11) |

**Kesimpulan §C:** bug ini adalah bug **kejujuran status & log**, bukan bug eksekusi. Itu sebabnya
perbaikan ini aman dilakukan tanpa risiko dana — dan juga sebabnya perbaikan ini **tidak**
menambah edge apa pun.

---

## D. Perbaikan — dua lapis

### D.1 Lapis 1 (akar) — `ScalpingMtfEvaluator.kt`

```kotlin
val early = !ready && !isDangerous && step2Ok && setup != ScalpSetupType.NONE
```

Tanpa pola, stage kini turun ke `WATCH`, bukan `EARLY_ENTRY`. Label yang bergantung padanya ikut
disinkronkan supaya tidak ada klaim palsu tersisa:

| Tempat | Sebelum | Sesudah |
|---|---|---|
| `reasons[0]` saat early | "EARLY: kondisi awal terbentuk, tunggu setup & konfirmasi penuh." | "EARLY: setup X terbentuk, menunggu trigger & konfirmasi penuh." |
| `reasons[0]` saat orderbook OK tanpa pola | "WAIT: belum ada setup valid. Menunggu momentum & orderbook." | "WAIT: tekanan orderbook sudah mendukung, tetapi belum ada setup scalping valid." |
| `statusTitle` | `step2Ok` jatuh ke "BIAS OK (1/4)" — **menghitung salah** (orderbook lolos tapi tidak dihitung) | cabang baru "ORDERBOOK OK (2/4) · SETUP BELUM ADA" |
| `waitingFor` | `step2Ok` → "Setup scalping valid & VWAP" (dipakai untuk dua keadaan berbeda) | dipisah: `early` → "Trigger VWAP/VSA & gate Net R:R"; `step2Ok` → "Setup scalping valid (sweep/retest/breakout/pullback)" |

Perbaikan `statusTitle` itu temuan tambahan: sebelumnya keadaan "orderbook sudah lolos tetapi pola
belum ada" dilabeli **1/4**, padahal dua checkpoint sudah lolos. Sekarang jujur 2/4.

### D.2 Lapis 2 (jaring pengaman) — `SignalLifecycleManager.kt`

Di cabang `SCALPING`, stage yang tidak didukung pola dinormalisasi **sebelum** dipakai mesin transisi:

```kotlin
val stage = when {
    ScalpReadiness.hasSetup(rawSignal) -> rawSignal.scalpingStage
    rawSignal.scalpingStage == ScalpingStage.HOLD -> ScalpingStage.HOLD
    else -> ScalpingStage.WATCH
}
```

**Kenapa normalisasi di satu titik, bukan menambal tiap cabang?** Karena invarian jadi berlaku
untuk **semua** jalur promosi — termasuk cabang yang ditambahkan orang lain di masa depan.
Menambal `IDLE` saja akan meninggalkan celah di `DETECTED → CONFIRMING` dan `CONFIRMING → READY`.

**Kenapa `HOLD` dikecualikan?** Ini yang saya temukan saat menguji desainnya: kalau `HOLD` ikut
dinormalisasi menjadi `WATCH`, sinyal yang sudah `CONFIRMING` akan **macet** saat kondisi berbahaya
muncul tanpa pola terbaca (cabang `CONFIRMING + WATCH` memang sengaja bertahan, sedangkan
`CONFIRMING + HOLD` membatalkan). Pengecualian ini menjaga **jalur invalidasi tidak ikut berubah** —
kondisi berbahaya tetap membatalkan sinyal yang sudah `DETECTED`/`READY`.

**Perilaku sinyal sah tidak berubah.** `WATCH` dan `HOLD` sudah punya transisi yang benar di mesin
ini: `IDLE` tetap `IDLE`, `DETECTED` turun ke `IDLE`, `READY` dibatalkan.

Predikat "punya pola" memakai `ScalpReadiness.hasSetup()` — **satu sumber kebenaran yang sama**
dengan lapisan UI, jadi kedua lapis tidak bisa berbeda pendapat.

---

## E. Verifikasi

### E.1 Test & build

| | Baseline (`2c514d3`, HEAD bersih) | Sesudah perbaikan |
|---|---|---|
| Kelas test | 48 | 48 |
| Test | **335** | **338** |
| Gagal | 0 | 0 |
| Dilewati | 1 | 1 |
| APK | — | **27.768.294 byte (26,5 MB)**, `assembleDebug` BUILD SUCCESSFUL |

Baseline diukur dengan menyimpan pekerjaan saya (`git stash`), menjalankan test di HEAD bersih,
lalu mengembalikannya — bukan dari ingatan. Hasilnya persis 335, cocok dengan angka akhir
pekerjaan P0 yang sudah di-merge.

Test baru (+3):

- `ScalpingMtfEvaluatorTest.testInvariant_EarlyEntryRequiresSetup`
- `SignalLifecycleManagerTest.testScalping_NoSetupNeverPromoted`
- `SignalLifecycleManagerTest.testScalping_DangerousHoldStillInvalidatesEvenWithoutSetup`

`SignalLifecycleManagerTest.testScalping_BackwardsCompatibility` diperbarui: sinyal scalping kini
wajib menyertakan `scalpingSetup` valid.

### E.2 Kontrol negatif — membuktikan test benar-benar menangkap bug

Test yang selalu hijau tidak membuktikan apa pun. Jadi saya **matikan perbaikan satu per satu** dan
memastikan test-nya gagal:

| Perbaikan dimatikan | Hasil |
|---|---|
| `early` dikembalikan ke `!ready && !isDangerous && step2Ok` | ❌ `AssertionError: Tanpa pola, stage tidak boleh EARLY_ENTRY — ini akar bug radar vs setup. Actual: EARLY_ENTRY` |
| Normalisasi stage dikembalikan ke `rawSignal.scalpingStage` | ❌ `AssertionError: expected:<IDLE> but was:<DETECTED>` |

Keduanya kemudian dikembalikan, dan saya verifikasi ulang lewat `git diff` bahwa barisnya utuh.

Catatan: pada kontrol negatif kedua, `testScalping_DangerousHoldStillInvalidatesEvenWithoutSetup`
**tetap hijau** — memang itu tujuannya, membuktikan jalur invalidasi tidak ikut ternormalisasi.

### E.3 Skenario test evaluator

Skenario dibuat **deterministik** (konstanta, bukan `Math.random()`) supaya tidak flaky:
M1 benar-benar datar (tidak ada sweep/breakout/retest/pullback), M15 & H1 menurun landai sehingga
resistance jelas di atas harga, dan orderbook dianggap memadai
(`diagnosticIgnoreOrderBookWhenUnavailable = true` — jalur yang sama dipakai `CandidateScanWorker`).

Test menegaskan **prasyarat**-nya lebih dulu (`setup == "NONE"` **dan** `setupOk == true`), jadi test
tidak bisa "lulus secara hampa": kalau suatu hari skenario tidak lagi menghasilkan kondisi pra-pola,
test akan gagal dan memberi tahu, bukan diam-diam kehilangan makna.

---

## F. Batasan (jangan dianggap beres)

- **Kesiapan uang riil tetap BELUM.** Perbaikan ini menutup lubang kejujuran status, **bukan**
  menambah edge. Profil exit masih skew negatif (menang ≈ +0,09R, kalah ≈ −1,16R, WR ≈ 95%,
  margin impas hanya ~2,3 poin persentase) dan gate ketat out-of-sample hanya 5 pair.
- **Angka `confidence` untuk keadaan pra-pola tetap 49.** Nilai itu setara "2/4 checkpoint", jadi
  konsisten dengan hitungan checkpoint — tetapi bisa dibaca sebagai "hampir siap entry". UI scalping
  tidak memakainya untuk menyimpulkan status (`ScalpReadiness` yang menentukan), namun log sinyal
  masih menampilkannya.
- **Statistik log sinyal historis tidak dihitung ulang.** Sinyal `DETECTED` palsu yang sudah
  tercatat sebelum perbaikan ini tetap ada di basis data. Perbaikan berlaku untuk sinyal baru.
- **`SCALP_EXIT.md` tidak diubah** — dokumen itu murni tentang jalur exit. `EARLY_PROFIT` di sana
  adalah *alasan exit*, bukan stage sinyal, jadi tidak terkait P1.

---

## G. Yang belum dikerjakan

| Item | Status |
|---|---|
| P4.1 walk-forward antar-bulan (split sekarang masih single-cut 70/30) | Belum |
| P4.2 preferensi maker otomatis untuk pair IDR | Belum |
| P4.3 refresh otomatis tabel `PairEdgeGate` (sekarang data statis 30 hari) | Belum |
| P5 data Indodax + harness replay + TAPI v2 end-to-end | Belum |
| P6 auto-execute **uang riil** | **DILARANG** sampai gate edge lolos |

---

## H. Dokumen yang diperbarui

| Dokumen | Perubahan |
|---|---|
| `workplan2.md` | §11.4 diubah dari "sengaja belum dilakukan" → **SELESAI**, menunjuk §13 |
| `workplan2.md` | **§13 baru** — akar masalah, blast radius, perbaikan dua lapis, uji, batasan |
| `AGENTS.md` | Aturan wajib baru: invarian pola ditegakkan **di engine**, bukan hanya UI; larangan melemahkan syarat `early` |
| `docs/LAPORAN-p1-akar-early-entry-2026-10-13.md` | Dokumen ini |

---

## Lampiran — berkas yang berubah

| Berkas | Δ |
|---|---:|
| `app/src/main/java/agu/analys/engine/scalping/ScalpingMtfEvaluator.kt` | +12 / −3 |
| `app/src/main/java/agu/analys/engine/scalping/SignalLifecycleManager.kt` | +24 / −4 |
| `app/src/test/java/agu/analys/engine/scalping/ScalpingMtfEvaluatorTest.kt` | +84 / −0 |
| `app/src/test/java/agu/analys/engine/scalping/SignalLifecycleManagerTest.kt` | +80 / −1 |
| `AGENTS.md` | +16 / −6 |
| `workplan2.md` | +105 / −5 |
| `docs/LAPORAN-p1-akar-early-entry-2026-10-13.md` | baru (227 baris) |

Total pada berkas terlacak: **+321 / −19** (6 berkas).
