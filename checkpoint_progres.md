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
| P1    | ✅ DONE | Setup + Score + Risk + Evaluator pipeline |
| P2    | ✅ DONE (short opsional belum) | Config, Historical Edge stub, imbalance, MTF matrix |
| Tests | 🔄 PARTIAL | Unit test files ada; jalankan manual di Android Studio |

---

## P0 — Fondasi ✅

- [x] Models, RVOL/CHOP/ADX, MarketRegimeEngine, CHoCH + strength

---

## P1 — Scalping Engine Core ✅

- [x] ScalpSetupDetector + test
- [x] SignalScoringEngine + test
- [x] ScalpingRiskEngine + test
- [x] ScalpingMtfEvaluator pipeline + audit tests

---

## P2 — Pelengkap

- [x] **`ScalpingConfig`** thresholds terpusat (`engine/scalping/ScalpingConfig.kt`)
  - Score gate, R:R, RVOL, order flow, CHOP/ADX/ATR%, spread, MTF keys, pesan historical edge
  - Risk engine default mengarah ke config ini
  - Test: `ScalpingConfigTest.kt`
- [x] **Historical Edge stub** (`HistoricalEdgeStub.kt`)
  - Selalu `INSUFFICIENT` — **tidak ada** win-rate / probabilitas palsu
  - Test: `HistoricalEdgeStubTest.kt`
- [x] **Order imbalance formal** (`OrderBookAnalyzer`)
  - `calculateOrderImbalance` = (bid−ask)/(bid+ask)
  - `analyzeOrderFlow` → `OrderFlowSnapshot`
  - Spread defaults dari `ScalpingConfig`
  - Test: `OrderImbalanceTest.kt`
- [x] **MTF matrix** (`MtfConfluenceMatrix.kt`)
  - Slot 1D / 4H / 1H / 15M / 5M / 1M
  - Partial feed (H1/M15/M1) didukung; TF kosong = UNKNOWN
  - Label alignment contoh `4/6 bullish`
  - Test: `MtfConfluenceMatrixTest.kt`
- [ ] **Short side penuh** (opsional — belum dikerjakan; mapping SHORT masih HOLD di evaluator)

**Gate P2 (inti):** ✅ Config + stub edge + imbalance + MTF helper siap. Short boleh menyusul.

---

## Unit Test (tanpa CI workflow)

| Test file | Scope | Status |
|-----------|--------|--------|
| `indicators/IndicatorMathScalpingExtTest.kt` | RVOL, CHOP, ADX | ✅ |
| `regime/MarketRegimeEngineTest.kt` | Regime | ✅ |
| `scalping/ScalpSetupDetectorTest.kt` | Setup | ✅ |
| `scalping/SignalScoringEngineTest.kt` | Score | ✅ |
| `scalping/ScalpingRiskEngineTest.kt` | Risk | ✅ |
| `scalping/ScalpingConfigTest.kt` | Config constants | ✅ P2 |
| `scalping/HistoricalEdgeStubTest.kt` | No fake stats | ✅ P2 |
| `scalping/OrderImbalanceTest.kt` | Imbalance | ✅ P2 |
| `scalping/MtfConfluenceMatrixTest.kt` | MTF matrix | ✅ P2 |
| `scalping/ScalpingMtfEvaluator*Test.kt` | Pipeline | existing |

**Jangan** menambah `.github/workflows/*` di branch ini untuk test.

---

## Log Commit / Progress

| Tanggal (WIB) | Item | Catatan |
|---------------|------|---------|
| 2026-10-04 | P0–P1 | Models → pipeline evaluator |
| 2026-10-04 | P2 | ScalpingConfig, HistoricalEdgeStub, OrderImbalance, MtfConfluenceMatrix + tests |

---

## Definisi Selesai (MVP branch ini)

- [x] Regime formal
- [x] Structure strength + CHoCH
- [x] RVOL + ADX + CHOP
- [x] ≥3 setup type
- [x] Score 0–100 + reasons
- [x] Direction LONG / WAIT (SHORT opsional)
- [x] Entry zone + dynamic SL/TP + net R:R
- [x] Backward compatible `AISignalState`
- [x] Historical Edge stub (tanpa angka palsu)
- [x] Order imbalance formal
- [x] MTF matrix helper
- [x] ScalpingConfig thresholds
- [x] Unit test file ada (manual run)
- [x] Tidak ada workflow CI test baru
- [ ] Short side penuh (opsional)

---

**Catatan residual:**
- Validasi edge pakai data historis **nyata** Tokocrypto/Indodax, bukan replay sintetis.
- Falling-knife guard (M15 lag di dump cepat) masih kandidat perbaikan terpisah.
- Wire opsional: evaluator bisa memanggil `HistoricalEdgeStub.stubMessage()` ke `ScalpingSignal.historicalEdgeStub` dan `MtfConfluenceMatrix.buildPartial` untuk `mtfAlignment` jika belum fully wired di P1.4.

**Agent berikutnya (jika ada):** short side opsional, atau wire MTF matrix + historical edge stub ke `ScalpingMtfEvaluator` bila belum, atau PR review + jalankan unit test di Android Studio. Jangan tambah CI workflow.
