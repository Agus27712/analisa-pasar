# Laporan Batch 6i — N6 + N17 + N11: satu mode, satu mesin, satu angka

**Tanggal:** 2026-10-17
**Branch:** `patch`
**Temuan:** N6 + N17 dan sisa N11 — `docs/bugs/temuan_bugs_notifikasi.md`
**Keputusan owner:** N6/N17 memilih **opsi (a)** — worker hanya menghitung mode yang sedang aktif;
N11 memilih **jangan tampilkan** tabel tier.

## Ringkasan

Tiga butir terakhir di audit notifikasi ditutup dalam satu batch karena akarnya sama: **angka yang
sama dirakit di lebih dari satu tempat**.

- **N6** — `CandidateScanWorker` mengevaluasi SWING + INTRADAY + SCALPING sekaligus, lalu menulis ke
  `SignalLifecycleManager` singleton yang sama dengan engine foreground. Key `SYMBOL#MODE` punya dua
  produsen.
- **N17** — versi SWING dari worker **tidak pernah** meneruskan `macroAnomalyResult`, sehingga skor
  yang ia tulis selalu lebih tinggi daripada skor engine untuk input yang sama.
- **N11** — tabel "Akurasi per Level Confidence" memakai ambang lintas-mode (≥80/60–79/<60).
  Sinyal scalping pra-entry di-cap ≤59, jadi hampir semuanya jatuh ke "Awal" dan tabel itu mengukur
  "sudah READY atau belum", bukan kualitas berjenjang.

## Perbaikan

### 1. Seam tunggal — `engine/ModeSignalEvaluator.kt` (baru)

Satu-satunya pemetaan `StrategyMode → evaluator`:

```kotlin
object ModeSignalEvaluator {
    fun evaluate(input: ModeSignalInput): ModeSignal? = when (input.mode) {
        StrategyMode.SCALPING -> evaluateScalping(input)
        StrategyMode.SWING -> evaluateSwing(input)
        StrategyMode.OFFICE_DAILY -> evaluateIntraday(input)
    }
}
```

Yang penting bukan pembungkusnya, melainkan **apa yang tidak lagi bisa dilakukan pemanggil**:

- `macroAnomalyResult` **tidak ada** di `ModeSignalInput`. Penalti dihitung di dalam seam:
  `MacroAnomalyDetector.evaluate(input.d1Candles, input.price)` untuk SWING dan OFFICE_DAILY.
  Tidak ada parameter yang bisa "lupa" diisi — akar N17 hilang secara struktural.
- Forging bar berjalan (M15/M1) juga dilakukan di dalam seam dari tick, jadi engine dan worker
  memakai bentuk candle yang sama.
- `LearningTradingEngine` (`runScalping`/`runSwing`/`runIntraday`) sekarang memanggil seam yang sama.
  Import `ScalpingMtfEvaluator`/`SwingEvaluator` di engine dihapus.

### 2. Worker hanya mode aktif — `service/CandidateScanWorker.kt`

- Membaca `prefs.strategyMode` **sekali** per pemindaian, lalu `when (mode)` memilih data yang
  diambil dan satu jalur evaluasi. Tidak ada lagi tiga blok SWING/INTRADAY/SCALPING per simbol.
- `SignalLifecycleManager.process(...)` kini dipanggil **satu kali** per simbol (dulu tiga).
- Input disamakan dengan engine:
  - SWING: H1 **200** + D1 **100**, H1 `dropLast(1)`.
  - OFFICE_DAILY: H4 **200** + D1 **100**, H4 `dropLast(1)`.
  - SCALPING: H1/M15/M1 60, semua `dropLast(1)`, **orderbook nyata** lewat `fetchOrderBook`.
- `diagnosticIgnoreOrderBookWhenUnavailable = true` **dihapus** dari jalur produksi. Tanpa
  orderbook, Step2 GAGAL — bukan dilewati. Ini yang mencegah skor worker lebih tinggi dari engine.
- Notifikasi memakai `tracked.activeSignalState ?: evaluation.signal`, sama dengan yang dipakai
  kartu engine (`engine` juga memakai `activeSignalState ?: result.signal`). Satu sumber angka.

**Baterai.** Pemindaian tetap **hanya watchlist** (`prefs.getWatchlist()`), dan sekarang justru
lebih ringan: satu simbol hanya mengambil data mode aktif (dulu selalu H1 + H4 + M1 + M15). Untuk
SCALPING ada batas `MAX_SCALP_BACKGROUND_SYMBOLS = 10` karena satu simbol butuh empat permintaan.

### 3. N11 — tabel tier dihapus

- `SignalLogScreen.kt`: kartu `ConfidenceReliabilityBreakdownCard`, `ConfidenceTierRow`, dan section
  `confidence_tier_section` dihapus.
- `SignalReliabilityModels.kt`: `ConfidenceTierStats` + tiga field tier dihapus.
- `SignalLogRepository.kt`: `buildTierStats(...)` + pemanggilannya dihapus.
- `ConfidenceTierNote.kt` + `ConfidenceTierNoteTest.kt` dihapus. Catatan itu hanya punya makna
  sebagai penjelas tabel tier; tanpa tabel, ia menjadi teks tanpa konteks.
