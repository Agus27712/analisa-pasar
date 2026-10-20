# Spec v2: Mesin Sinyal Scalping Spot (Long-Only), Tokocrypto

> **Versi 2 (2026-10-20).** Menggantikan v1. Disesuaikan dengan konteks Tokocrypto, laporan sinkronisasi `LAPORAN-sinkronisasi-spec-scalping-spot-2026-10-20.md`, dan aturan `AGENTS.md`.
> Untuk agen koding. Semua aturan deterministik dan bisa langsung diterjemahkan ke kode. Angka default adalah **hipotesis awal untuk diukur**, bukan klaim edge.
> Jika ada yang ambigu: pilih opsi **paling konservatif**, beri komentar `TODO(spec)`, jangan mengarang parameter baru. Dokumen, komentar kode, dan pesan commit memakai Bahasa Indonesia.

---

## 0. Ruang lingkup dan batasan

- Spot, **long-only**, tanpa leverage, **Tokocrypto saja** (pair USDT dan IDR). Indodax tidak disentuh. Dilarang request ke `binance.com`/`stream.binance.com` dari app.
- Mode: `BACKTEST` dan `PAPER`. **LIVE tidak dibangun** (uang riil masih diblokir sampai paper dan fee truth stabil).
- **Taker-only.** Semua fill (entry, TP, SL) dianggap taker + slippage. Jalur maker/limit tetap ada di model sebagai `TODO(spec)`, tidak diaktifkan.
- **Satu rumus fee dan P&L:** semua perhitungan fee, P&L, break-even, dan R memakai `FeeSchedule`/`FeeCalculator` yang sudah ada. Dilarang membuat rumus kedua.
- **Lantai friksi** (`ScalpingRiskEngine`, default 6,0×) dan **`PairEdgeGate`** tetap berlaku. Jangan dilemahkan hanya demi menambah sinyal (di bawah 5× butuh bukti replay).
- Engine baru di balik flag `LEGACY`/`SPOT_SPEC`, default `LEGACY`. Satu simbol hanya punya satu sinyal aktif, satu kartu status, satu mesin exit. Jangan tampilkan dua engine bersamaan.
- Komponen: indikator, regime classifier, 5 setup (entry + exit), gerbang sinyal, position sizing, position manager, risk manager, backtester, jurnal.
- Setup: `PULLBACK_VWAP`, `BREAKOUT_RETEST`, `SWEEP_RECLAIM`, `RANGE_BOUNCE`, `SQUEEZE_BREAKOUT`.

## 1. Aturan emas (berlaku untuk semua)

1. **No lookahead.** Sinyal dihitung hanya dari candle yang sudah close. Sinyal di close candle `i`, order berlaku mulai candle `i+1`. Swing/fractal baru valid setelah `right` candle penguat close. Candle HTF hanya dipakai jika sudah close.
2. Entry, SL, TP1 harus ada **sebelum** order dikirim. Tanpa SL = sinyal ditolak.
3. Sinyal wajib lolos semua gerbang di §6 (termasuk lantai friksi dan fee).
4. SL hanya boleh bergerak searah profit, tidak pernah melebar.
5. Dilarang average down. Maksimal 1 posisi atau 1 pending order per simbol.
6. Jika SL dan TP kena di candle yang sama, **SL dianggap kena lebih dulu**.
7. Ukuran posisi dihitung dari jarak SL (termasuk fee), bukan dari "mau beli berapa".
8. Jangan menyimpulkan edge dari satu window data. Laporkan juga angka negatif dan batas eksperimen.

---

## 2. Model data (Kotlin, domain murni tanpa dependensi Android)

Package: `agu.analys.engine.spot`. Enum dan model **terpisah** dari `ScalpSetupType` dan `MarketRegime` lama. `CandleBar` hanya dipakai di batas adapter.

```kotlin
data class SpotCandle(
    val openTime: Long,        // epoch ms UTC
    val open: Double, val high: Double, val low: Double, val close: Double,
    val volume: Double         // base asset
)

enum class SpotSetupType { PULLBACK_VWAP, BREAKOUT_RETEST, SWEEP_RECLAIM, RANGE_BOUNCE, SQUEEZE_BREAKOUT }
enum class SpotEntryMode { CONFIRM_BREAK, MARKET_NEXT_OPEN, LIMIT_AT_LEVEL /* TODO(spec): nonaktif, taker-only */ }
enum class SpotTrailMode { NONE, EMA9, HIGHER_LOW, CHANDELIER }
enum class SpotRegime { TREND_UP, TREND_DOWN, RANGE, COMPRESSION, UNDEFINED }

data class SpotSignal(
    val symbol: String,
    val setup: SpotSetupType,
    val timeframe: String,
    val signalCandleTime: Long,
    val entryMode: SpotEntryMode,
    val entryPrice: Double,          // CONFIRM_BREAK = trigger stop-buy; MARKET = estimasi (close candle sinyal)
    val entryValidCandles: Int,      // pending order kedaluwarsa setelah N candle
    val cancelBelow: Double?,        // batalkan pending entry jika low < nilai ini
    val stopLoss: Double,            // SL struktural (sebelum lantai friksi)
    val tp1: Double,
    val tp2: Double?,
    val invalidationLevel: Double?,  // dipakai exit SETUP_INVALIDATION (cek pakai close)
    val trailMode: SpotTrailMode,
    val timeStopCandles: Int,
    val score: Int,
    val reasons: List<String>,
    val atr: Double
)

enum class SpotTradeState { PENDING_ENTRY, OPEN, PARTIAL, CLOSED, CANCELLED }

enum class SpotExitReason {
    STOP_LOSS, BREAKEVEN_STOP, TRAILING_STOP, TAKE_PROFIT_1, TAKE_PROFIT_2,
    SETUP_INVALIDATION, MOMENTUM_FAIL, TIME_STOP, REGIME_CHANGE, ENTRY_EXPIRED, MANUAL
}

data class SpotTrade(
    val id: String, val signal: SpotSignal, var state: SpotTradeState,
    val qtyTotal: Double, var qtyRemaining: Double,
    var entryFill: Double?, var entryTime: Long?,
    val stopInitial: Double,         // SL efektif setelah lantai friksi
    var stopCurrent: Double, var tp1Hit: Boolean = false,
    var highestHighSinceEntry: Double = 0.0, var candlesSinceEntry: Int = 0,
    var maxFavorableR: Double = 0.0, var maxAdverseR: Double = 0.0,
    val fills: MutableList<SpotFill> = mutableListOf()
)
```

