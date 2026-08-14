# Architecture — Package Partition

Target structure for `agu.analys`. Large monoliths are split by responsibility.

```
agu/analys/
├── engine/                         # Trading logic (no UI)
│   ├── indicators/
│   │   ├── IndicatorMath.kt        # RSI, EMA, MACD, BB, ATR
│   │   └── CandlePatternDetector.kt
│   ├── regime/
│   │   └── MarketRegimeDetector.kt
│   ├── scalping/                   # (next) MTF 1H/15M/1M evaluator
│   ├── swing/                      # (next) swing/long evaluate
│   ├── MarketStructureAnalyzer.kt
│   └── LearningTradingEngine.kt    # Orchestrator only (thin)
│
├── ui/
│   ├── animation/
│   │   └── AppAnimations.kt        # FadeSlideIn, live pulse, durations
│   ├── components/
│   │   ├── dashboard/              # Watchlist, volume leaders, overview
│   │   ├── detail/                 # Market condition, levels, scalping status…
│   │   ├── chart/                  # (next) SimpleComposeChart etc.
│   │   └── common/                 # Shared cards, empty states
│   ├── screens/                    # Thin screens — compose components only
│   └── theme/
│
├── service/                        # Network / AI APIs
├── util/                           # Prefs, cache, formatters, updater
├── model/                          # Data classes & enums
├── viewmodel/                      # State holders (keep thin)
├── trading/                        # Spot position store
└── bridge/                         # TradingView JS bridge
```

## Split priority (large files)

| File | Size | Target partition |
|------|------|------------------|
| `LearningTradingEngine.kt` | ~33 KB | `indicators/` + `regime/` + `scalping/` + `swing/` |
| `DetailChartScreen.kt` | ~46 KB | `ui/components/detail/*` (scaffold exists) |
| `DashboardScreen.kt` | ~33 KB | `ui/components/dashboard/*` |
| `TradingViewModel.kt` | ~21 KB | keep, extract helpers to `util/` if needed |
| `AISignalCard.kt` / `SpotPositionCard.kt` | ~17–18 KB | already under `ui/components/` |

## Rules

1. **engine** never imports `ui.*`
2. **ui** never contains indicator math or network calls
3. **animation** only Compose animation helpers
4. Screens should be composition roots (< ~150 lines ideal)
5. New feature = new file in the matching package, not growth of monoliths