- **Cap `ScalpingMtfEvaluator` tidak diubah** (59/49/39/29 tetap). Keputusan owner: jangan
  menampilkan tier lintas-mode, bukan mengubah skor yang sudah tampil di tempat lain.
- Kartu **Akurasi per Setup** tetap ada — itu mengelompokkan per setup/skor engine dalam satu mode,
  bukan tier lintas-mode.

## Uji

**11 uji baru** di tiga berkas:

| Berkas | Isi |
|---|---|
| `ModeSignalEvaluatorTest` (4) | SWING: penalti −30 dari D1 benar-benar masuk (seam == engine, dan == skor tanpa anomali −30). INTRADAY: parabolic unwind memaksa confidence 0 + HOLD; tanpa D1 skor > 0. SCALPING: orderbook nyata ⇒ BUY, orderbook kosong ⇒ bukan BUY. Mode tanpa candle ⇒ `null`. |
| `CandidateScanWorkerSourceTest` (5) | worker membaca `prefs.strategyMode`; hanya **satu** `SignalLifecycleManager.process(`; tidak memanggil evaluator mode langsung; tidak memakai `diagnosticIgnoreOrderBookWhenUnavailable = true`; mengambil D1 + `dropLast(1)`; engine memakai seam dan **tidak** menghitung anomali sendiri. |
| `ConfidenceTierRemovalTest` (2) | layar tidak lagi memuat kartu/section tier; model tidak lagi membawa `ConfidenceTierStats`. |

Uji SWING memakai fixture yang sama dengan `SwingEvaluatorTest` (uptrend 150 bar + rejection), dan
D1 buatan: baseline 600, puncak 2000 (pump 3,3×), turun 19 hari ⇒ penalti **−30**. Dengan skor tanpa
anomali 36, seam harus menghasilkan 6 — cukup untuk membedakan, belum terpotong ke nol.

## Kontrol negatif

| Kontrol | Perubahan | Hasil |
|---|---|---|
| A | `macroAnomalyResult = null` di seam (SWING + INTRADAY) | **2 GAGAL** |
| B | worker kembali memanggil `ScalpingMtfEvaluator.evaluate(... diagnosticIgnoreOrderBookWhenUnavailable = true)` | **2 GAGAL** (evaluator langsung + jalur diagnostik) |
| C | worker menambah `SignalLifecycleManager.process(...)` kedua | **1 GAGAL** |
| D | engine menghitung `MacroAnomalyDetector.evaluate(...)` sendiri | **1 GAGAL** |
| E | kartu tier dihidupkan kembali di `SignalLogScreen` | **1 GAGAL** |

**Angka terukur (dari XML):**

| Tahap | Kelas | Uji | Gagal | Error | Dilewati |
|---|---|---|---|---|---|
| Awal batch (batch 6h) | 82 | 656 | 0 | 0 | 1 |
| Setelah N11 dihapus (12 uji tier ikut dihapus) | 81 | 644 | 0 | 0 | 1 |
| Setelah 11 uji baru | **84** | **655** | **0** | **0** | **1** |

## Batas

- **Snapshot tetap berbeda.** Worker latar berjalan tiap 15 menit dan tidak punya forming volume /
  orderbook live seperti engine per-tick. Yang dijamin adalah **metode dan gerbang yang sama**;
  skor bisa bergerak mengikuti pasar. Jangan menjanjikan angka identik di detik yang sama.
- **Mode lain tidak punya produsen latar.** Saat mode aktif SWING, worker tidak lagi mengirim
  notifikasi kandidat INTRADAY/SCALPING. Itu konsekuensi langsung dari keputusan (a), bukan bug.
- **Scalping latar dibatasi 10 simbol** dan orderbook diambil per simbol (4 permintaan/simbol).
  Kalau batas ini dinaikkan, ukur dampak baterai/rate-limit dulu.
- **Tabel tier hilang, bukan diganti.** Tidak ada tabel lintas-mode pengganti. Kalau nanti ingin
  statistik berjenjang, ia harus dibuat **per mode** dengan ambang yang menghormati cap tahap —
  dan itu keputusan produk baru.
- Cooldown kandidat 5 menit (N12) tetap berlaku; dengan worker yang kini hanya satu mode, flapping
  antar-mode tidak lagi menambah tekanan, tetapi cooldown tetap dipertahankan.

## Berkas yang berubah

- **Baru:** `engine/ModeSignalEvaluator.kt`
- `engine/LearningTradingEngine.kt` — tiga blok evaluasi memakai seam; import lama dihapus.
- `service/CandidateScanWorker.kt` — satu mode, satu lifecycle, orderbook nyata, D1 untuk anomali.
- `ui/screens/SignalLogScreen.kt` — kartu tier + baris tier dihapus.
- `model/SignalReliabilityModels.kt` — `ConfidenceTierStats` + field tier dihapus.
- `database/SignalLogRepository.kt` — perhitungan tier dihapus.
- **Dihapus:** `model/ConfidenceTierNote.kt`, `model/ConfidenceTierNoteTest.kt`.
- **Uji baru:** `engine/ModeSignalEvaluatorTest.kt`, `service/CandidateScanWorkerSourceTest.kt`,
  `model/ConfidenceTierRemovalTest.kt`.
- **Uji disesuaikan:** `database/SignalOutcomeClassificationTest.kt` (uji tier dihapus).