Antarmuka setup dan konteks:

```kotlin
interface SpotSetup {
    val type: SpotSetupType
    fun allowedRegimes(): Set<SpotRegime>
    fun evaluate(ctx: SpotContext): SpotSignal?               // null = tidak ada sinyal
    fun invalidated(ctx: SpotContext, t: SpotTrade): Boolean  // exit khusus setup (cek di close candle)
}

data class SpotContext(
    val symbol: String, val tfMinutes: Int,
    val candles: List<SpotCandle>, val i: Int,    // i = index candle terakhir yang SUDAH close
    val ind: SpotIndicatorSeries,                  // ind.ema9[i], ind.atr[i], ind.vwap[i], dst.
    val htf: SpotHtfSnapshot,                      // dari candle HTF terakhir yang sudah close
    val levels: SpotLevelProvider,
    val btcReturnHtfPct: Double?,                  // filter altcoin (opsional)
    val rules: SymbolRules,                        // tickSize, lotStep, minQty, minNotional
    val cfg: SpotConfig
)
```

---

## 3. Konfigurasi (`SpotConfig`, jangan hardcode)

Semua threshold ada di satu kelas config. Jika dicerminkan ke JSON offline, ubah keduanya bersamaan.

| Parameter | Default | Keterangan |
|---|---|---|
| `execTimeframe` | `15m` | opsi `1h`; `5m` hanya on-demand (lihat §3.2) |
| `htfTimeframe` | `1h` | untuk eksekusi 1h pakai `4h` |
| `warmupCandles` | 150 | candle eksekusi sebelum boleh memberi sinyal |
| `htfWarmupCandles` | 60 | |
| `riskPerTradePct` | 0.5 | % equity per trade |
| `maxPositionPctOfEquity` | 25 | batas nilai posisi |
| `maxConcurrentPositions` | 2 | |
| `maxDailyLossPct` | 2.0 | |
| `maxConsecutiveLosses` | 3 | lalu pause 60 menit |
| `cooldownAfterLossMin` | 15 | per simbol |
| `tp1Fraction` | 0.6 | **parameter**, bukan konstanta; uji sensitivitas 0.5 vs 0.6 |
| `frictionFloorMultiple` | 6.0 | dari `ScalpingRiskEngine`, tidak diubah di sini |
| `minNetRrTp1` | 1.0 | R:R bersih ke TP1 |
| `minNetRrFinal` | 1.5 | R:R bersih ke target akhir |
| `minRewardOverFriction` | 3.0 | reward TP1 % ≥ N × F |
| `minAtrPctOverFriction` | 1.5 | ATR% ≥ N × F |
| `minSlAtr` / `maxSlAtr` | 0.5 / 5.0 | jarak SL efektif dalam ATR; `maxSlAtr` dilonggarkan dari 3.0 karena lantai friksi (hipotesis, ukur lewat funnel) |
| `maxSpreadPct` | 0.10 | skip jika spread lebih lebar |
| `btcFilterMinReturnPct` | -1.5 | return BTC 4 candle HTF terakhir (altcoin saja) |
| `volMaPeriod` | 20 | |
| `emaFast` / `emaSlow` / `emaTrend` | 9 / 21 / 50 | |
| `atrPeriod` / `rsiPeriod` / `adxPeriod` | 14 / 14 / 14 | |
| `bbPeriod` / `bbMult` | 20 / 2.0 | |
| `kcPeriod` / `kcMult` | 20 / 1.5 | Keltner (EMA ± mult × ATR20) |
| `riskDayResetTz` | `Asia/Jakarta` | reset batas rugi harian 00:00 |
| `vwapResetUtcHour` | 0 | VWAP reset 00:00 UTC (07:00 WIB) |

### 3.1 Fee dan friksi (Tokocrypto)

Fee Tokocrypto **all-in** (fee bursa + pajak + biaya lain) jauh di atas fee nominal 0,1%. Karena itu **jangan memakai konstanta fee di spec ini**. Ambil semuanya dari `FeeSchedule`/`TradingFeeConfig` per quote asset (USDT/IDR) dan per sisi (beli/jual) lewat `SpotFeeAdapter`.

```
F = buyFeePct + sellFeePct + 2 × slippagePct      // all-in, taker, dari FeeSchedule (satuan %)
```

Rumus ini sama dengan basis lantai friksi di `ScalpingRiskEngine`. Contoh pada laporan sinkronisasi: friksi USDT `F = 0,9688%`; semua contoh di spec ini memakai angka itu hanya sebagai ilustrasi.

> Rujukan eksternal, **belum diverifikasi** (jangan dipakai di kode): perkiraan fee all-in taker sekitar 0,38% per sisi untuk pair USDT dan sekitar 0,32–0,33% untuk pair IDR. Gunakan hanya untuk sanity check terhadap `FeeSchedule`.

### 3.2 Timeframe

- Default eksekusi **M15** dengan HTF **H1**. Keduanya sudah masuk daftar prefetch `MtfCacheManager`, jadi tidak perlu fetch tambahan.
- Eksekusi **H1** dengan HTF **H4** untuk pair yang lebih lambat.
- **M5** hanya on-demand untuk simbol Tokocrypto yang sedang dibuka, dan hanya jika pair lolos `PairEdgeGate` dan gerbang ATR (§6). Jangan menambah prefetch M5 ke seluruh watchlist. Jika M5 tidak tersedia, kembalikan "tidak ada sinyal"; **jangan fallback ke M1**.
- Alasan: friksi pulang-pergi ≈ 1% membuat target sub-2% tidak layak. Contoh dengan F = 0,9688%: TP 2% / SL 1% → win rate impas ≈ 66%; TP 4% / SL 2% → ≈ 50%. Target harus beberapa persen, jadi timeframe harus cukup besar.

---

## 4. Indikator dan helper (definisi tepat)

Buat `SpotIndicatorSeries` baru yang murni. **Jangan mengubah `IndicatorMath` global.**

