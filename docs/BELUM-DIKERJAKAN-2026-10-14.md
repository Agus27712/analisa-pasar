# Daftar Lengkap Pekerjaan yang BELUM Dikerjakan

**Tanggal:** 14 Oktober 2026
**Repo:** branch `patch` @ `0ca38eb` (S1–S9 sudah masuk)
**Peringatan branch:** `main` **tertinggal 11 commit** dari `patch`. Baca dari `patch`, bukan `main`.
**Sumber:** `LAPORAN-validitas-log-audit`, `LAPORAN-verifikasi-ui-vs-candle`, `LAPORAN-audit-amp`, `LAPORAN-replay-gns`, `PANDUAN-LANJUT-AGEN.md` §4–§5
**Catatan:** semua item di bawah **sudah diverifikasi ulang ke kode** — bukan dari ingatan. Yang sudah selesai di P0–P2 tidak dimasukkan.

**Total: 27 item** — 9 saran strategi (SELESAI 2026-10-14) · 13 temuan audit · 5 roadmap · 4 verifikasi lapangan (beberapa tumpang tindih, lihat catatan di tiap bagian).

---

## Bagian 1 — Saran perbaikan strategi

> ✅ **SELESAI 2026-10-14** — S1–S9 dieksekusi, diuji (358 test lulus), dan didokumentasikan di
> `docs/LAPORAN-eksekusi-s1s9-2026-10-14.md`.
>
> Dua catatan penting:
> - **S9 (gerbang extended-move) dikirim dalam keadaan NONAKTIF** (`max_change_24h_pct = 0`).
>   Ambang 25% dari kasus AMP terbukti menghapus seluruh trade GNS — padahal tanpa gerbang
>   trade itu 4/4 menang (avg R +0,178, tanpa drawdown). Kalibrasi menunggu P4.1.
> - **S2 mengubah perilaku default**: lantai profit 2× friksi (1,94% kotor) kini aktif, jadi
>   early-profit / BE / trailing di bawah itu tidak lagi terjadi. Setel slider ke 0 untuk
>   mengembalikan perilaku lama.

Tabel di bawah dipertahankan sebagai arsip kebutuhan (status ditambahkan di kolom pertama).

| # | Item | Titik kode | Prioritas | Bukti/alasan |
|---|---|---|---|---|
| S1 ✅ | **RVOL wajib ≥ 1,2× untuk setup BREAKOUT** — hapus loophole "2 dari 3 konfirmasi" | `ScalpingMtfEvaluator.kt` (gate breakout) | **Tinggi** | AMP lolos BREAKOUT dengan RVOL 0,07× (hitung ulang 0,20×) = partisipasi hampir nol |
| S2 ✅ | **Lantai tutup ≥ ~2% gross** (≈ 2× friksi 0,97%) | `ScalpExitParams` / early-profit | **Tinggi** | AMP tutup di +1,50% gross → **54% dimakan fee**, net cuma +0,69% |
| S3 ✅ | **SL keras wajib** — −2× ATR (≈ −4%) atau −2× friksi | `ScalpExitParams` (mode manual) | **Tinggi** | AMP menahan −2,76% selama 9 menit **tanpa SL apa pun** |
| S4 ✅ | **Pakai konstanta `ATR_PCT_*`** yang kini dideklarasikan tapi **tak pernah dipakai** | `ScalpingConfig.kt:94-97` | Sedang | `ATR_PCT_HIGH/LOW/SCALP_SWEET_*` = kode mati |
| S5 ✅ | **Seragamkan PnL laporan** (laporan fee-only vs engine fee+slippage) | generator laporan / `TradeLogExporter` | Sedang | Beda 0,16 pp per trade (AMP: +0,6889% vs +0,5289%) |
| S6 ✅ | **Cek field volume 24 jam** di generator laporan (selisih ~3 orde magnitudo) | `TradeLogExporter.kt` (= A1) | Sedang | Laporan 8,32M AMP vs candle 12,2 miliar AMP |
| S7 ✅ | **Arm `PaperExitStateMachine` untuk beli MANUAL** | `TradingViewModelSync.kt` (jalur beli manual) | Sedang | Mesin exit hanya dibuat di `PaperAutoTradeCoordinator.kt:262` + `PaperRehydrate.kt:98` — beli manual **tidak punya exit engine** |
| S8 ✅ | **BE trigger & trail proporsional ATR** — BE ≥ 1,5% (bukan 0,35%), trail 0,5–1,0× ATR% (bukan 0,15%) | `ScalpExitParams` | Sedang | Replay GNS: BE 0,35% → keluar di wick, +0,86%; BE 1,5% → +7,09% (**8×**) |
| S9 ✅ | **Extended-move gate** (jangan masuk koin yang sudah naik ekstrem) | gate entry | Sedang | AMP sudah +28,09% dalam 24 jam = dekat puncak harian |

