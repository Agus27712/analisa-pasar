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
| P1    | 🔄 IN PROGRESS | P1.1–P1.2 done → lanjut P1.3 Risk/Entry Zone |
| P2    | ⬜ TODO | Historical edge stub, MTF penuh, short |
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

### P1.3 Risk / Entry Zone  ← **KERJAKAN INI SEKARANG**
- [ ] Helper (mis. `ScalpingRiskEngine.kt`): Entry Zone + Dynamic SL/TP + Net R:R via `FeeCalculator`
- [ ] Min Net R:R default **1.15**
- [ ] Unit test entry zone low ≤ high; netRr calculation

### P1.4 Refactor `ScalpingMtfEvaluator`
- [ ] Orchestrator: Indicators → Structure → Regime → Setup → Score → Risk → Direction
- [ ] Map ke `AISignalState` (BUY←LONG, HOLD←WAIT/SHORT sementara)
- [ ] Pertahankan `SignalAudit`
- [ ] Orderbook kosong / data kurang → tidak crash, arah WAIT
- [ ] Update unit test existing bila kontrak berubah

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

---

## Definisi Selesai (MVP branch ini)

- [x] Regime formal
- [x] Structure strength + CHoCH
- [x] RVOL + ADX + CHOP
- [x] ≥3 setup type terdeteksi (detector + tests)
- [x] Score 0–100 + reasons
- [ ] Direction LONG / WAIT / SHORT
- [ ] Entry zone + dynamic SL/TP + net R:R
- [ ] Backward compatible `AISignalState`
- [x] Unit test file ada (manual run nanti)
- [x] Tidak ada workflow CI test baru

---

**Agent berikutnya:** kerjakan **P1.3 ScalpingRiskEngine** saja. Jangan loncat ke P1.4 refactor evaluator sebelum Risk (P1.3) selesai.