- **Typical price** `tp = (H+L+C)/3`.
- **VWAP**: `Σ(tp × volume) / Σ volume` sejak reset harian 00:00 UTC. Jika `Σ volume == 0`, pakai nilai sebelumnya.
- **EMA(n)**: `alpha = 2/(n+1)`, seed = SMA n candle pertama.
- **TR** = `max(H−L, |H−prevC|, |L−prevC|)`. **ATR(n)** = Wilder RMA dari TR (seed SMA).
- **RSI(14)**: Wilder RMA gain/loss.
- **ADX(14)**, **+DI**, **−DI**: Wilder standar.
- **Bollinger(20, 2)**: SMA ± 2 × stdev populasi. `bandwidth = (upper−lower)/mid`.
- **Keltner(20, 1.5)**: EMA20 ± 1.5 × ATR20. **squeezeOn** = `bbUpper < kcUpper && bbLower > kcLower`.
- **volMA[i]** = SMA volume dari 20 candle **sebelum** `i` (tidak termasuk candle `i`). `volRatio[i] = volume[i] / volMA[i]`.
- **percentileRank(x, series, n)** = persentase nilai dalam n candle terakhir yang ≤ x.
- **Swing high/low fractal(L, R)**: candle `k` swing high jika `high[k]` > high L candle kiri dan ≥ high R candle kanan (kebalikannya untuk swing low). Valid hanya jika `k + R ≤ i`.
- **Equal lows/highs**: ≥ 2 swing dalam toleransi `equalTolAtr × ATR`.
- **PDH/PDL**: high/low hari UTC sebelumnya. **Asia low**: low sesi 00:00–08:00 UTC hari ini (valid setelah 08:00 UTC).

Helper candle:

```
body       = |close − open|
range      = high − low
lowerWick  = min(open, close) − low
closePos   = range == 0 ? 0.5 : (close − low) / range
isRejection(i)     = lowerWick ≥ 1.5 × max(body, 0.1×ATR) && closePos ≥ 0.6
isBullEngulf(i)    = close[i] > open[i] && close[i−1] < open[i−1] && open[i] ≤ close[i−1] && close[i] ≥ open[i−1]
isConfirmCandle(i) = close[i] ≥ open[i] && (isRejection(i) || isBullEngulf(i))
```

Verifikasi indikator terhadap referensi (TradingView/TA-Lib) dengan **golden fixture**, toleransi `1e-4` setelah warm-up. Tiga sumber selisih yang paling sering: `volMA` yang ikut menghitung candle saat ini, reset VWAP, dan seed Wilder.

---

## 5. Regime classifier

```
TREND_DOWN  : adx ≥ 25 && −DI > +DI && close < vwap && ema9 < ema21
COMPRESSION : squeezeOn[i] (atau [i−1])
TREND_UP    : adx ≥ 20 && +DI > −DI && close > vwap && ema9 > ema21 && ema21[i] > ema21[i−3]
RANGE       : rata-rata adx 10 candle terakhir < 20 && bukan COMPRESSION
UNDEFINED   : selain itu
```

Prioritas evaluasi: `TREND_DOWN` → `COMPRESSION` → `TREND_UP` → `RANGE` → `UNDEFINED`. Buat `SpotRegimeClassifier` terpisah dari `MarketRegimeEngine`.

| Setup | Regime yang diizinkan |
|---|---|
| PULLBACK_VWAP | TREND_UP |
| BREAKOUT_RETEST | semua kecuali TREND_DOWN |
| SWEEP_RECLAIM | RANGE, TREND_UP, UNDEFINED |
| RANGE_BOUNCE | RANGE |
| SQUEEZE_BREAKOUT | COMPRESSION |

`TREND_DOWN` = **blokir semua entry** (v1).

---

## 6. Gerbang sinyal, fee, dan position sizing

### 6.1 Urutan gerbang

Sinyal dari setup harus lolos berurutan. Setiap penolakan **dicatat sebagai alasan** (funnel counter, lihat §11).

| # | Gerbang | Aturan | Kode alasan |
|---|---|---|---|
| G0 | Edge pair | Lolos `PairEdgeGate` (`data/config/pair_gate.json`). Pakai yang sudah ada, jangan tulis ulang | `PAIR_EDGE` |
| G1 | Lantai friksi | Panggil lantai yang sudah ada (`ScalpingRiskEngine`) pada `(entry, stopLoss)`. Hasilnya SL efektif (≥ `frictionFloorMultiple × F`) atau penolakan. **Jangan menulis ulang rumusnya** | `FRICTION_FLOOR` |
| G2 | Jarak SL | `minSlAtr ≤ (entry − slEff)/atr ≤ maxSlAtr` | `SL_ATR_RANGE` |
| G3 | Volatilitas | `atrPct ≥ max(minAtrPctOverFriction × F, frictionFloorMultiple × F / maxSlAtr)` | `ATR_TOO_LOW` |
| G4 | R:R bersih TP1 | `netRr1 ≥ minNetRrTp1` | `NET_RR_TP1` |
| G5 | R:R bersih akhir | `netRrFinal ≥ minNetRrFinal` | `NET_RR_FINAL` |
| G6 | Reward vs friksi | `reward1Pct ≥ minRewardOverFriction × F` | `REWARD_OVER_FRICTION` |
| G7 | Struktur harga | `slEff < entry < tp1 (< tp2)` | `INVALID_LEVELS` |
| G8 | Spread | spread ≤ `maxSpreadPct` (jika order book tersedia; teruskan exchange eksplisit ke cache) | `SPREAD` |

Rumus bersih (semua F dari `FeeSchedule`, satuan %):

```
riskPct        = (entry − slEff) / entry × 100
reward1Pct     = (tp1 − entry) / entry × 100
rewardFinalPct = ((tp2 ?: tp1) − entry) / entry × 100
netRr1         = (reward1Pct − F) / (riskPct + F)
netRrFinal     = (rewardFinalPct − F) / (riskPct + F)
atrPct         = atr / entry × 100
```

### 6.2 Catatan penting kombinasi gerbang

Dengan `F = 0,9688%`: lantai 6× → SL ≥ ±5,81%. Agar `netRr1 = 1`, TP1 harus ≥ ±7,75% (8F). Contoh:

