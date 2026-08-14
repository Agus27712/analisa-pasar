# Scalping v2 specification

## Goal
Trend-following scalping for fast daily trades without chasing overheated candles.

## Entry model
- Market Structure determines directional bias.
- EMA 5/13 and MACD 5/12/4 confirm direction.
- RSI is a timing/context indicator, not a reversal trigger.
- Volume >= 1.2x recent average is confirmation; >= 1.5x is strong.
- Score <45 = HOLD; 45-54 = WATCH; >=55 = ENTRY; >=70 = STRONG ENTRY.
- Dominance >=1.20 for ENTRY; >=1.35 for STRONG ENTRY.
- Conflict is not an independent hard blocker when directional bias and confirmations are aligned.
- RSI 50-65 is healthy LONG territory, 65-70 strong, 70-75 caution, >75 avoid chasing LONG. Mirror for SHORT.
- Sideways should normally produce WATCH, not an automatic permanent no-trade state. A breakout with momentum and volume can promote WATCH to ENTRY.
- If trend is bullish but price is overheated, expose WAIT_PULLBACK rather than BUY/SELL immediately.
- Pullback can become ENTRY when structure remains intact, RSI cools, and EMA/MACD momentum remains aligned.
- ATR >4% is extreme volatility and should block fresh entries; elevated volatility should reduce confidence rather than automatically kill a setup.
- Risk validation remains mandatory: TP1 >= 1R and TP2 >= 1.5R.

## UI semantics
- HOT = radar candidate, not an entry.
- TRENDING = directional market condition.
- WATCH = setup developing.
- WAIT PULLBACK = bullish/bearish setup exists but price is extended.
- ENTRY = actionable setup passes signal and risk checks.
- STRONG ENTRY = higher-confidence actionable setup.
- HOLD = insufficient or invalid setup.

## Implementation note
This file is a specification only. No engine logic is changed by this commit.
