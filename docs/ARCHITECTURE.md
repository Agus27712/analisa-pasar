# Architecture — Package Partition

```
agu/analys/
├── engine/
│   ├── indicators/
│   │   ├── IndicatorMath.kt
│   │   └── CandlePatternDetector.kt
│   ├── regime/
│   │   └── MarketRegimeDetector.kt
│   ├── scalping/
│   │   ├── FrameSignal.kt
│   │   ├── FrameAnalyzer.kt
│   │   └── ScalpingMtfEvaluator.kt
│   ├── swing/
│   │   └── SwingEvaluator.kt
│   ├── MarketStructureAnalyzer.kt
│   └── LearningTradingEngine.kt    # thin orchestrator (~5 KB)
│
├── ui/
│   ├── animation/
│   │   └── AppAnimations.kt
│   ├── components/
│   │   ├── dashboard/
│   │   │   ├── DashboardColors.kt
│   │   │   ├── ModeSwitchToggle.kt
│   │   │   ├── VolumeLeaderChip.kt
│   │   │   └── WatchlistCoinCard.kt
│   │   ├── detail/
│   │   └── …
│   ├── screens/
│   └── theme/
│
├── service/
├── util/
├── model/
├── viewmodel/
├── trading/
└── bridge/
```

## Engine flow

```
LearningTradingEngine
  ├─ isScalpingMode → ScalpingMtfEvaluator (1H / 15M / 1M)
  └─ else           → SwingEvaluator
         └─ IndicatorMath + CandlePatternDetector + MarketRegimeDetector + MarketStructureAnalyzer
```

## Rules

1. `engine` never imports `ui.*`
2. `ui` never contains indicator math or network calls
3. Screens stay composition roots
4. New feature = new file in matching package
