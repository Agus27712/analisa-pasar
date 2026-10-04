# Checkpoint Progress — Engine Scalping Update

**Branch:** `engine_scalping_update`  
**Base:** `main` @ `e06b12eb`  
**Exchange:** Indodax + Tokocrypto (IDR & USDT) — threshold %/ratio only.

---

## ATURAN WAJIB

1. Ikuti urutan phase. Jangan loncat / ngide di luar spek KriptoYoi.
2. Jangan ubah UI kecuali mapping `AISignalState` backward-compatible.
3. **Jangan** buat CI workflow test.
4. Fee+slippage lewat `FeeCalculator` / `TradingFeeConfig`.
5. WAIT sah. Jangan angka Historical Edge palsu.
6. Bahasa reasoning: Indonesia.

---

## Status Ringkas

| Phase | Status | Catatan |
|-------|--------|---------|
| P0–P1 | ✅ | Models → pipeline |
| P2 | ✅ | Config, edge stub, imbalance, MTF matrix |
| **Wiring P2→Evaluator** | ✅ | Config, OrderFlow, MTF, EdgeStub, falling-knife |
| P3 | ✅ | Backtest adapter + fee/slippage config |
| P4 | ✅ | Market scanner engine (no UI) |
| P5 | ✅ DEFERRED | ML **tidak** dikerjakan (spek: setelah edge terbukti) |
| Tests | 🔄 | File ada; jalankan di Android Studio |

---

## Wiring residual (selesai)

`ScalpingMtfEvaluator` sekarang memakai:

- [x] `ScalpingConfig` (MIN_SCORE_LONG, STRONG, ORDERBOOK_STALE_MS)
- [x] `OrderBookAnalyzer.analyzeOrderFlow` (buy pressure + imbalance formal)
- [x] `MtfConfluenceMatrix.buildPartial` → `mtfAlignment` label
- [x] `HistoricalEdgeStub.stubMessage` → `ScalpingSignal.historicalEdgeStub`
- [x] Falling-knife guard → `STEP1_FALLING_KNIFE`
- [x] `regimeDetected` = `regime.regime.name`

**Review wiring:** Integrasi bersih, tanpa fake probability. SHORT masih HOLD (opsional). Duplikasi `orderImbalance` privat dihapus.

---

## P3 — Backtesting / Expectancy (engine)

- [x] `ScalpingBacktestAdapter` — bungkus `BacktestEngine` + `ScalpingConfig.MIN_NET_RR` / slippage
- [x] Catatan eksplisit: **bukan** Historical Edge setup-spesifik
- [x] Test smoke: `ScalpingBacktestAdapterTest.kt`
- Fee+slippage sudah di risk engine + backtest engine existing

**Review P3:** Baseline backtest deterministik OK untuk regresi. Trigger internal masih EMA sederhana (bukan full pipeline per-bar — mahal di mobile). Jangan pakai win-rate backtest ini sebagai klaim edge produksi tanpa data exchange nyata.

---

## P4 — Scanner (engine only)

- [x] `MarketScannerEngine` — scan multi-pair via `ScalpingMtfEvaluator`, rank LONG/score/R:R
- [x] Tanpa UI / ViewModel
- [x] Test smoke: `MarketScannerEngineTest.kt`

**Review P4:** Cukup untuk fondasi screener Tokocrypto/Indodax. UI bisa consume `ScanResult` nanti. `onlyLongReady` filter opsional. Performa: O(n pairs × evaluate) — batasi batch di layer pemanggil.

---

## P5 — Machine Learning

- [x] **DEFERRED (sengaja)** sesuai spek KriptoYoi §31/§Phase 7:
  - Baseline strategy + backtest stabil dulu
  - Data historis cukup + out-of-sample
  - Overfitting dikontrol
- Jangan implement ML di branch ini.

**Review P5:** Menunda ML adalah keputusan benar. Fokus validasi data nyata + journal dulu.

---

## Unit Test (tanpa CI)

| File | Phase |
|------|-------|
| IndicatorMathScalpingExtTest | P0 |
| MarketRegimeEngineTest | P0 |
| ScalpSetupDetectorTest | P1 |
| SignalScoringEngineTest | P1 |
| ScalpingRiskEngineTest | P1 |
| ScalpingConfigTest / HistoricalEdgeStubTest / OrderImbalanceTest / MtfConfluenceMatrixTest | P2 |
| ScalpingBacktestAdapterTest | P3 |
| MarketScannerEngineTest | P4 |
| ScalpingMtfEvaluator*Test | existing — re-run setelah wiring |

---

## Residual / next (opsional)

1. Jalankan unit test di Android Studio; perbaiki regresi audit test jika ada.
2. Short side penuh (masih opsional).
3. Wire scanner ke UI screener Tokocrypto (di luar scope engine branch jika UI frozen).
4. Historical Edge nyata dari `trade_journal` / backtest per-setup (Phase 5 spek) — **setelah** data cukup.
5. PR review + merge ke main bila test hijau.

**Jangan:** CI workflow, ML model, angka edge palsu, hardcode harga IDR absolut.
