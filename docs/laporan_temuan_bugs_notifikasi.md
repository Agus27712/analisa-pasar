# Laporan Temuan Audit — Jalur Notifikasi & Skor Keyakinan

**Audit:** 2026-10-16 · **Ditutup seluruhnya:** 2026-10-17 · **Dipindahkan ke sini:** 2026-10-17
**Letak berkas:** `docs/laporan_temuan_bugs_notifikasi.md`. Berkas ini **tidak lagi** berada di
`docs/bugs/`, karena seluruh temuannya sudah tertutup. Aturannya: `docs/bugs/` hanya memuat
temuan yang **masih hidup**; berkas yang sudah beres dipindahkan keluar dengan nama
`docs/laporan_temuan_bugs_<topik>.md`, dan berkas itu **menjadi laporannya** — tidak ada lagi
laporan per batch.
**Isi dokumen:** laporan penutupan. Butir yang sudah ditutup dipangkas dari badan dokumen dan
diringkas di §4; ringkasan per batch ada di §7.
**Verifikasi terakhir:** branch `patch` @ `8316543` (audit awal @ `547048d`). Nomor baris di §1
adalah nomor saat audit awal — dipakai sebagai peta, bukan rujukan terkini.
**Cakupan:** seluruh pengirim notifikasi + seluruh alur `confidence` (produsen → notifikasi → jurnal → UI)

| Status | Temuan | Ringkas |
|---|---|---|
| ✅ **Ditutup seluruhnya** | **N1–N17** | Tidak ada butir hidup. Lihat §4 untuk commit + laporan tiap temuan |

> **Tidak ada pekerjaan tersisa di berkas ini.** Kalau butuh pekerjaan baru di area notifikasi/skor,
> lakukan **audit baru** dan tulis temuannya ke berkas temuan baru di `docs/bugs/`
> (`temuan_bugs_<topik>.md`), jangan menghidupkan kembali nomor lama. Berkas temuan baru itu nanti
> dipindahkan ke sini dengan nama `docs/laporan_temuan_bugs_<topik>.md` begitu seluruh isinya tutup.

## 1. Peta jalur (hasil pemetaan awal, 2026-10-16)

```
PRODUSEN CONFIDENCE
├─ engine/scalping/ScalpingMtfEvaluator.kt  — conf = min(score, cap 59/49/39/29); ready → score.total
├─ engine/swing/SwingEvaluator.kt           — conf = finalScore (− anomalyPenalty, s.d. −60)
├─ engine/intraday/IntradayEvaluator.kt     — conf = baseScore × regimeMultiplier
└─ engine/ModeSignalEvaluator.kt            — TITIK SAMBUNG BARU (batch 6i): satu-satunya perakitan input
                                              untuk SCALPING / SWING / OFFICE_DAILY

JALUR NOTIFIKASI (4 pengirim)
├─ A. viewmodel/TradingViewModelSync.kt  onCandidateSignalTransition → sendCandidateFoundNotification (foreground)
├─ B. service/CandidateScanWorker.kt         periodic 15 menit → mode AKTIF saja (sejak batch 6i)
├─ C. service/TradingForegroundService.kt    loop 4 detik → emergency/trailing/TP via SellSignalLifecycleManager
└─ D. viewmodel/TradingViewModelAlerts.kt    per tick → price alert, trailing hit, peak update, hasil auto-sell

KONSUMEN CONFIDENCE
├─ database/SignalLogRepository.kt  (B5: conf = nilai saat firing, sudah benar)
├─ database/TradeHistoryRecorder.kt → jurnal (confidenceScore dari snapshot)
├─ ui/components/detail/RadarCheckpointStepper.kt (bobot 30% progress), RadarBuySection.kt (faktor ukuran posisi)
└─ ui/screens/SignalLogScreen.kt (tabel tier lintas-mode DIHAPUS batch 6i — N11)
```

## 2. 🟠 Temuan serius (masih hidup)

**Tidak ada.** N6 dan N17 ditutup batch 6i — lihat §4.

## 2b. Temuan dari audit lanjutan 2026-10-16

