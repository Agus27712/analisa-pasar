# Checkpoint Progress — Engine Scalping Update

**Branch:** `engine_scalping_update`  
**Base:** `main` @ `e06b12eb`  
**Spek:** `KriptoYoi_Scalping_Engine_Update_Prompt.md` + `KriptoYoi_Scalping_Analyzer_Specification.md`  
**Exchange context:** Indodax + **Tokocrypto** (quote **IDR** dan **USDT**). Engine scalping harus exchange-agnostic (harga/volume relatif, bukan hardcode IDR).

---

## ATURAN WAJIB UNTUK SEMUA AGENT

1. **Ikuti urutan P0 → P1 → P2.** Jangan loncat.
2. **Jangan “ngide”** di luar scope checkpoint ini / spek KriptoYoi.
3. **Jangan ubah UI Compose / ViewModel** kecuali mapping backward-compatible ke `AISignalState`.
4. **Jangan buat GitHub Actions / CI workflow** untuk test di tahap ini. Unit test file saja; dijalankan manual nanti.
5. **Fee + slippage** tetap lewat `FeeCalculator` / `TradingFeeConfig` yang sudah ada.
6. **WAIT** adalah output sah. Jangan memaksa BUY/LONG.
7. Setiap selesai satu item: **update status di file ini** (`[ ]` → `[x]`) + commit message jelas.
8. Harga bisa IDR (juta–miliar) atau USDT (desimal). Semua threshold pakai **persen / ratio**, bukan absolute price.
9. Reuse file existing: `MarketStructureAnalyzer`, `OrderBookAnalyzer`, `IndicatorMath`, `ConfluenceEvaluator`, `ScalpingMtfEvaluator`.
10. Bahasa reasoning signal: **Bahasa Indonesia**.

---

## Status Ringkas

| Phase | Status | Catatan |
|-------|--------|---------|
| P0    | ✅ DONE | Models + IndicatorMath + Regime + Structure |
| P1    | ✅ DONE | P1.1–P1.4 selesai (SHORT → P2) |
| P2    | ⬜ TODO ← berikutnya | Historical edge stub, MTF penuh, short |
| Tests | 🔄 PARTIAL | Unit test files ada; jalankan manual nanti |

---

## P0 — Fondasi

### P0.1 Data models
- [x] `ScalpingEngineModels.kt`

### P0.2 IndicatorMath extensions
- [x] `relativeVolume` / `choppiness` / `adx` + `IndicatorMathScalpingExtTest.kt`

### P0.3 Market Regime Engine
- [x] `MarketRegimeEngine.kt` + `MarketRegimeEngineTest.kt`

### P0.4 Structure enhancement
- [x] CHoCH + `structureStrength` + `toStructureSnapshot()`
- [ ] Unit test structure (opsional)

**Gate P0:** ✅

---

## P1 — Scalping Engine Core

### P1.1 Setup Detector
- [x] `engine/scalping/ScalpSetupDetector.kt`
  - Types: `BREAKOUT`, `BREAKOUT_RETEST`, `LIQUIDITY_SWEEP`, `TREND_PULLBACK`, `NONE`
  - Prioritas: Sweep → Retest → Breakout → Pullback → None
  - Ranging keras (CHOP≥65) hanya izinkan sweep/retest
  - Exchange-agnostic (% / ratio)
- [x] Unit test: `ScalpSetupDetectorTest.kt` (1+ case per type + IDR scale + ranging NONE)

### P1.2 Signal Scoring Engine
- [x] `engine/scalping/SignalScoringEngine.kt`
  - Bobot: Structure 25 | MTF 15 | Price Action 20 | Volume 15 | Momentum 10 | Order Flow 10 | Volatility 5
  - Output `ScoreBreakdown` + reasons (Bahasa Indonesia)
- [x] Unit test: total = sum komponen; kategori NO_TRADE/WEAK/WATCH/STRONG/VERY_STRONG → `SignalScoringEngineTest.kt`

### P1.3 Risk / Entry Zone
- [x] Helper (`engine/scalping/ScalpingRiskEngine.kt`): Entry Zone + Dynamic SL/TP + Net R:R via `FeeCalculator`
- [x] Min Net R:R default **1.15**
- [x] Unit test entry zone low ≤ high; netRr calculation → `ScalpingRiskEngineTest.kt`