- SL 5,81%, TP1 2,9% → netRr1 = (2,9 − 0,97)/(5,81 + 0,97) ≈ **0,28** → ditolak (`NET_RR_TP1`).
- SL 5,81%, TP1 8,0% → netRr1 ≈ **1,04** → lolos G4.

Artinya banyak setup akan hilang. **Ini bukan alasan melemahkan lantai.** Urutan pelonggaran bila funnel menunjukkan hampir nol sinyal:

1. Longgarkan parameter milik spec ini dulu: `maxSlAtr`, `minNetRrTp1`, `minNetRrFinal`, `minRewardOverFriction`.
2. Naikkan timeframe eksekusi (M15 → H1), karena ATR% dan jarak target lebih besar.
3. Lantai friksi dan `PairEdgeGate` hanya boleh diubah dengan bukti replay baru, lewat keputusan owner.

### 6.3 Position sizing (`SpotPositionSizer`)

Pakai `BigDecimal` untuk pembulatan ke aturan bursa.

```
riskAmount  = equity × riskPerTradePct / 100
perUnitRisk = (entry − slEff) + entry × F / 100
qtyRaw      = riskAmount / perUnitRisk
qty         = min(qtyRaw, equity × maxPositionPctOfEquity/100 / entry, freeBalance / entry)
qty         = floorToStep(qty, lotStep)
tolak jika qty < minQty atau qty × entry < minNotional
```

Format harga dan P&L mengikuti `quoteAsset` (USDT `$` + `≈ Rp`, IDR `Rp`). Untuk entry `MARKET_NEXT_OPEN`, `entryPrice` hanya estimasi: **validasi ulang** G1–G7 dengan harga fill sebenarnya; jika gagal, batalkan.

---

## 7. Framework exit (berlaku untuk semua setup)

State per trade: `stopCurrent`, `tp1`, `tp2`, `tp1Hit`, `trailMode`, `candlesSinceEntry`, `highestHighSinceEntry`, `maxFavorableR`.

```
R                = entryFill − stopInitial          // stopInitial = SL efektif (setelah lantai friksi)
breakEvenPrice   = harga di mana net P&L = 0 menurut FeeCalculator (fee beli + fee jual + slippage). Jangan menurunkan rumus sendiri
timeStopCandles  = nilai dari setup (minimal 3)
```

Semua fill **taker + slippage**. Tidak ada asumsi limit/maker.

### Fase A: intrabar (pakai high/low candle `c`, urutan ini)

1. Jika `c.low ≤ stopCurrent` → jual seluruh `qtyRemaining` di `stopCurrent × (1 − slippage)`. Alasan: `STOP_LOSS`; jika `stopCurrent ≥ breakEvenPrice` dan belum trailing → `BREAKEVEN_STOP`; jika trailing aktif → `TRAILING_STOP`.
2. Else jika `!tp1Hit && c.high ≥ tp1` → jual `floorToStep(qtyTotal × tp1Fraction)` di `tp1 × (1 − slippage)`. Set `tp1Hit = true`, `stopCurrent = max(stopCurrent, breakEvenPrice)`, state `PARTIAL`. Jika porsi partial atau sisa `< minNotional`: jual **semua** di TP1 (tanpa runner).
3. Else jika `tp1Hit && tp2 != null && c.high ≥ tp2` → jual sisa di `tp2 × (1 − slippage)`. TP2 baru dievaluasi mulai **candle setelah** TP1 kena.

### Fase B: saat close candle (eksekusi market di open candle berikutnya, fill = open × (1 − slippage))

4. `SETUP_INVALIDATION`: `setup.invalidated(ctx, trade)` bernilai true.
5. `MOMENTUM_FAIL`: `close < open && (open − close) ≥ 0.8×ATR && volRatio ≥ 1.5`.
6. `REGIME_CHANGE`: regime berubah jadi `TREND_DOWN`.
7. `TIME_STOP`: `!tp1Hit && candlesSinceEntry ≥ timeStopCandles && maxFavorableR < 0.5`.
8. **Update trailing** (hanya jika `tp1Hit`), `stopCurrent = max(stopCurrent, newStop)`, tidak pernah turun:
   - `EMA9`: `ema9[i] − 0.25×ATR`
   - `HIGHER_LOW`: swing low (fractal 2/2) terbaru setelah entry `− 0.1×ATR`
   - `CHANDELIER`: `highestHighSinceEntry − 1.5×ATR`
   - `NONE`: stop tetap di breakeven

`SpotPositionManager` punya mesin exit sendiri. **Jangan dicampur dengan `PaperExitResolver`** (TP1 50%, time-stop 90 bar, trailing lama) sebelum ada adapter dan uji paritas. Untuk satu posisi hanya boleh ada satu mesin exit.

---

## 8. Setup (entry + exit digabung)

Semua kondisi memakai candle `i` = candle terakhir yang close. `ATR` = `ind.atr[i]` kecuali disebut lain. `tick` = `rules.tickSize`. `timeStopCandles` dihitung dalam candle eksekusi (default M15); semua nilai adalah titik awal untuk diukur.

### 8.1 PULLBACK_VWAP (pullback ke VWAP di tren naik)

**Indikator wajib:** VWAP, EMA 9/21, volume + volMA, ATR, ADX. **Opsional:** RSI (dipakai sebagai filter).

| Parameter | Default |
|---|---|
| exec timeframe | M15 (opsi H1) |
| `impulseLookback` | 25 candle |
| `minImpulseAtr` | 2.0 |
| `minImpulseVolRatio` | 1.2 |
| `pullbackVolMaxRatio` | 0.8 |
| `touchTolAtr` | 0.15 |
| `rsiFloor` | 40 (min RSI selama pullback ≥ 35) |
| `maxChaseAtr` | 1.2 |
| `slBufferAtr` | 0.3 |
| `entryValidCandles` | 2 |
| `trailMode` | `EMA9` |
| `timeStopCandles` | 8 |

**Deteksi:**