**Tidak ada yang tersisa.** N17 (skor SWING worker tanpa penalti anomali makro) ditutup batch 6i
bersama N6.

## 3. 🟡 Temuan sedang/rendah (masih hidup)

**Tidak ada.** N11 ditutup batch 6i dengan keputusan produk: tabel tier lintas-mode **tidak
ditampilkan** (cap `ScalpingMtfEvaluator` sengaja tidak diubah).

## 4. Sudah ditutup (ringkasan + pointer laporan)

| Temuan | Commit | Laporan |
|---|---|---|
| N1 — confidence tampil ×100 (6800%) | `cd6c032` | `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` |
| N2 — balapan hilangnya edge trailing | `cd6c032` | `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` |
| N3 — P&L notifikasi pakai fee preset legacy | `cd6c032` | `docs/LAPORAN-batch1-notifikasi-2026-10-16.md` |
| N4 — sakelar notifikasi diabaikan (diperluas: keempat sakelar kategori) | `1a87e3c` | `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` |
| N5 — notifikasi kandidat tanpa disclaimer "uang riil diblokir" | `1a87e3c` | `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` |
| N7 — `markTriggered`/`reset` tidak menormalkan simbol | `1a87e3c` | `docs/LAPORAN-batch2-notifikasi-2026-10-16.md` |
| N10 — atribusi sinyal per simbol di jurnal | `9767c88` | `docs/LAPORAN-batch5-j4n10-2026-10-16.md` |
| N9 (= J9) — default `?: 75` mengarang skor keyakinan | batch 6b | `docs/LAPORAN-batch6-j9-2026-10-17.md` |
| N13 — laporan eksekusi jual memakai kategori `PRICE_ALERT` | batch 6f | `docs/LAPORAN-batch6f-n13n14-2026-10-17.md` |
| N14 — cooldown peak di-bypass jalur kembar | batch 6f | `docs/LAPORAN-batch6f-n13n14-2026-10-17.md` |
| N8 — parameter mati `onlyWhenBackground` | batch 6f | `docs/LAPORAN-batch6f-n13n14-2026-10-17.md` |
| N15 — sakelar notifikasi mematikan foreground service | batch 6g | `docs/LAPORAN-batch6g-n15n16-2026-10-17.md` |
| N16 — `AppPreferences` dibangun berulang di service | batch 6g | `docs/LAPORAN-batch6g-n15n16-2026-10-17.md` |
| N12 — tidak ada cooldown per-pair untuk notifikasi kandidat | batch 6h | `docs/LAPORAN-batch6h-n12-2026-10-17.md` |
| **N6** — dua produsen satu `SignalLifecycleManager` dengan data berbeda | batch 6i | `docs/LAPORAN-batch6i-n6n17n11-2026-10-17.md` |
| **N17** — skor SWING dari worker tanpa penalti anomali makro | batch 6i | `docs/LAPORAN-batch6i-n6n17n11-2026-10-17.md` |
| **N11** — tabel tier lintas-mode mencampur mode | batch 6i | `docs/LAPORAN-batch6i-n6n17n11-2026-10-17.md` |

> ✅ **N10 (= J10) kini tuntas penuh** — jalur beli paper manual yang masih membaca SL/TP mentah
> ditutup batch 6a (`docs/LAPORAN-batch6-j10-2026-10-17.md`).

## 5. Yang sudah benar (jangan diutak-atik)

- `hasTriggeringTransition` = `(isNewDetected || isNewReady) && action == BUY` → sinyal HOLD
  tidak pernah memberi notifikasi kandidat; invarian P1 (`EARLY_ENTRY` mensyaratkan setup)
  terproteksi di sini juga.
- Notifikasi kandidat **dilewati** bila coin sedang HOLDING
  (`TradingViewModelSync.kt`, `CandidateScanWorker.kt`).
- **B5 sudah benar:** confidence di signal-log = nilai saat firing, bukan MAX/SKARANG
  (`database/SignalLogRepository.kt`).