---

## Bagian 2 — Temuan audit (A/B) yang belum ditangani

Di luar cakupan P0–P2. **Semua diverifikasi masih ada di kode saat ini.**

### 2a. Kesalahan info (A)

| # | Item | Bukti di kode | Dampak |
|---|---|---|---|
| A1 | `volume24h` dilabeli `$baseAsset`, isinya **quote (USDT)** | `TradeLogExporter.kt:138` masih `... $baseAsset` | Likuiditas salah **~1.467×** |
| A2 | `signalPrice` tanpa basis bar | sebagian tertutup P0-2 (basis bar indikator disimpan) | **Sebagian** — harga benar, basis baru sebagian |
| A6 | `bbMiddle = ema20` saat Bollinger null | `TradeSignalSnapshot.kt:241` masih `else ema20` | "Bollinger Middle" **palsu** (itu EMA20) |
| A8 | Header bursa **ditebak** dari quote asset | `TradeLogExporter.kt:101` masih `isUsdtQuote → "TOKOCRYPTO"` | Pair USDT di Indodax dicap TOKOCRYPTO |
| A9 | Export markdown **membuang Kategori 6** (setup/skor/regime scalping) | `TradeLogExporter.kt:131-168` hanya kategori A–E | Markdown ≠ JSON; field paling relevan mode SCALPING hilang |
| A10 | "Skor Konfidensi" ambigu (skor engine vs *cap*) | `ScalpingMtfEvaluator.kt` confidence = cap bila tak `ready` | Pembaca tak bisa bedakan skor 63 dari cap 59 |

### 2b. Bias & fabrikasi (B)

| # | Item | Bukti di kode | Dampak |
|---|---|---|---|
| B1 | **Record fallback mengarang trade** (`estBuyPrice = sellPrice × 0.98`, `confidence = 80`, hold 15 mnt tetap) | `TradeHistoryRecorder.kt:312, 326, 338` | Trade palsu terlihat sah + masuk statistik |
| B3 | Sinyal **expired diberi kredit PEAK**, bukan harga jual | `SignalLogRepository.kt:389-390` | Bias **optimistis sistematis** pada "akurasi sinyal" |
| B4 | `resolveLogManually` **mengarang besaran** PnL (`max(1.5, …)` / `min(-1.5, …)`) | `SignalLogRepository.kt:355` | Anda tandai menang/kalah, besarannya fiktif |
| B5 | Confidence signal-log = **nilai MAX sepanjang pelacakan** | `SignalLogRepository.kt:107` | "confidence 88" = skor tertinggi berjam-jam, bukan saat entry |
| B6 | **Gross vs net dicampur** di satu kartu (peak/drawdown gross, `pnlPercent` net) | entity + UI ringkasan | Pembaca mengira ada selisih tak dijelaskan |
| B7 | Peak/trough = **sampel polling** (throttle 1,5 s), bukan ekstrem sejati | `processPriceTick` | Wick antar-sampel hilang → peak understated (**sebagian** tertutup P0-6 lewat label `Peak (candle)`) |

> Catatan: **A3, A4, A5, A7, B2 sudah selesai di P0–P2.** A2 & B7 baru **sebagian**.

---

