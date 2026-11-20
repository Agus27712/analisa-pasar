# Packet schema: `ai-agent-lens/v1`

One packet is sent per closed candle (or only on setups and trade updates, depending on the "Send JSON packet" input). Missing values are sent as `null`. Prices are rounded to the symbol's tick size.

Full example: [`examples/sample_packet.json`](../examples/sample_packet.json)

## Top level

| Field | Type | Description |
|---|---|---|
| `schema` | string | Always `"ai-agent-lens/v1"` |
| `event` | enum | `BAR_CLOSE`, `LONG_SETUP`, `SHORT_SETUP`, `TRADE_UPDATE` |
| `symbol` | string | Exchange prefix and ticker, e.g. `BINANCE:BTCUSDT` |
| `timeframe` | string | TradingView timeframe code: `1`, `15`, `60`, `240`, `D`, `W` |
| `time` | string | Candle close time, ISO 8601 UTC |
| `state_code` | string | One-line summary: `LENS\|TICKER\|TF\|REGIME\|L<long>\|S<short>\|SIDE:STATUS` |
| `candle` | object | The candle that just closed |
| `recent_candles` | array | The last N candles (default 5), oldest first, the last one is the current candle |
| `regime` | enum | `TREND_UP`, `TREND_DOWN`, `RANGE`, `HIGH_VOLATILITY` |
| `trend` | object | EMAs and higher-timeframe bias |
| `momentum` | object | RSI and MACD |
| `strength` | object | ADX and DI |
| `volatility` | object | ATR and its percentile |
| `volume` | object | Relative volume |
| `structure` | object | Swing structure |
| `levels` | object | Key price levels |
| `scores` | object | Long and short confluence scores and their factors |
| `trade` | object | The indicator's tracked trade |
| `last_setup` | object | Most recent setup |

## `candle`

| Field | Type | Description |
|---|---|---|
| `open`, `high`, `low`, `close` | number | Prices |
| `volume` | number or null | Volume |
| `direction` | enum | `bull`, `bear`, `flat` |
| `body_pct` | number | Body as % of range |
| `upper_wick_pct` | number | Upper wick as % of range |
| `lower_wick_pct` | number | Lower wick as % of range |
| `range_atr` | number or null | Range divided by ATR |
| `close_position_pct` | number | Where the close sits in the range: 0 = low, 100 = high |
| `gap_pct` | number or null | Open versus previous close, % |
| `closed_above_prev_high` | bool | Close > previous high |
| `closed_below_prev_low` | bool | Close < previous low |
| `pattern` | enum | See [SPECIFICATION.md](SPECIFICATION.md#patterns) |

## `recent_candles[]`

| Field | Type | Description |
|---|---|---|
| `bars_ago` | int | 0 = current candle |
| `o`, `h`, `l`, `c` | number | Prices |
| `v` | number or null | Volume |
| `dir` | enum | `bull`, `bear`, `flat` |
| `body_pct` | number | Body as % of range |
| `range_atr` | number or null | Range divided by ATR at that bar |
| `pattern` | enum | Pattern of that candle |

## `trend`

| Field | Type | Description |
|---|---|---|
| `ema_fast`, `ema_mid`, `ema_slow` | number | EMA 20, 50, 200 by default |
| `stack` | enum | `BULLISH` (fast > mid > slow), `BEARISH`, `MIXED` |
| `slow_slope` | enum | `RISING`, `FALLING`, `FLAT` |
| `htf` | string | Higher timeframe used |
| `htf_bias` | enum | `UP`, `DOWN`, `FLAT`, `N/A` |

## `momentum`, `strength`, `volatility`, `volume`

| Field | Type | Description |
|---|---|---|
| `momentum.rsi` | number | RSI 14 |
| `momentum.macd_hist` | number | MACD histogram |
| `momentum.macd_hist_rising` | bool | Histogram higher than previous bar |
| `strength.adx` | number | ADX 14 |
| `strength.di_plus`, `strength.di_minus` | number | Directional indicators |
| `volatility.atr` | number | ATR 14 |
| `volatility.atr_percentile` | number | ATR rank over the last 100 bars, 0 to 100 |
| `volume.relative` | number or null | Volume / 20-bar average |

## `structure` and `levels`

| Field | Type | Description |
|---|---|---|
| `structure.bias` | enum | `BULLISH`, `BEARISH`, `MIXED` |
| `structure.event` | enum | `BREAK_UP`, `BREAK_DOWN`, `NONE` |
| `structure.last_swing_high` | number or null | Last confirmed swing high |
| `structure.last_swing_low` | number or null | Last confirmed swing low |
| `levels.prev_day_high` | number or null | Previous day high |
| `levels.prev_day_low` | number or null | Previous day low |
| `levels.vwap` | number or null | Session VWAP |

## `scores`

| Field | Type | Description |
|---|---|---|
| `long`, `short` | int | 0 to 100 |
| `threshold` | int | Setup threshold in use |
| `long_factors`, `short_factors` | object | `trend`, `momentum`, `strength`, `volume`, `htf`, each 0 to 20 |

## `trade` and `last_setup`

| Field | Type | Description |
|---|---|---|
| `trade.side` | enum | `LONG`, `SHORT`, `NONE` (side of the most recent tracked trade) |
| `trade.status` | enum | `ACTIVE`, `TARGET_1_HIT`, `TARGET_2_HIT`, `STOPPED`, `STOPPED_AFTER_T1`, `NONE` |
| `trade.entry`, `trade.stop`, `trade.target_1`, `trade.target_2` | number or null | Levels of the tracked trade |
| `trade.bars_since_entry` | int or null | Bars since the trade opened |
| `last_setup.side` | enum | `LONG`, `SHORT`, `NONE` |
| `last_setup.bars_ago` | int or null | Bars since the last setup |

## Versioning

Breaking changes will use a new schema name (`ai-agent-lens/v2`). Agents should check `schema` before reading a packet.