1. Regime `TREND_UP`. HTF: `ema9 > ema21` dan `close > ema21` pada candle HTF terakhir yang close.
2. **Impuls:** `impulseHighIdx` = argmax(high) dalam `impulseLookback` candle terakhir (minimal 2 candle sebelum `i`). `impulseLowIdx` = argmin(low) dalam 20 candle sebelum `impulseHighIdx`. `impulseSize = high − low ≥ minImpulseAtr × ATR`. Rata-rata volume impuls ≥ `minImpulseVolRatio × volMA[i]`.
3. **Koreksi sehat:** ≥ 2 candle antara `impulseHighIdx` dan `i`. `avgVolPullback ≤ pullbackVolMaxRatio × avgVolImpulse`. Tidak ada candle koreksi yang close di bawah `VWAP − 0.3×ATR`.
4. **Sentuhan VWAP:** `low[i] ≤ vwap[i] + touchTolAtr×ATR` dan `close[i] > vwap[i]`. Bonus skor jika `|ema21 − vwap| ≤ 0.3×ATR`.
5. **Konfirmasi:** `isConfirmCandle(i)` dan `volRatio[i] ≥ 1.0`.
6. **RSI:** `rsi[i] ≥ 40` dan min RSI selama koreksi ≥ 35.

**Entry:** `CONFIRM_BREAK`, stop-buy di `high[i] + tick`, valid 2 candle. `cancelBelow = pullbackLow` (low terendah sejak `impulseHighIdx`). Tolak jika `entry − vwap > maxChaseAtr × ATR`.

**SL struktural:** `pullbackLow − 0.3×ATR` (lalu lewat G1).

**TP:** `tp1 = impulseHigh` (tolak jika `< entry + 1.0R`). `tp2 = impulseHigh + 0.5 × impulseSize`. Sisa di-trail `EMA9`.

**Exit khusus (`invalidated`):** `close < vwap − 0.2×ATR && volRatio ≥ 1.0` (berlaku sebelum maupun sesudah TP1).

**Skor (informasi):** +1 volRatio[i] ≥ 1.5, +1 konfluensi EMA21–VWAP.

---

### 8.2 BREAKOUT_RETEST (tembus resistance, retest, lanjut)

**Indikator wajib:** level horizontal (cluster swing high, PDH), volume + volMA, ATR. **Opsional:** Bollinger bandwidth (kompresi sebelum breakout), EMA 21.

| Parameter | Default |
|---|---|
| exec timeframe | M15–H1 |
| `levelLookback` | 80 candle |
| `swingFractal` | L=R=3 |
| `levelTolAtr` | 0.2 |
| `minTouches` | 2 |
| `breakoutVolRatio` | 1.5 |
| `breakoutBodyAtr` | 0.6 |
| `breakoutClosePos` | 0.7 |
| `maxExtensionAtr` | 2.5 |
| `retestMaxCandles` | 10 |
| `retestTouchTolAtr` | 0.15 |
| `retestFailAtr` | 0.1 |
| `retestVolMaxRatio` | 0.7 |
| `slBufferAtr` | 0.5 |
| `entryValidCandles` | 2 |
| `trailMode` | `HIGHER_LOW` |
| `timeStopCandles` | 12 |

**Deteksi:**

1. **Level `L`:** cluster ≥ `minTouches` swing high (valid, dalam `levelLookback`) yang berjarak ≤ `levelTolAtr × ATR`; `L` = rata-rata cluster. PDH juga kandidat level.
2. **Candle breakout `b`:** `close[b] > L + 0.1×ATR`, `volRatio[b] ≥ 1.5`, `body[b] ≥ 0.6×ATR`, `closePos[b] ≥ 0.7`, `close[b] − L ≤ 2.5×ATR`. Candle sebelum `b` close ≤ `L + tol` (breakout pertama). `rangeLow` = low terendah 40 candle sebelum `b`. `rangeHeight = L − rangeLow`.
3. **Retest:** dalam `retestMaxCandles` candle setelah `b`: jika ada **close < L − 0.1×ATR** → batal (`FAILED_BREAKOUT`). Candle retest `r` memenuhi `low[r] ≤ L + 0.15×ATR`. Rata-rata volume `b+1..r` ≤ `0.7 × volume[b]`.
4. **Konfirmasi:** `r` = candle terakhir (`i`), `close[r] > L`, `isConfirmCandle(r)`.

**Entry:** `CONFIRM_BREAK` di `high[r] + tick`, valid 2 candle. `cancelBelow = L − 0.1×ATR`. Tolak jika `entry − L > 1.5×ATR`. (`LIMIT_AT_LEVEL` di `L + 0.05×ATR`: `TODO(spec)`, nonaktif.)

**SL struktural:** `min(retestLow, L) − 0.5×ATR`.

**TP:** `tp1 = entry + 1.0R`. `tp2 = L + rangeHeight` (measured move); jika `tp2 < entry + 1.5R` → `tp2 = null` (andalkan trailing).

**Exit khusus (`invalidated`):** `close < L − 0.1×ATR` (breakout gagal). Tetap berlaku setelah `tp1Hit`.

---

### 8.3 SWEEP_RECLAIM (liquidity sweep lalu reclaim)

**Indikator wajib:** level likuiditas (swing low, equal lows, PDL, Asia low), volume + volMA, ATR. **Opsional untuk skor:** RSI (divergence), VWAP.

| Parameter | Default |
|---|---|
| exec timeframe | M15 (opsi H1) |
| `liqLookback` | 100 candle |
| `swingFractal` | L=R=5 |
| `equalTolAtr` | 0.15 |
| `levelMinAgeCandles` | 10 |
| `sweepMinDepthAtr` | 0.05 |
| `sweepMaxDepthAtr` | 1.5 |
| `reclaimMaxCandles` | 3 (termasuk candle sweep) |
| `sweepWickRatio` | 0.5 |
| `volSpikeRatio` | 1.5 |
| `entryGuardAtr` | 1.0 |
| `slBufferAtr` | 0.2 |
| `minScore` | 3 |
| `failedReclaimCandles` | 3 |
| `trailMode` | `CHANDELIER` |
| `timeStopCandles` | 6 |

**Deteksi:**