### P1.4 Refactor `ScalpingMtfEvaluator`
- [x] Orchestrator: Indicators → Structure → Regime → Setup → Score → Risk → Direction
- [x] Map ke `AISignalState` (BUY←LONG, HOLD←WAIT/SHORT sementara)
- [x] Pertahankan `SignalAudit` (step1–4 tetap; field baru berdefault: `score`, `scoreCategory`, `setup`, `direction`, `regime`)
- [x] Orderbook kosong / data kurang → tidak crash, arah WAIT
- [x] Update unit test existing bila kontrak berubah
  - Mapping checkpoint: step1 = tidak berbahaya + ruang naik · step2 = orderbook valid · step3 = setup valid + trigger VWAP · step4 = risk valid (Net R:R ≥ 1.15) + skor ≥ 60
  - BUY (LONG) hanya jika step1–4 lolos; skor ≥ 75 → `STRONG_ENTRY`, 60–74 → `ENTRY`
  - Alasan penolakan baru: `STEP3_NO_SETUP`, `STEP4_SCORE` (selain yang lama)
  - `Result.scalping: ScalpingSignal` membawa direction, setup, score breakdown, risk, mtfAlignment
  - Pesan Limit Maker memakai `$` untuk pair USDT, `Rp` untuk IDR
  - P1.3 disesuaikan: TP2 dihitung dari target Net R:R 1.25 setelah fee exchange (Tokocrypto vs Indodax), Net R:R diukur di TP2; plan ditolak jika TP2 > 5R atau resistance membatasi TP1 < 1R
  - Test diubah: `ScalpingMtfEvaluatorAuditTest` (data tren deterministik + 5 test P1.4), `HistoricalReplayComparisonTest` (dataset tangga naik 800 candle; bukan bukti edge)

**Gate P1:** Pipeline menghasilkan LONG/WAIT/SHORT + score explainable.

---

## P2 — Pelengkap (setelah P1 stabil)

- [ ] Historical Edge **stub**
- [ ] MTF matrix lebih lengkap
- [ ] Order imbalance formal
- [ ] Short side penuh (opsional)
- [ ] `ScalpingConfig` thresholds

---

## Unit Test (tanpa CI workflow)

| Test file | Scope | Status |
|-----------|--------|--------|
| `indicators/IndicatorMathScalpingExtTest.kt` | RVOL, CHOP, ADX | ✅ |
| `regime/MarketRegimeEngineTest.kt` | Regime synthetic | ✅ |
| `scalping/ScalpSetupDetectorTest.kt` | Setup types | ✅ |
| `scalping/SignalScoringEngineTest.kt` | Score breakdown | ✅ |
| `scalping/ScalpingRiskEngineTest.kt` | Entry zone, SL/TP, net R:R | ✅ |
| `scalping/ScalpingMtfEvaluatorTest.kt` | existing | existing |
| `scalping/ScalpingMtfEvaluatorAuditTest.kt` | existing | existing |

**Jangan** menambah `.github/workflows/*` di branch ini untuk test.

---

## Log Commit / Progress

| Tanggal (WIB) | Item | Commit / catatan |
|---------------|------|------------------|
| 2026-10-04 | Branch + checkpoint | `engine_scalping_update` dari main |
| 2026-10-04 | P0.1–P0.4 | Models, indicators, regime, structure |
| 2026-10-04 | P1.1 | `ScalpSetupDetector` + test |
| 2026-10-04 | P1.2 | `SignalScoringEngine` + test |
| 2026-10-04 | P1.3 | `ScalpingRiskEngine` + test |
| 2026-10-04 | P1.4 | Refactor `ScalpingMtfEvaluator` jadi pipeline + test |

---

## Definisi Selesai (MVP branch ini)

- [x] Regime formal
- [x] Structure strength + CHoCH
- [x] RVOL + ADX + CHOP
- [x] ≥3 setup type terdeteksi (detector + tests)
- [x] Score 0–100 + reasons
- [x] Direction LONG / WAIT (SHORT → P2)
- [x] Entry zone + dynamic SL/TP + net R:R
- [x] Backward compatible `AISignalState`
- [x] Unit test file ada (manual run nanti)
- [x] Tidak ada workflow CI test baru

---

**Catatan sebelum P2 (belum diselesaikan):**
- Evaluator baru jauh lebih selektif dari yang lama (di replay sintetis, lama BUY di ±90% frame). Belum ada bukti edge: replay sintetis tidak bisa dipakai untuk menilai profit. Validasi dengan data historis nyata Tokocrypto/Indodax.
- Pada dump cepat sintetis, sinyal BUY masih bisa muncul di candle awal dump karena indikator M15 terlambat (falling knife). Pertimbangkan guard di P2 setelah dicek dengan data nyata.
- Test dijalankan lewat shim JUnit di luar Gradle (50+ test lulus); jalankan juga di Android Studio.

**Agent berikutnya:** kerjakan **P2** (urut: `ScalpingConfig` thresholds → Historical Edge stub → order imbalance formal → MTF matrix → short opsional).
