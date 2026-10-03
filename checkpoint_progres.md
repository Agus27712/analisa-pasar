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
| P0    | 🔄 IN PROGRESS | Models + IndicatorMath + Regime |
| P1    | ⬜ TODO | Setup + Score + Refactor evaluator |
| P2    | ⬜ TODO | Historical edge stub, MTF penuh, short |
| Tests | 🔄 IN PROGRESS | Unit test per engine, no CI workflow |

---

## P0 — Fondasi (wajib selesai dulu)

### P0.1 Data models
- [x] Buat `app/src/main/java/agu/analys/model/ScalpingEngineModels.kt`
  - `MarketRegime`, `RegimeSnapshot`
  - `StructureBias`, `StructureSnapshot` (engine-level; mapping dari MarketStructureAnalyzer)
  - `ScalpSetupType`, `SignalDirection`
  - `ScoreBreakdown`, `EntryZone`, `RiskLevels`, `ScalpingSignal`
- [x] Model **exchange-agnostic** (IDR/USDT OK)

### P0.2 IndicatorMath extensions
- [ ] Tambah `relativeVolume(candles, period = 20): Double`
- [ ] Tambah `choppiness(candles, period = 14): Double`
- [ ] Tambah `adx(candles, period = 14): Double` (TA4J jika ada / fallback manual)
- [ ] Unit test: `IndicatorMathScalpingExtTest.kt`

### P0.3 Market Regime Engine
- [ ] Buat / ganti implementasi formal di `engine/regime/`:
  - Prefer file baru `MarketRegimeEngine.kt` (jangan pecah API lama `MarketRegimeDetector` secara breaking)
  - Output: `RegimeSnapshot`
  - Logic sesuai spek: CHOP + ADX + ATR% + structure bias + EMA alignment + breakout volume
- [ ] Unit test: `MarketRegimeEngineTest.kt` (synthetic trending / ranging / high-vol)

### P0.4 Structure enhancement
- [ ] Extend `MarketStructureAnalyzer`:
  - CHoCH detection
  - `structureStrength` 0–100
  - Mapping helper → `StructureSnapshot`
- [ ] Unit test structure strength + CHoCH (minimal 1–2 case)

**Gate P0:** Semua checkbox P0 dicentang + unit test terkait compile. Baru boleh P1.

---

## P1 — Scalping Engine Core

### P1.1 Setup Detector
- [ ] `engine/scalping/ScalpSetupDetector.kt`
  - Types: `BREAKOUT`, `BREAKOUT_RETEST`, `LIQUIDITY_SWEEP`, `TREND_PULLBACK`, `NONE`
- [ ] Unit test minimal 1 case per setup type

### P1.2 Signal Scoring Engine
- [ ] `engine/scalping/SignalScoringEngine.kt`
  - Bobot: Structure 25 | MTF 15 | Price Action 20 | Volume 15 | Momentum 10 | Order Flow 10 | Volatility 5
  - Output `ScoreBreakdown` + reasons (ID)
- [ ] Unit test: total = sum komponen; kategori NO_TRADE/WEAK/WATCH/STRONG/VERY_STRONG

### P1.3 Risk / Entry Zone
- [ ] Helper di package scalping: Entry Zone + Dynamic SL/TP + Net R:R via `FeeCalculator`
- [ ] Min Net R:R default **1.15** (configurable)
- [ ] Unit test entry zone low < high; netRr calculation

### P1.4 Refactor `ScalpingMtfEvaluator`
- [ ] Orchestrator pipeline: Indicators → Structure → Regime → Setup → Score → Risk → Direction
- [ ] Map ke `AISignalState` (BUY←LONG, HOLD←WAIT/SHORT sementara)
- [ ] Pertahankan `SignalAudit`
- [ ] Orderbook kosong / data kurang → tidak crash, arah WAIT
- [ ] Update / extend unit test existing `ScalpingMtfEvaluatorTest` + audit test

**Gate P1:** Pipeline menghasilkan LONG/WAIT/SHORT + score explainable. Existing test scalping tidak merah parah (adjust jika kontrak berubah dengan sengaja).

---

## P2 — Pelengkap (setelah P1 stabil)

- [ ] Historical Edge **stub** (jangan angka palsu)
- [ ] MTF matrix lebih lengkap (H1/M15/M1 dulu; 1D/4H/5M opsional)
- [ ] Order imbalance formal di `OrderBookAnalyzer` jika belum
- [ ] Short side penuh (opsional; app masih fokus long)
- [ ] `ScalpingConfig` object kumpulkan threshold

---

## Unit Test (tanpa CI workflow)

Lokasi: `app/src/test/java/agu/analys/engine/...`

| Test file | Scope | Status |
|-----------|--------|--------|
| `indicators/IndicatorMathScalpingExtTest.kt` | RVOL, CHOP, ADX | ⬜ |
| `regime/MarketRegimeEngineTest.kt` | Regime synthetic | ⬜ |
| `scalping/ScalpSetupDetectorTest.kt` | Setup types | ⬜ |
| `scalping/SignalScoringEngineTest.kt` | Score breakdown | ⬜ |
| `scalping/ScalpingMtfEvaluatorTest.kt` | Update jika perlu | existing |
| `scalping/ScalpingMtfEvaluatorAuditTest.kt` | Update jika perlu | existing |

**Jangan** menambah `.github/workflows/*` di branch ini untuk test.

---

## Log Commit / Progress

| Tanggal (WIB) | Item | Commit / catatan |
|---------------|------|------------------|
| 2026-10-04 | Branch + checkpoint | Branch `engine_scalping_update` dibuat dari main |
| 2026-10-04 | P0.1 | `ScalpingEngineModels.kt` ditambahkan |

---

## Definisi Selesai (MVP branch ini)

- [ ] Regime formal (TRENDING_UP/DOWN, RANGING, HIGH_VOL, …)
- [ ] Structure strength + CHoCH
- [ ] RVOL + ADX + CHOP di IndicatorMath
- [ ] ≥3 setup type terdeteksi
- [ ] Score 0–100 + reasons
- [ ] Direction LONG / WAIT / SHORT
- [ ] Entry zone + dynamic SL/TP + net R:R
- [ ] Backward compatible `AISignalState`
- [ ] Unit test file ada (dijalankan manual nanti)
- [ ] Tidak ada workflow CI test baru

---

**Agent berikutnya:** baca file ini dulu → kerjakan item `[ ]` paling atas di phase aktif → centang → update log → commit. Jangan kerjakan P1 sebelum Gate P0 terpenuhi.