1. Regime bukan `TREND_DOWN`. Jika simbol altcoin dan `btcReturnHtfPct` tersedia: wajib `btcReturnHtfPct > btcFilterMinReturnPct` (4 candle HTF terakhir).
2. **Level `L`** = level terdekat di bawah harga: swing low, equal lows (level = low terendah cluster), PDL, atau Asia low; usia ≥ `levelMinAgeCandles`.
3. **Sweep candle `j`:** `low[j] < L − 0.05×ATR` dan `low[j] ≥ L − 1.5×ATR` (terlalu dalam = breakdown sungguhan, tolak).
4. **Reclaim:** candle pertama `k` (`j ≤ k ≤ j + reclaimMaxCandles − 1`) dengan `close[k] > L`. Sinyal muncul di close `k` (`k == i`). Jika `k == j`: wajib `lowerWick[j] / range[j] ≥ 0.5`.
5. **Skor** (wajib ≥ `minScore`):
   - +1 `volRatio[j] ≥ 1.5`
   - +1 bullish RSI divergence (low sweep < low swing sebelumnya, tetapi RSI di low sweep lebih tinggi)
   - +1 `close[k] > vwap[k]` atau `|vwap − L| ≤ 1×ATR`
   - +1 level adalah PDL, Asia low, atau equal lows
   - +1 `isConfirmCandle(k)`

**Entry:** default `MARKET_NEXT_OPEN`. Tolak jika `entry − L > entryGuardAtr × ATR`. (`LIMIT_AT_LEVEL` di `L + 0.05×ATR`: `TODO(spec)`, nonaktif.)

**SL struktural:** `sweepLow − 0.2×ATR`, dengan `sweepLow` = low terendah `j..k`.

**TP:** kandidat target di atas entry = {VWAP, midRange, rangeHigh, equal highs terdekat}, dengan `rangeHigh/rangeLow` dari 60 candle terakhir. `tp1` = kandidat terdekat yang ≥ `entry + 1.0R`; jika tidak ada → tolak. `tp2` = kandidat berikutnya di atas `tp1` (atau null). Sisa di-trail `CHANDELIER`.

**Exit khusus (`invalidated`):** dalam `failedReclaimCandles` candle pertama setelah entry: `close < L − 0.05×ATR` → keluar (reclaim gagal).

---

### 8.4 RANGE_BOUNCE (pantulan di dasar range)

**Indikator wajib:** Bollinger Bands, RSI, ADX, level range (cluster swing). **Opsional:** VWAP sebagai target.

| Parameter | Default |
|---|---|
| exec timeframe | M15 (opsi H1) |
| `rangeLookback` | 60 candle |
| `minTouchesPerSide` | 2 |
| `sideTolAtr` | 0.2 |
| `minRangeHeightPct` | `max(0.6, 3 × F)` |
| `adxMax` | 20 (rata-rata 10 candle) |
| `bottomZonePct` | 0.2 |
| `rsiOversold` | 35 (dalam 3 candle terakhir) |
| `slBufferAtr` | 0.7 |
| `entryValidCandles` | 2 |
| `trailMode` | `NONE` |
| `timeStopCandles` | 12 |

**Deteksi:**

1. Regime `RANGE`.
2. **Range:** `rangeHigh` dan `rangeLow` = cluster swing (≥ 2 sentuhan tiap sisi, toleransi `sideTolAtr × ATR`). `rangeHeight / price ≥ minRangeHeightPct`. Maksimal 1 close di luar `[rangeLow − 0.3×ATR, rangeHigh + 0.3×ATR]` selama `rangeLookback`.
3. **Posisi:** `(close[i] − rangeLow) / (rangeHigh − rangeLow) ≤ 0.2`.
4. **Pemicu:** salah satu dari 3 candle terakhir `low ≤ bbLower` dan `min(rsi 3 candle) ≤ 35`.
5. **Konfirmasi:** `isConfirmCandle(i)`, `close[i] > bbLower[i]`, `volRatio[i] ≥ 1.0`.

**Entry:** `CONFIRM_BREAK` di `high[i] + tick`, valid 2 candle. `cancelBelow = rangeLow − 0.2×ATR`. (`LIMIT_AT_LEVEL` di `rangeLow + 0.1×ATR`: `TODO(spec)`, nonaktif.)

**SL struktural:** `min(rangeLow − 0.7×ATR, low[i] − 0.2×ATR)`.

**TP:** `tp1 = midRange` (tolak jika `< entry + 1.0R`). `tp2 = rangeHigh − 0.15×ATR`. Tanpa trailing; setelah TP1, SL ke breakeven.

**Exit khusus (`invalidated`):** `close < rangeLow − 0.2×ATR` (range pecah) atau `adx ≥ 25 && −DI > +DI`.

---

### 8.5 SQUEEZE_BREAKOUT (kompresi volatilitas lalu ekspansi)

**Indikator wajib:** Bollinger Bands, Keltner Channel, ATR, volume + volMA. **Opsional:** EMA 9/21 dan VWAP untuk bias.

| Parameter | Default |
|---|---|
| exec timeframe | M15–H1 |
| `minSqueezeCandles` | 6 |
| `bwPercentileMax` | 20 (lookback 100) |
| `atrRatioMax` | 0.8 (ATR14 / SMA(ATR14, 50)) |
| `breakoutVolRatio` | 1.5 |
| `maxBreakoutRangeAtr` | 2.0 |
| `marketEntryMaxAtr` | 0.6 |
| `slMode` | `BOX_MID` (alt `BOX_LOW`) |
| `trailMode` | `EMA9` |
| `timeStopCandles` | 8 |

**Deteksi:**

1. `squeezeOn` berturut-turut ≥ `minSqueezeCandles` hingga candle `i−1`. Di `i−1`: `percentileRank(bandwidth) ≤ 20` dan `atrRatio ≤ 0.8`.
2. **Box:** `compHigh` = high tertinggi selama squeeze, `compLow` = low terendah, `boxHeight = compHigh − compLow`.
3. **Bias:** `close[i] > vwap[i]` dan `ema9[i] ≥ ema21[i]`.
4. **Candle pemicu `i`:** `close[i] > compHigh`, `close[i] ≥ bbUpper[i]`, `volRatio[i] ≥ 1.5`, `range[i] ≤ 2.0 × ATR[i−1]`, `closePos[i] ≥ 0.6`.

