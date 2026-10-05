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
| Real-data replay | ✅ dijalankan | **Belum ada bukti edge** (lihat § Replay) |
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
4. Wire scanner ke UI screener — di luar scope engine murni jika UI frozen.
5. Historical Edge nyata dari journal / per-setup — **setelah** data cukup + edge terindikasi.
6. PR → main hanya jika pemilik setuju; test hijau saja **bukan** bukti edge trading.

**Jangan:** CI otomatis push/PR, ML, angka edge palsu, hardcode IDR absolut, longgarkan kembali gate retest tanpa data pendukung.

---

## Catatan agent

- Replay run #7 = sebelum filter retest; run #8 = sesudah. Bandingkan **n setup + avg net + PF**, bukan hanya win rate.
- Hasil provisional tanpa orderbook: jangan overfit ke window 1–5 Okt 2026 saja.
- Update file ini setiap selesai patch/tuning bermakna.