- Gerbang tunggal N4 sudah benar: `AlertNotificationHelper.canNotify(context, category)` dipasang di
  setiap titik kirim, dan `AppPreferences` di-cache. Sejak batch 6f **kategori** juga sudah benar:
  laporan eksekusi memakai `sendExecutionResultNotification` (kategori `TRAILING_STOP`), dan hanya
  pengingat harga milik pengguna yang memakai `sendUserPriceAlertNotification` (`PRICE_ALERT`).
  Foreground service juga sudah dilepas dari sakelar notifikasi (batch 6g, N15): keputusan
  start/stop hanya dari keberadaan posisi.
- Izin OS dicek di setiap helper.
- **Foreground service = keep-alive, bukan pengirim push** (batch 6g, N15). `startService` /
  `forceRefresh` tidak lagi membaca `isNotificationsEnabled`, dan `updateForegroundServiceState`
  hanya memutuskan dari `hasActive`. Service memakai **satu** `AppPreferences` per instance
  (N16), dan `updatePrices` menerima bursa dari pemanggil sehingga jalur per-tick tidak menyentuh
  preferensi sama sekali.
- Cooldown **emergency 60 dtk** (`TradingForegroundService.kt`) berfungsi.
- Cooldown **peak 30 dtk** berfungsi penuh (batch 6f, N14): satu-satunya pengirim adalah jalur
  ber-jendela `TrailingPeakThrottle`, dan `updateSimTrailingOrder`/`updateRealTrailingOrder` tidak
  lagi mengirim notifikasi sendiri.
- **Satu mode, satu mesin, satu angka** (batch 6i, N6 + N17): `engine/ModeSignalEvaluator.kt`
  adalah satu-satunya perakitan input evaluator. Engine dan `CandidateScanWorker` sama-sama
  memanggilnya; worker hanya menghitung `prefs.strategyMode`. Penalti anomali makro dihitung **di
  dalam** `ModeSignalEvaluator` dari candle D1, jadi tidak ada pemanggil yang bisa lupa meneruskannya. Worker juga
  mengambil orderbook nyata untuk scalping — `diagnosticIgnoreOrderBookWhenUnavailable` tidak
  dipakai di jalur produksi.
- **Pemindaian latar hanya menyentuh watchlist** (`prefs.getWatchlist()`), dengan batas tambahan
  `MAX_SCALP_BACKGROUND_SYMBOLS` untuk scalping. Jangan menambah pemindaian seluruh pasar.
- **Tabel tier lintas-mode tidak ditampilkan** (batch 6i, N11). Ambang ≥80/60–79/<60 mencampur mode
  sehingga scalping pra-entry (cap ≤59) selalu "Awal". Cap `ScalpingMtfEvaluator` tidak diubah.
- `util/TradeLogExporter.kt` melabeli jujur "Skor Konfidensi (skor engine, dibatasi tahap)".

## 6. Rekomendasi perbaikan

**Tidak ada** untuk cakupan dokumen ini. Seluruh N1–N17 tertutup. Area berikutnya yang dulu masih
punya temuan hidup — **paper trading** (`docs/laporan_temuan_bugs_paper_trade.md`) — juga sudah
tuntas (batch 6o, 2026-10-18): **tidak ada lagi temuan audit yang hidup di repo ini**.

## 7. Riwayat

| Tanggal | Aksi |
|---|---|
| 2026-10-16 | Audit awal jalur notifikasi & confidence dilakukan (read-only, terverifikasi ke kode) |
| 2026-10-16 | Temuan diekspor ke `docs/bugs/temuan_bugs_notifikasi.md` (path lama) |
| 2026-10-16 | Batch 1, 2, 5, 6b menutup N1–N5, N7, N9, N10 |
| 2026-10-17 | Batch 6f, 6g, 6h menutup N8, N12–N16 |
| 2026-10-17 | Batch 6i menutup N6, N17, N11 — **tidak ada temuan hidup tersisa** |
| 2026-10-17 | Berkas dipindahkan keluar `docs/bugs/` → `docs/laporan_temuan_bugs_notifikasi.md`. Mulai sekarang berkas inilah laporannya; laporan per batch tidak dibuat lagi |