**Entry:** hanya jika `close[i] − compHigh ≤ marketEntryMaxAtr × ATR` → `MARKET_NEXT_OPEN`. Jika lebih jauh: tolak (jangan mengejar candle yang sudah terlalu besar). Mode retest dengan limit di `compHigh + 0.2×ATR` adalah `TODO(spec)`, nonaktif.

**SL struktural:** `BOX_MID` = `(compHigh + compLow)/2 − 0.2×ATR`; `BOX_LOW` = `compLow − 0.1×ATR`.

**TP:** `tp1 = entry + 1.0R`. `tp2 = compHigh + 1.5 × boxHeight` (null jika `< entry + 1.5R`). Sisa di-trail `EMA9`.

**Exit khusus (`invalidated`):** dalam 3 candle pertama setelah entry: `close < compHigh − 0.1×ATR` (false breakout).

---

## 9. Risk manager (`SpotRiskManager`)

- **Batas rugi harian:** jika PnL bersih realisasi sejak 00:00 `riskDayResetTz` ≤ `−maxDailyLossPct` dari equity awal hari → blokir entry baru sampai hari berikutnya (posisi terbuka tetap dikelola).
- **Loss beruntun:** `maxConsecutiveLosses` → pause semua entry 60 menit.
- **Cooldown simbol:** `cooldownAfterLossMin` setelah trade rugi di simbol itu.
- **Maksimal posisi bersamaan:** `maxConcurrentPositions`.
- **Blackout:** daftar jendela waktu (`blackoutWindows`) dan flag manual `newsBlackout` untuk memblokir entry baru.
- **Likuiditas:** `maxSpreadPct`; opsional kedalaman order book dalam ±0,3% ≥ 10 × nilai posisi.

---

## 10. Pipeline per candle close dan aturan fill

```
onClosedCandle(symbol, candle):
  1. update seri indikator
  2. kelola trade terbuka: Fase A lalu Fase B (§7)
  3. kedaluwarsakan pending entry (entryValidCandles) / batalkan jika low < cancelBelow
  4. cek fill pending entry
  5. jika riskManager.allows(symbol) dan tidak ada posisi/pending untuk simbol:
       regime = classify(ctx)
       sinyal = setups.filter { regime in it.allowedRegimes() }.mapNotNull { it.evaluate(ctx) }
       lewatkan gerbang §6 (catat alasan penolakan)
       pilih terbaik: skor tertinggi, lalu netRrFinal tertinggi
       hitung qty → buat SpotTrade(PENDING_ENTRY)
```

**Aturan fill (backtest dan paper), taker-only:**

- Stop-buy trigger `T`: terisi di candle `c` jika `c.high ≥ T`; fill = `max(c.open, T) × (1 + slippage)`.
- Market: fill = `open[i+1] × (1 + slippage)`.
- TP dan SL: fill sebagai taker, `× (1 − slippage)`, sesuai §7.
- Limit buy (`LIMIT_AT_LEVEL`): `TODO(spec)`, nonaktif.
- Setelah fill di candle `c`, cek SL di candle yang sama (asumsi terburuk). TP tidak dievaluasi di candle entry.
- Fee dan P&L dihitung lewat `FeeCalculator`.

---

## 11. Backtester (`SpotBacktester`, Kotlin)

- **Data:** CSV 1m Tokocrypto di `data/tokocrypto` dan `data/tokocrypto_v2` (`<PAIR>_1m.csv`, candle CLOSED), **diagregasi** ke timeframe eksekusi dan HTF. Aturan agregasi: bucket berdasarkan `openTime` dibulatkan ke timeframe (UTC); `open` = pertama, `high` = maks, `low` = min, `close` = terakhir, `volume` = jumlah; buang bucket terakhir yang belum lengkap dan bucket yang kehilangan > 10% menit. Pair USDT dan IDR saja. Unduh ulang data hanya dari jaringan Indonesia (`tools/fetch_tokocrypto_klines.py`).
- Wajib: fee/slippage dari `FeeSchedule`, aturan fill §10, urutan SL-sebelum-TP, position sizing §6.3, risk manager §9, gerbang §6.
- **Funnel counter** per setup dan simbol: jumlah kandidat di tiap tahap (`SETUP_CONDITION`, `REGIME`, `PAIR_EDGE`, `FRICTION_FLOOR`, `SL_ATR_RANGE`, `ATR_TOO_LOW`, `NET_RR_TP1`, `NET_RR_FINAL`, `REWARD_OVER_FRICTION`, `SPREAD`, `RISK_MANAGER`) sampai trade. Tujuannya melihat gerbang mana yang membunuh sinyal.
- **Metrik** (per setup, simbol, regime): jumlah trade, jumlah trade tidak tuntas, win rate, rata-rata R menang/kalah, **expectancy (R bersih)**, profit factor, max drawdown, rata-rata durasi hold, total fee, TP1 hit rate, distribusi `SpotExitReason`, MFE/MAE dalam R.
- **Anti-overfit:** split 70/30 atau walk-forward; sensitivitas parameter ±20%; sensitivitas `tp1Fraction` 0.5 vs 0.6; setup dianggap valid hanya jika ≥ 100 trade dan expectancy bersih > 0 di out-of-sample. Jangan menyimpulkan edge dari satu window.
- **Eksperimen offline:** boleh membandingkan dengan dan tanpa lantai friksi, **hanya** untuk riset dan dilaporkan; produksi tetap memakai lantai.
- Backtester tinggal di Kotlin. Jika butuh harness Python, wajib ada uji paritas terhadap fixture Kotlin.
- Ekspor hasil ke CSV. Setiap fase menulis bagian "Hasil" di laporan: perintah, dataset, rentang tanggal, metrik, angka negatif, batas eksperimen.

---

## 12. Arsitektur

```
app/src/main/java/agu/analys/engine/spot/
  model/        SpotConfig, SpotSignal, SpotTrade, SpotFill, enum di §2
  indicator/    SpotIndicatorSeries (EMA, ATR, RSI, ADX, BB, Keltner, VWAP, volMA)
  level/        SpotLevelProvider (swing/fractal, equal low, PDH/PDL, Asia low)
  regime/       SpotRegimeClassifier
  setup/        PullbackVwap, BreakoutRetest, SweepReclaim, RangeBounce, SqueezeBreakout
  trade/        SpotFeeAdapter (membungkus FeeSchedule), SpotPositionSizer, SpotPositionManager, SpotRiskManager
  backtest/     SpotBacktester, SpotFillEngine, SpotMetrics, SpotFunnel
  journal/      SpotJournal (interface)
```