## Bagian 3 — Roadmap (PANDUAN-LANJUT-AGEN §4–§5)

| # | Item | Alasan | Status |
|---|---|---|---|
| P4.1 | **Walk-forward antar-bulan** — perluas `tools/split_validate.py` jadi beberapa jendela bergulir | Validasi keluar-sampel sekarang hanya **1 potongan** (70/30) — bisa kebetulan | **Prioritas tertinggi** |
| P4.4 | **Rehidrasi FSM paper: pulihkan progres** — persistensikan `barsHeld`, `isBeArmed`, `isPartialDone`, `peak` | Saat restart keempat nilai **reset**; mesin pulih ≠ mesin yang hilang | Belum |
| P4.2 | **Preferensi maker untuk pair IDR** (taker 0,8144% → maker 0,6144%) | Semua perhitungan masih taker | Belum |
| P5 | **Data Indodax + replay TAPI v2** — harness ambil data + replay, jalankan pipeline §9 pada data Indodax | Folder `data/` belum punya data Indodax; `IndodaxTradeApiV2.kt` belum diuji ujung-ke-ujung | Belum |
| P6 | **Auto-execute uang riil** | **DIBLOKIR** sampai P4.1 & P5 selesai — jangan disentuh tanpa instruksi eksplisit | Diblokir |

> Aturan yang **jangan dilonggarkan**: `MIN_FRICTION_MULTIPLE = 6.0`, `PairEdgeGate` rasio ≥ 2,0.

---

## Bagian 4 — Verifikasi lapangan & pertanyaan terbuka

| # | Item | Kenapa penting |
|---|---|---|
| V1 | **Pasang APK `app-debug-p0p2.apk`, cek tab Siklus Trade** — `Pemicu Jual` bukan lagi `SIMULATION_SELL`; `Trailing Stop Lock` mengikuti toggle nyata | Konfirmasi P0-4 & P0-5 benar di perangkat, bukan hanya di test |
| V2 | **Cek export audit** — baris `RVOL` & `Basis Bar Indikator` muncul di kategori B | Konfirmasi P0-2 |
| V3 | **Replay lebih banyak trade** untuk membuktikan RVOL understatement **sistematis**, bukan cuma 2 kasus | Menentukan apakah S1 perlu diubah atau cukup perbaikan basis volume |
| V4 | **AMP: replay tidak menghasilkan setup di 00:51** (setup terakhir engine 23:51) — apakah `BUY READY` itu status lifecycle yang bertahan, bukan deteksi baru? | Bisa berarti sinyal di app **stale** — perlu verifikasi sebelum jadi kesimpulan |

---

## Bagian 5 — Status (bukan tugas, tapi jangan lupa)

- **Kesiapan uang riil masih BELUM.** avg R keluar-sampel +0,0216; profil exit 88,7% `TRAIL_SL` (+0,09R) + 4,9% hard SL (−1,16R); margin di atas impas hanya **≈2,3 poin persentase**; gate ketat lolos **5 dari 40 pair**. Tandanya benar, besarnya belum cukup.
- **Tiga jurnal paralel** (`trade_history_records` · `signal_logs` · `SimulationTradeHistoryItem`) belum disatukan — sumber kebingungan audit berulang.
- **Fitur horizon (§14/§15) tetap DIBATALKAN** — jangan dibangun ulang.

---

## Ringkasan eksekutif — kalau hanya boleh pilih 5

| Urut | Item | Alasan |
|---|---|---|
| 1 | **S3 — SL keras wajib** | Satu-satunya asimetri nyata: "cut rugi cepat" belum ada |
| 2 | **S1 — RVOL wajib untuk BREAKOUT** | Mencegah breakout di volume nol |
| 3 | **A1 — label volume 24 jam** | Salah 1.467× — sudah menyesatkan 2 audit |
| 4 | **B3 — expiry pakai harga terakhir, bukan peak** | Bias optimistis sistematis pada win rate sinyal |
| 5 | **P4.1 — walk-forward antar-bulan** | Gerbang menuju uang riil |
