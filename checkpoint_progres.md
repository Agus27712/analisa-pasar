# Checkpoint Progress — Engine Scalping Update

**Branch:** `engine_scalping_update`  
**Tip (saat update ini):** `87ed3abd` + commit checkpoint ini  
**Exchange:** Indodax + Tokocrypto (IDR & USDT) — threshold %/ratio only.

**Sumber kebenaran untuk agent:** file ini. Jangan loncat spek / ngide fitur baru di luar daftar residual.

---

## ATURAN WAJIB

1. Ikuti urutan phase. Jangan loncat / ngide di luar spek KriptoYoi.
2. Jangan ubah UI kecuali mapping `AISignalState` backward-compatible.
3. CI: hanya `.github/workflows/unit-tests-manual.yml` (`workflow_dispatch`). Jangan tambah trigger push/PR otomatis tanpa permintaan pemilik.
4. Fee+slippage lewat `FeeCalculator` / `TradingFeeConfig`.
5. WAIT sah. Jangan angka Historical Edge palsu.
6. Bahasa reasoning: Indonesia.
7. Threshold exchange-agnostic (% / ratio) — jangan hardcode harga IDR absolut.

---

## Status Ringkas

| Phase | Status | Catatan |
|-------|--------|---------|
| P0–P1 | ✅ | Models → Setup / Score / Risk / pipeline |
| P2 | ✅ | Config, edge stub, imbalance, MTF matrix |
| Wiring P2→Evaluator | ✅ | Config, OrderFlow, MTF, EdgeStub, falling-knife |
| P3 | ✅ | Backtest adapter + fee/slippage |
| P4 | ✅ | Market scanner engine (no UI) |
| P5 | ✅ DEFERRED | ML **tidak** dikerjakan sampai edge terbukti |
| Unit tests | ✅ | Hijau via Actions manual; 1 skip live Indodax |
| Real-data replay | ✅ dijalankan (8 hari data total, 14 hari terpanjang) | **Belum ada bukti edge** (lihat § Replay, run #12–#13) |
| Filter retest | ✅ | Gate skor 75 + RVOL 1.5 + no hard-range |

---

## Replay data nyata Tokocrypto (wajib dibaca agent)

**Cara jalan:** Actions → Unit Tests (Manual) → scope `real-tokocrypto-replay`, **`data_dir=data/tokocrypto`** (bukan `data_dir=data/tokocrypto` literal error / path salah).  
CSV offline dari jaringan Indonesia (`tools/fetch_tokocrypto_klines.py`). Runner AS kena HTTP 451 dari Tokocrypto.

Metodologi: kausal, entry open bar berikutnya, fee 0.1%+0.1% + slip 0.08%, time stop 30 bar M1, Step 2 bypass (tanpa orderbook historis) → **PROVISIONAL**.

### Run #7 — baseline sebelum ketatkan retest

| Metrik | Nilai |
|--------|-------|
| Trade | 600 |
| Win% / avg net / PF | 10.2% / −0.35% / 0.09 |
| Setup mix | **BREAKOUT_RETEST 482 (80%)**, pullback 58, sweep 54, breakout 6 |
| Kesimpulan | Tidak lebih baik dari baseline entry berkala (−0.35%) |

Report analyzer sudah punya **tabel per setup** (`RealDataReplayAnalyzer.buildReport`).

### Run #8 — sesudah filter BREAKOUT_RETEST (`87ed3abd`)

| Metrik | Nilai |
|--------|-------|
| Trade | **412** (−31%) |
| Win% / avg net / PF | 11.4% / **−0.36%** / 0.10 |
| Setup mix | **TREND_PULLBACK 257 (62%)**, retest 65 (16%), sweep 61, breakout 29 |
| Retest avg skor | **76.9** (gate 75 jalan) |
| Kesimpulan | **Masih belum ada bukti edge** vs baseline |

**Efek filter:** volume retest −86% (482→65) — seleksi berhasil.  
**Belum berhasil:** expectancy aggregate tidak naik; pullback mengambil alih dengan skor tipis (~63) dan avg net serupa (−0.35%). TIME_STOP tetap ~80%+.


### Run #9–#11 — sensitivitas time stop & slippage (commit workflow `94f2266`, data sama 1–5 Okt 2026)

Input workflow baru: `lookahead`, `slippage_pct`, `label` (laporan di branch `real-data-reports-<label>`). Slippage hanya mengubah **biaya simulasi**; geometri SL/TP engine tetap memakai slippage 0.08%.

| Run | Time stop | Slippage/sisi | Trade | Win% | Avg net% (CI95) | PF | Baseline avg net% |
|-----|-----------|---------------|------:|-----:|-----------------|---:|------------------:|
| #8 (acuan) | 30 | 0.08 | 412 | 11.4 | −0.357 (±0.032) | 0.10 | −0.347 |
| #9 A | **120** | 0.08 | 234 | 21.4 | −0.340 (±0.065) | 0.22 | −0.297 |
| #10 B | 30 | **0.02** | 412 | 15.0 | −0.237 (±0.032) | 0.19 | −0.228 |
| #11 C | **120** | **0.02** | 234 | 25.2 | −0.221 (±0.065) | 0.36 | −0.177 |

Temuan: semua varian **masih negatif** dan **tidak lebih baik dari baseline** (engine justru sedikit di bawah baseline di A dan C). Biaya eksekusi menggeser seluruh hasil (≈ +0.12% dari slippage), tetapi selisih engine vs baseline tidak membaik. Time stop 120 menaikkan win rate tapi jumlah trade turun (posisi menahan lebih lama → sinyal tumpang tindih diblok). Skor tidak membedakan hasil antar setup. **Belum ada bukti edge; jangan ML; jangan klaim.**

Implikasi: urutan tuning "ketatkan pullback dulu" tidak didukung data; gate skor/setup tidak mengangkat expectancy di sample ini. Perpanjang data (rezim berbeda) sebelum ubah engine lebih lanjut.

### Run #12–#13 — data 14 hari (`data/tokocrypto_v2`, 21 Sep → 5 Okt 2026, 8 pair USDT, fee 0.1%/0.1%, slippage 0.08%)

Data v2 pertama (5 halaman) ternyata ±97% tumpang tindih dengan data awal, lalu diganti dengan 20 halaman (M1 19.999 candle/pair). Replay jauh lebih besar dan lebih independen dari run #8–#11.

| Run | Time stop | Trade | Win% | Avg net% (CI95) | PF | Baseline avg net% (CI95) |
|-----|-----------|------:|-----:|-----------------|---:|--------------------------|
| #12 v2-base | 30 | 1634 | 16.6 | −0.366 (±0.020) | 0.13 | −0.357 (±0.004) |
| #13 v2-t120 | 120 | 1008 | 25.1 | −0.353 (±0.035) | 0.26 | −0.349 (±0.006) |

Per setup (time stop 120): LIQUIDITY_SWEEP −0.297 (n=284), TREND_PULLBACK −0.338 (n=522), BREAKOUT_RETEST **−0.455** (n=125, skor rata-rata 76.8), BREAKOUT −0.503 (n=77). Time stop 30: semua setup −0.34 s/d −0.40.

Temuan: hasil **konsisten dengan run #8–#11** pada rentang 4× lebih panjang. Engine ≈ baseline entry berkala (selisih < 0.01% vs CI ±0.02%), rata-rata net negatif secara statistik jelas, mendekati biaya round-trip ~0.36%. Skor tinggi (retest, ~77) **tidak** lebih baik; di time stop 120 cenderung lebih buruk. Win% per pair sangat beda (BTC 3.4%, ADA 29%, TRX 0%) = efek volatilitas pair terhadap SL/TP tetap ±0.5%, bukan keunggulan sinyal.

**Kesimpulan: belum ada bukti edge pada scalping M1 + geometri SL~0.5%/TP~0.8–1.45% dengan fee Tokocrypto.** Jangan klaim, jangan ML, jangan tuning gate setup lebih lanjut. Arah yang belum diuji (butuh persetujuan pemilik): target/stop lebih lebar relatif terhadap biaya (timeframe lebih tinggi), order maker, atau memakai engine hanya sebagai filter WAIT.

### Implikasi tuning (urutan wajib)

1. **Jangan** klaim edge / jangan ML.
2. Masalah dominan sekarang: **TREND_PULLBACK** over-trade + skor dekat gate 60.
3. Geometri SL~0.49% / TP1~0.75–0.80% + fee round-trip ~0.36% + time stop 30 = struktural sulit di sample ini — sentuh risk **setelah** gate setup, atau ukur impact terpisah.
4. Retest: biarkan gate 75; jangan longgarkan.
5. Sample ~3.5 hari, satu rezim — perpanjang data sebelum tuning agresif.

---

## Patch filter retest (sudah di branch)

| File | Perubahan |
|------|-----------|
| `ScalpingConfig` | `MIN_SCORE_BREAKOUT_RETEST=75`, `RVOL_RETEST_MIN=1.5`, `RETEST_MIN_STRUCTURE_STRENGTH=60`, `minScoreForSetup()` |
| `ScalpSetupDetector` | Retest diblok hard ranging; RVOL wajib; BOS+strength; bias bearish ditolak |
| `ScalpingMtfEvaluator` | Step4 skor ≥ `minScoreForSetup(setup)` |
| `ScalpSetupDetectorTest` | Case RVOL rendah / ranging keras / assert skor 75 |
| `RealDataReplayAnalyzer` | Section **Per setup** (win%, net, PF, exit mix, geometri) |

---

## Status wiring ke aplikasi

**Tersambung (jalur sinyal live, mode SCALPING saja):**
`LearningTradingEngine.runScalping()` → `ScalpingMtfEvaluator` (pipeline) → `AISignalState` → `SignalLifecycleManager` → UI. Fee mengikuti `tradingFees` exchange aktif. Mode swing & intraday memakai evaluator sendiri.

**Disambungkan pada commit "sambungkan output pipeline" (diverifikasi compile + 160+ unit test di GitHub Actions):**
- `AISignalState` membawa `scalpingSetup`, `scalpingScore`, `scalpingScoreCategory`, `scalpingScoreDetail`, `scalpingRegime`, `scalpingDirection`, `entryZoneLow/High`. `entryPrice` tetap harga saat sinyal (zona entry terpisah, agar alur order lama tidak berubah).
- **Log sinyal** (`signal_logs`): kolom baru `scalpingSetup`, `scalpingScore`, `scalpingScoreCategory`, `scalpingRegime`, dicatat saat sinyal pertama terpicu (tidak ditimpa saat update). **Migrasi Room 7→8 nyata** (ALTER TABLE) — data lama tidak terhapus. SQL migrasi sudah dicek pada skema sqlite (kolom identik, baris lama utuh); belum diuji di perangkat → **backup/cek sebelum update di HP**.
- **Trade journal**: `TradeSignalSnapshot` (JSON di `signalSnapshotJson`) mencatat setup, skor, rincian skor, regime engine, zona entry. Tanpa perubahan skema DB.
- **UI**: kartu sinyal (zona entry, label TP/SL dinamis, baris "Setup Scalping" & "Rincian Skor"), dialog detail jurnal (kategori E), kartu log sinyal (baris setup + skor).

**Belum tersambung:**
- `MarketScannerEngine` — belum dipanggil siapa pun. Dashboard hanya punya sinyal untuk pair yang sedang dipilih (`getEngineSignal`), jadi scanner butuh fetch candle M1/M15/H1 per pair (≥3 request/pair) + UI hasil. Belum dibangun karena beban API/rate limit belum bisa diuji; usulan: tombol "Scan Scalping" manual (bukan polling otomatis) dengan jeda antar request.
- Statistik akurasi per setup/skor (agregat di layar log) — data sudah tercatat, tampilan agregat belum ada.
- Sinyal lama (sebelum update) tidak punya setup/skor (kolom kosong).

> Catatan: pemilik repo menguji dengan data live (simulasi & trade nyata) lewat aplikasi di branch `main`.

---

## Wiring residual (selesai)

`ScalpingMtfEvaluator` memakai:

- [x] `ScalpingConfig` (termasuk gate skor per setup)
- [x] `OrderBookAnalyzer.analyzeOrderFlow`
- [x] `MtfConfluenceMatrix.buildPartial`
- [x] `HistoricalEdgeStub` (tanpa angka palsu)
- [x] Falling-knife → `STEP1_FALLING_KNIFE`
- [x] `regimeDetected` = regime name

SHORT masih HOLD (opsional, jangan kerjakan kecuali diminta).

---

## P3 / P4 / P5 (ringkas)

- **P3** ✅ `ScalpingBacktestAdapter` — bukan Historical Edge setup-spesifik.
- **P4** ✅ `MarketScannerEngine` — no UI.
- **P5** ✅ DEFERRED — ML hanya setelah edge terbukti + data OOS.

---

## Unit test & workflow

| Item | Status |
|------|--------|
| Unit tests manual Actions | ✅ hijau |
| `RealBtcIndodaxReplayTest` | skip (live API) |
| `real-tokocrypto-replay` | ✅ jalan offline `data/tokocrypto` |
| Path input | **`data/tokocrypto`** — jangan salah format |

File test utama: IndicatorMath*, MarketRegime*, ScalpSetupDetector*, SignalScoring*, ScalpingRisk*, ScalpingConfig*, Mtf*, BacktestAdapter*, MarketScanner*, ScalpingMtfEvaluator*.

---

## Residual / next (opsional — ikuti urutan)

1. **(Disarankan next)** Ketatkan **TREND_PULLBACK** mirror retest: skor min 70 dan/atau RVOL/MTF lebih ketat — lalu **rerun** `real-tokocrypto-replay` bandingkan tabel per setup.
2. Evaluasi geometri risk / time stop **setelah** gate setup (jangan dulu ubah risk tanpa baseline replay baru).
3. Short side penuh — opsional, jangan prioritas.
4. Wire scanner ke UI screener — di luar scope engine murni jika UI frozen. (Lihat § Status wiring ke aplikasi.)
4b. ✅ Catat setup + skor + regime ke log sinyal / trade journal (selesai; lihat § Status wiring).
5. Historical Edge nyata dari journal / per-setup — **setelah** data cukup + edge terindikasi.
6. PR → main hanya jika pemilik setuju; test hijau saja **bukan** bukti edge trading.

**Jangan:** CI otomatis push/PR, ML, angka edge palsu, hardcode IDR absolut, longgarkan kembali gate retest tanpa data pendukung.

---

## Catatan agent

- Replay run #7 = sebelum filter retest; run #8 = sesudah. Bandingkan **n setup + avg net + PF**, bukan hanya win rate.
- Hasil provisional tanpa orderbook: jangan overfit ke window 1–5 Okt 2026 saja.
- Update file ini setiap selesai patch/tuning bermakna.