- Domain murni Kotlin, tanpa dependensi Android, bisa dites dengan JUnit.
- `SpotCandleSource` adalah antarmuka; implementasi pertama hanya Tokocrypto. `SpotEngine` memproses satu simbol pada satu waktu dan hanya menerima candle close.
- Harga/qty ke bursa: `BigDecimal` dengan `tickSize`/`lotStep`/`minNotional` dari `SymbolRules`.
- Indikator untuk backtest dihitung sekali sebagai array lalu diakses `[i]`; pastikan tidak ada akses `[i+n]`.
- Pemetaan ke UI hanya lewat adapter pada fase integrasi. Jangan ubah `when` dan tes engine lama.
- Cache, SharedPreferences, dan Room tetap dipartisi per exchange.

---

## 13. Jurnal (Room)

Tabel **baru** khusus engine ini, dengan migrasi Room yang eksplisit. Jangan menimpa `signal_logs` atau `trade_history_records`.

Per trade: `id`, waktu sinyal, simbol, setup, timeframe, regime, entry/SL struktural/SL efektif/TP1/TP2, qty, `riskAmount`, skor, snapshot indikator (ATR, ADX, RSI, volRatio, VWAP, EMA9/21), harga fill entry, semua fill exit, `SpotExitReason`, fee total, PnL bersih, hasil dalam R, MFE/MAE dalam R, durasi hold, alasan penolakan gerbang (untuk sinyal yang ditolak).

---

## 14. Kriteria penerimaan (test)

Jalankan tes dengan scope kelas yang diubah: `./gradlew :app:testDebugUnitTest --tests "agu.analys.engine.spot.*"` (JDK 17, `chmod +x gradlew` dulu).

1. Indikator cocok dengan golden fixture (toleransi `1e-4` setelah warm-up).
2. **No-lookahead:** hasil sinyal di `i` identik meski candle `> i` dibuang.
3. Tiap setup punya fixture positif (sinyal dengan entry/SL/TP yang diharapkan) dan fixture negatif (volume koreksi besar, close di bawah level, ADX tinggi, dsb.) yang menghasilkan `null`.
4. Invarian: `slEff < entry < tp1 (< tp2)`; jumlah qty exit = `qtyTotal`; `stopCurrent` tidak pernah turun.
5. SL dan TP kena di candle yang sama → `STOP_LOSS`.
6. Fee: hasil P&L dan break-even sama persis dengan `FeeCalculator`. Tidak ada rumus fee kedua. Contoh gerbang memakai `F` dari `FeeSchedule`.
7. Position sizing: risiko aktual (termasuk fee) ≤ `riskAmount`, qty dibulatkan ke `lotStep`, nilai ≥ `minNotional`.
8. Risk manager memblokir entry setelah batas rugi harian dan setelah 3 loss beruntun.
9. Backtest deterministik: data sama → hasil identik.
10. **Kontrol negatif:** untuk tiap gerbang (§6) dan tiap perbaikan bug, matikan gerbang/perbaikan itu dan pastikan tes yang relevan **gagal**.
11. Setiap simbol hanya punya satu sinyal aktif dan satu mesin exit.

---

## 15. Fase kerja (satu fase per sesi)

| Fase | Pekerjaan | Gerbang lolos |
|---|---|---|
| 0 | Dokumentasi, commit spec, baseline test | review owner |
| 1 | Model, config, indikator, helper | tes indikator dan no-lookahead hijau |
| 2 | Regime classifier, `SpotFeeAdapter`, gerbang §6, `SpotPositionSizer` | fee truth dan sizing lolos; ukur distribusi ATR% pair aktif |
| 3 | `SpotPositionManager`, aturan fill, backtester minimal + funnel | SL-before-TP, next-candle, deterministik |
| 4 | `PULLBACK_VWAP` end-to-end | laporan hasil terukur beserta funnel |
| 5 | `BREAKOUT_RETEST` → `RANGE_BOUNCE` → `SQUEEZE_BREAKOUT` → `SWEEP_RECLAIM` | tiap setup punya fixture positif/negatif |
| 6 | Risk manager, jurnal Room, UI | migrasi dan uji UI |
| 7 | Paper Tokocrypto, satu engine aktif | paper vs backtest sebanding; tidak ada duplikasi sinyal/exit; posisi tersimpan lewat `SpotPositionStore` dan aturan rehidrasi paper |
| 8 | LIVE | **tidak dikerjakan** |

Kerjakan satu fase per sesi, pastikan tes fase sebelumnya hijau sebelum lanjut.

**Kewajiban per perubahan:** perbarui `workplan2.md`, `AGENTS.md`, dan `docs/LAPORAN-*.md`; commit non-rilis (docs/fee/config) sertakan `[skip ci]`; jangan push ke `main` tanpa instruksi owner.

---

## 16. Riwayat dan daftar TODO

**Perubahan v1 → v2:**

- Fee: konstanta 0,10% dihapus; semua dari `FeeSchedule` (all-in Tokocrypto, taker-only). Maker/limit jadi `TODO(spec)`.
- Timeframe: default M15/H1 (sebelumnya 5m/15m); `timeStopMinutes` diganti `timeStopCandles` per setup.
- Gerbang: ditambah `PairEdgeGate` dan lantai friksi 6×; `maxSlAtr` 3,0 → 5,0 (hipotesis); funnel counter wajib.
- Exit: TP dan SL sebagai taker + slippage; break-even dari `FeeCalculator`; `tp1Fraction` jadi parameter.
- Backtest: sumber CSV 1m yang sudah ada, diagregasi; kontrol negatif; kewajiban dokumentasi repo.
- Nama tipe memakai prefix `Spot*` dan package `agu.analys.engine.spot`.

**TODO(spec):** jalur maker/limit (`LIMIT_AT_LEVEL`); LIVE; dukungan stop order native bursa; keputusan owner soal lokasi spec (`docs/exp/` vs `docs/`), engine baru sebagai pengganti atau alternatif, dan `tp1Fraction` untuk paper.