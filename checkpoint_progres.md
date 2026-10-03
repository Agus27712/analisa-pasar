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
| P0    | ✅ DONE (pending structure unit test opsional) | Models + IndicatorMath + Regime + Structure |
| P1    | ⬜ NEXT | Setup + Score + Risk + Refactor evaluator |
| P2    | ⬜ TODO | Historical edge stub, MTF penuh, short |
| Tests | 🔄 PARTIAL | Indicator + Regime unit tests ada; jalankan manual nanti |

---

## P0 — Fondasi (wajib selesai dulu)

### P0.1 Data models
- [x] Buat `app/src/main/java/agu/analys/model/ScalpingEngineModels.kt`
  - `MarketRegime`, `RegimeSnapshot`
  - `StructureBias`, `StructureSnapshot`
  - `ScalpSetupType`, `SignalDirection`
  - `ScoreBreakdown`, `EntryZone`, `RiskLevels`, `ScalpingSignal`
- [x] Model **exchange-agnostic** (IDR/USDT OK)

### P0.2 IndicatorMath extensions
- [x] Tambah `relativeVolume(candles, period = 20): Double`
- [x] Tambah `choppiness(candles, period = 14): Double`
- [x] Tambah `adx(candles, period = 14): Double` (TA4J ADXIndicator + fallback)
- [x] Unit test: `IndicatorMathScalpingExtTest.kt`

### P0.3 Market Regime Engine
- [x] `engine/regime/MarketRegimeEngine.kt` (API lama `MarketRegimeDetector` tidak dipecah)
- [x] Output: `RegimeSnapshot`
- [x] Logic: CHOP + ADX + ATR% + structure bias + EMA alignment + breakout volume
- [x] Unit test: `MarketRegimeEngineTest.kt`

### P0.4 Structure enhancement
- [x] Extend `MarketStructureAnalyzer`:
  - CHoCH detection (`hasChoCH`, `chochDirection`)
  - `structureStrength` 0–100
  - `toStructureSnapshot()` mapper
  - BOS flags di snapshot
- [ ] Unit test structure strength + CHoCH (opsional sebelum P1; boleh dikerjakan paralel)

**Gate P0:** ✅ Fondasi kode P0 selesai. Agent boleh mulai **P1.1 Setup Detector**.

---

## P1 — Scalping Engine Core  ← **KERJAKAN INI SEKARANG**

### P1.1 Setup Detector
- [ ] `engine/scalping/ScalpSetupDetector.kt`
  - Types: `BREAKOUT`, `BREAKOUT_RETEST`, `LIQUIDITY_SWEEP`, `TREND_PULLBACK`, `NONE`
  - Input: price, StructureSnapshot, RegimeSnapshot, rvol, buyPressure, candles
- [ ] Unit test minimal 1 case per setup type → `ScalpSetupDetectorTest.kt`

### P1.2 Signal Scoring Engine
- [ ] `engine/scalping/SignalScoringEngine.kt`
  - Bobot: Structure 25 | MTF 15 | Price Action 20 | Volume 15 | Momentum 10 | Order Flow 10 | Volatility 5
  - Output `ScoreBreakdown` + reasons (Bahasa Indonesia)
- [ ] Unit test: total = sum komponen; kategori NO_TRADE/WEAK/WATCH/STRONG/VERY_STRONG

### P1.3 Risk / Entry Zone
- [ ] Helper di package scalping (mis. `ScalpingRiskEngine.kt`): Entry Zone + Dynamic SL/TP + Net R:R via `FeeCalculator`
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

- [ ] Historical Edge **stub** (jangan angka palsu)
- [ ] MTF matrix lebih lengkap (H1/M15/M1 dulu)
- [ ] Order imbalance formal di `OrderBookAnalyzer` jika belum
- [ ] Short side penuh (opsional)
- [ ] `ScalpingConfig` object kumpulkan threshold

---

## Unit Test (tanpa CI workflow)

| Test file | Scope | Status |
|-----------|--------|--------|
| `indicators/IndicatorMathScalpingExtTest.kt` | RVOL, CHOP, ADX | ✅ added |
| `regime/MarketRegimeEngineTest.kt` | Regime synthetic + IDR scale | ✅ added |
| `scalping/ScalpSetupDetectorTest.kt` | Setup types | ⬜ P1 |
| `scalping/SignalScoringEngineTest.kt` | Score breakdown | ⬜ P1 |
| `scalping/ScalpingMtfEvaluatorTest.kt` | Update jika perlu | existing |
| `scalping/ScalpingMtfEvaluatorAuditTest.kt` | Update jika perlu | existing |

**Jangan** menambah `.github/workflows/*` di branch ini untuk test.

---

## Log Commit / Progress

| Tanggal (WIB) | Item | Commit / catatan |
|---------------|------|------------------|
| 2026-10-04 | Branch + checkpoint | `engine_scalping_update` dari main |
| 2026-10-04 | P0.1 | `ScalpingEngineModels.kt` |
| 2026-10-04 | P0.2 | `IndicatorMath` + RVOL/CHOP/ADX + test |
| 2026-10-04 | P0.3 | `MarketRegimeEngine` + test |
| 2026-10-04 | P0.4 | CHoCH + strength + `toStructureSnapshot` |

---

## Definisi Selesai (MVP branch ini)

- [x] Regime formal (TRENDING_UP/DOWN, RANGING, HIGH_VOL, …)
- [x] Structure strength + CHoCH
- [x] RVOL + ADX + CHOP di IndicatorMath
- [ ] ≥3 setup type terdeteksi
- [ ] Score 0–100 + reasons
- [ ] Direction LONG / WAIT / SHORT
- [ ] Entry zone + dynamic SL/TP + net R:R
- [ ] Backward compatible `AISignalState`
- [x] Unit test file ada (dijalankan manual nanti)
- [x] Tidak ada workflow CI test baru

---

**Agent berikutnya:** mulai **P1.1 ScalpSetupDetector** → jangan refactor `ScalpingMtfEvaluator` dulu sebelum Setup + Score + Risk siap. Baca spek setup di prompt update. Jangan kerjakan P2.
