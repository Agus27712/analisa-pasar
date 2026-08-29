# Audit `analisa-pasar` — Missed BUY Opportunities

**Tanggal audit:** 2026-08-29  
**Branch:** `main`  
**Scope:** engine Scalping, Swing, Second-Wave, data realtime, backtest/validation, dan jalur keputusan BUY.  
**Tujuan:** mencari penyebab peluang BUY yang secara visual sudah naik/terjadi tetapi engine terlambat atau tidak pernah mengeluarkan `BUY`.

> **Catatan penting:** audit ini berbasis source code repository yang aktif saat audit. Saya tidak menganggap sinyal sebagai valid hanya karena harga kemudian naik. Temuan di bawah adalah masalah logika/data-flow yang dapat menyebabkan peluang terlewat.

---

## 1. Kesimpulan Utama

Masalah terbesar **bukan satu indikator yang salah**, tetapi desain decision gate yang terlalu serial dan terlalu banyak syarat harus `true` secara bersamaan.

Pada mode Scalping, `BUY` baru keluar jika seluruh rantai berikut lolos:

```text
1H bias
  ↓
15M setup
  ↓
1M trigger
  ↓
quality gate
  ↓
net R:R setelah fee + slippage
  ↓
NOT sideways
  ↓
NOT overfit
  ↓
NOT extended
  ↓
NOT extreme volatility
  ↓
BUY
```

Artinya, **satu gate saja gagal → BUY hilang**, walaupun peluang entry sebenarnya masih layak.

Temuan paling kritis:

1. **Scalping terlalu bergantung pada konfirmasi 1H + 15M + 1M secara simultan.** Ini menyebabkan delay struktural pada entry cepat.
2. **Walk-Forward validation dipakai sebagai hard veto BUY.** Jika `isOverfitted == true`, BUY selalu diblokir, bukan hanya confidence yang diturunkan.
3. **Sideways regime juga hard veto.** Padahal breakout dari sideways adalah salah satu momen scalping yang paling cepat.
4. **`DYNAMIC_AUTO` belum benar-benar dinamis.** Di evaluator, hanya `AGGRESSIVE` yang dibaca sebagai mode khusus; `DYNAMIC_AUTO` jatuh ke perilaku non-aggressive/balanced.
5. **Trigger 1M terlalu berbasis kondisi candle terakhir.** Breakout/retest hanya dikenali pada candle saat ini, sehingga event yang hanya berlangsung sebentar dapat hilang pada refresh berikutnya.
6. **Realtime WebSocket hanya membentuk candle 1M.** Tidak ada evidence dari source yang menunjukkan H1/15M memperoleh feed realtime setara; akibatnya MTF bisa memakai konteks yang tertinggal sementara trigger 1M sudah berubah.
7. **Backtest yang dipakai Walk-Forward tidak identik dengan logika ScalpingMtfEvaluator.** Jadi label `overfit` tidak benar-benar memvalidasi strategi scalping yang sedang dipakai.
8. **Scalping test sangat minim terhadap kasus BUY nyata.** Test bullish hanya mengecek confidence >30, bukan memastikan BUY keluar pada setup yang seharusnya ditangkap.

---

# 2. Audit Mode Scalping — Prioritas Paling Tinggi

## 2.1 Hard gate MTF menyebabkan entry terlambat

File utama:

`app/src/main/java/agu/analys/engine/scalping/ScalpingMtfEvaluator.kt`

Engine menghitung bias 1H, setup 15M, dan trigger 1M. `ready` baru menjadi true ketika:

```text
biasLong && setupLong && triggerLong
&& !extended
&& !extremeVolatility
&& feeOk
&& qualityOk
&& !isSidewaysRegime
&& !wfReport.isOverfitted
```

**Dampak:**

- 1M bisa sudah menunjukkan reversal/breakout.
- Tetapi 15M belum selesai membentuk candle konfirmasi.
- Atau H1 belum mengubah EMA/structure.
- Engine tetap HOLD.
- Ketika H1/15M akhirnya mengonfirmasi, harga sudah bergerak jauh.

Ini adalah **structural entry lag**, bukan sekadar masalah threshold.

### Rekomendasi

Pisahkan menjadi 3 tingkat:

```text
WATCH → EARLY ENTRY → CONFIRMED ENTRY
```

Jangan jadikan semua timeframe sebagai hard gate.

Contoh:

- H1 = bias/filter, bukan trigger.
- 15M = setup.
- 1M = entry trigger.
- Jika H1 belum bearish dan 15M sedang reclaim, 1M boleh menghasilkan `EARLY BUY`.
- `CONFIRMED BUY` tetap membutuhkan alignment lebih ketat.

---

## 2.2 Walk-Forward menjadi veto absolut

`ScalpingMtfEvaluator` melakukan:

```text
wfReport = WalkForwardEvaluator.validate(m1Candles, fees)
```

lalu:

```text
if (wfReport.isOverfitted) score -= 15
```

dan lebih parah:

```text
ready = ... && !wfReport.isOverfitted
```

Jadi validation yang seharusnya menjadi **risk/confidence modifier** berubah menjadi **BUY blocker**.

### Kenapa ini bermasalah

Walk-forward adalah evaluasi histori. Kondisi market yang sedang berubah cepat tidak otomatis berarti setup live sekarang buruk.

Selain itu `WalkForwardEvaluator` menggunakan `BacktestEngine`, sedangkan `BacktestEngine` memakai trigger sederhana:

```text
close > EMA20
prev close <= EMA20
volume > 0
```

Itu **bukan logika lengkap ScalpingMtfEvaluator**.

Jadi engine dapat mengatakan:

> strategi dianggap overfit

padahal yang diuji bukan strategi scalping MTF yang sebenarnya.

### Rekomendasi

Ubah menjadi:

```text
isOverfitted → confidence penalty / risk multiplier
```

bukan:

```text
isOverfitted → BUY forbidden
```

Dan jika ingin validasi strategi, backtest harus memanggil **logika signal yang sama** dengan live evaluator.

---

## 2.3 Sideways = BUY diblokir total

Kode menetapkan:

```text
if (isSidewaysRegime) score -= 15
```

dan:

```text
ready = ... && !isSidewaysRegime
```

Ini terlalu keras untuk scalping.

Market sideways justru sering menjadi tempat:

```text
compression → breakout → volume expansion → impulse
```

Jika engine menunggu status market berubah menjadi trending terlebih dahulu, ia berpotensi mendeteksi breakout **setelah** sebagian besar impuls selesai.

### Rekomendasi

Sideways harus dibagi:

```text
SIDEWAYS_RANGE
SIDEWAYS_COMPRESSION
SIDEWAYS_BREAKOUT_PENDING
SIDEWAYS_BREAKOUT_CONFIRMED
```

BUY boleh ketika:

```text
compression + breakout + volume expansion + reclaim
```

bukan menunggu `SIDEWAYS == false`.

---

## 2.4 Trigger 1M terlalu event-based pada candle terakhir

`FrameAnalyzer` mendeteksi:

```text
breakoutUp = last.close > previous.high && last.high >= previous.high
```

dan:

```text
retestUp = last.low <= emaFast && last.close > emaFast && previous.close >= emaFast
```

Ini bagus untuk sinyal deterministik, tetapi terlalu sempit untuk scalping.

Contoh yang bisa terlewat:

```text
Candle A: breakout
Candle B: harga lanjut naik
refresh/evaluasi terjadi pada Candle B
```

Jika tidak ada lagi kondisi `close > previous.high` yang cocok, event breakout A tidak lagi terlihat sebagai trigger baru.

### Rekomendasi

Gunakan state/event memory:

```text
BREAKOUT_DETECTED
→ RETEST_WINDOW_ACTIVE (mis. 1–3 candle)
→ ENTRY_TRIGGERED
→ EXPIRED
```

Jangan hanya bertanya:

> "Apakah candle terakhir breakout?"

Tetapi:

> "Apakah kita masih berada di jendela entry setelah breakout yang valid?"

---

## 2.5 Realtime candle 1M terus berubah

`IndodaxMarketWebSocket` menerima chart tick dan melakukan update candle 1M secara realtime. Candle yang sedang berjalan dikirim berulang kali melalui `onCandle(next)`.

Artinya indikator pada candle aktif dapat berubah setiap tick.

Untuk scalping, ini menyebabkan dua risiko:

1. sinyal muncul sebentar lalu hilang sebelum UI sempat menangkapnya;
2. signal evaluator bisa membaca kondisi intrabar yang belum final.

### Rekomendasi

Simpan dua state:

```text
closedCandles
formingCandle
```

Dan gunakan aturan:

- indikator struktur → closed candles;
- trigger realtime → forming candle;
- signal event → harus diberi timestamp + TTL;
- jangan menghapus event hanya karena candle berubah beberapa tick kemudian.

---

# 3. Threshold Scalping

## 3.1 Balanced masih terlalu ketat pada kondisi tertentu

Mode Balanced menggunakan:

- volume minimum `1.00x`;
- RSI `35..68`;
- net R:R minimum `1.20`;
- extended RSI dapat memblokir BUY;
- extreme volatility dapat memblokir BUY.

Masalah bukan angka individualnya. Masalahnya adalah angka tersebut **ditumpuk** dengan hard gate lain.

Contoh:

```text
Bias OK
Setup OK
Trigger OK
Volume OK
RR OK
RSI sedikit > 68
→ BUY = tidak boleh
```

Untuk scalping, RSI >68 tidak selalu berarti entry buruk. Dalam breakout kuat, RSI tinggi justru bisa menunjukkan momentum.

### Rekomendasi

`extended` jangan langsung membatalkan BUY.

Pisahkan:

```text
EXTENDED_PULLBACK_REQUIRED
MOMENTUM_BREAKOUT_ALLOWED
```

---

# 4. Masalah `ScalpingSensitivity`

File:

`app/src/main/java/agu/analys/config/ScalpingSensitivity.kt`

Terdapat:

- CONSERVATIVE
- BALANCED
- AGGRESSIVE
- DYNAMIC_AUTO

Tetapi evaluator hanya melakukan perlakuan khusus untuk:

```text
isAggressive = sensitivity == AGGRESSIVE
```

`DYNAMIC_AUTO` tidak mendapat engine adaptif yang nyata di evaluator tersebut.

Ini mismatch antara nama UI/config dan perilaku engine.

### Dampak

User bisa memilih:

> Adaptif Otomatis (AI)

tetapi logic masih mengikuti jalur non-aggressive.

### Rekomendasi

Buat `SensitivityProfile` runtime:

```text
SensitivityProfile(
    rsiMin,
    rsiMax,
    volumeMin,
    minRR,
    sidewaysPolicy,
    extendedPolicy,
    volatilityPolicy,
    triggerTTL
)
```

Kemudian `DYNAMIC_AUTO` benar-benar memilih profile berdasarkan regime.

---

# 5. Market Structure

`MarketStructureAnalyzer` menggunakan swing 5-candle untuk HH/HL/LH/LL.

Ini bagus untuk menghindari data palsu, tetapi secara alami memiliki **lag 2 candle** pada sisi kanan swing karena membutuhkan candle setelah swing.

Untuk swing trading ini acceptable.

Untuk scalping 1M, ini terlalu lambat jika structure digunakan sebagai hard confirmation.

Untungnya Scalping memakai structure terutama untuk `biasStrong/setupStrong`, tetapi struktur tetap berkontribusi pada skor dan quality gate.

### Rekomendasi

Gunakan dua structure layer:

```text
Confirmed Structure = 5-candle swing
Micro Structure = 2-candle / displacement + BOS
```

Micro structure khusus 1M/5M.

---

# 6. Audit Swing Mode

File:

`engine/swing/SwingEvaluator.kt`

Swing memiliki 4 checkpoint:

```text
1. Macro trend
2. Structure/support
3. Momentum/volume
4. Risk/reward
```

`step4Ok` membutuhkan seluruh checkpoint sebelumnya:

```text
step1Ok && step2Ok && step3Ok && netRr >= 1.5 && buy >= 40
```

Lalu final BUY membutuhkan:

```text
completedSteps == 4
&& buy >= 45
&& buy > sell * 1.2
```

### Temuan

Swing juga memakai model **all-or-nothing**.

Ini lebih cocok untuk high-confidence swing daripada scanner peluang.

### Rekomendasi

Tetap pertahankan strict BUY untuk Swing, tetapi tampilkan:

```text
SETUP 3/4
```

sebagai actionable watchlist.

Jangan biarkan user melihat hanya `HOLD`, karena `HOLD` mencampur:

- setup belum terbentuk;
- setup hampir valid;
- setup valid tetapi RR buruk.

---

# 7. Audit Second-Wave

`SecondWaveEvaluator` menggunakan score 0–12 dan baru qualified jika:

```text
totalScore >= 7
&& drawdownScore >= 1
&& priorRunScore >= 1
```

### Temuan

Second-Wave memiliki definisi yang jauh lebih spesifik daripada Scalping/Swing.

Khususnya:

- prior run wajib ada;
- drawdown harus berada pada zona tertentu;
- base/structure;
- volume dry-up/returning;
- flow.

Ini bukan bug. Ini memang strategi berbeda.

Namun scanner dapat melewatkan **second wave yang dangkal** karena drawdown <20% mendapat score 0.

### Rekomendasi

Jangan ubah threshold inti strategi.

Tambahkan kategori:

```text
EARLY SECOND-WAVE
QUALIFIED SECOND-WAVE
RECLAIM SECOND-WAVE
```

Dengan begitu peluang awal tidak hilang hanya karena belum memenuhi full score.

---

# 8. Backtest / Validation — Temuan Arsitektur

`WalkForwardEvaluator` membagi data:

```text
60% In-Sample
40% Out-of-Sample
```

Tetapi `BacktestEngine` bukan replika ScalpingMtfEvaluator.

Ia hanya memakai trigger sederhana:

```text
current close > EMA20
previous close <= EMA20
volume > 0
```

### Ini masalah serius

Kita tidak boleh menggunakan hasil backtest sederhana tersebut sebagai hard gate terhadap engine MTF yang jauh lebih kompleks.

Urutan yang benar:

```text
Live strategy
      ↓
StrategyBacktestAdapter
      ↓
Walk Forward
      ↓
confidence/risk adjustment
```

Bukan:

```text
Backtest strategi A
      ↓
memblokir strategi B
```

---

# 9. Testing — Coverage Tidak Membuktikan BUY Capture

Test Scalping saat ini terutama memastikan:

- result tidak null;
- bullish confidence >30;
- bearish = HOLD;
- insufficient data = null.

Test tersebut **belum membuktikan** bahwa:

```text
valid breakout → BUY
valid retest → BUY
pullback → BUY setelah trigger
sideways breakout → BUY
high RSI momentum → tidak otomatis ditolak
```

### Wajib ditambahkan

Minimal test matrix:

| Scenario | Expected |
|---|---|
| 1M bullish reclaim + H1 bullish | BUY / EARLY BUY |
| 1M breakout volume spike | BUY |
| breakout lalu candle berikutnya continuation | signal tetap aktif |
| pullback ke EMA20 lalu reclaim | BUY |
| sideways compression + breakout | BUY jika volume valid |
| RSI 70+ dengan strong breakout | boleh BUY momentum |
| WF overfit tetapi live setup kuat | BUY dengan reduced confidence, bukan veto |
| extreme volatility | risk flag / reduced size, bukan selalu HOLD |
| H1 bullish tetapi 15M belum final | EARLY/WATCH, bukan kehilangan event |

---

# 10. Prioritas Perbaikan

## P0 — Harus diperbaiki

### P0.1 Ubah `WalkForward` dari hard veto menjadi risk/confidence modifier

```text
BEFORE:
!wfReport.isOverfitted → wajib

AFTER:
isOverfitted → confidence penalty + risk multiplier
```

### P0.2 Jangan blok BUY hanya karena SIDEWAYS

Tambahkan breakout state.

### P0.3 Pisahkan forming candle dan closed candle

Ini penting untuk realtime scalping.

### P0.4 Buat trigger event TTL

Breakout/retest harus tetap aktif beberapa candle, bukan hanya `last candle == trigger`.

---

## P1 — Perbaikan berikutnya

### P1.1 H1 sebagai bias, bukan hard entry gate

### P1.2 Buat `EARLY_ENTRY` khusus scalping

### P1.3 Implementasikan `DYNAMIC_AUTO` yang benar-benar adaptif

### P1.4 Tambahkan micro-structure 1M/5M

### P1.5 Satukan engine live dan engine backtest

---

## P2 — Penguatan kualitas

### P2.1 Tambahkan telemetry signal

Setiap peluang harus dicatat:

```text
timestamp
symbol
price
mode
score
bias
setup
trigger
volumeRatio
RSI
regime
WF status
RR
reasonBlocked
```

Dengan ini kita bisa menjawab secara objektif:

> "Harga naik 4%. Kenapa aplikasi tidak BUY?"

Jawabannya harus berupa satu atau beberapa gate konkret, bukan tebakan.

### P2.2 Tambahkan signal lifecycle

```text
DETECTED
CONFIRMING
READY
TRIGGERED
EXPIRED
INVALIDATED
```

Ini jauh lebih cocok untuk trading realtime dibanding hanya:

```text
BUY / HOLD
```

---

# 11. Arsitektur Target

Arsitektur yang lebih tepat untuk Scalping:

```text
                 MARKET DATA
                     │
        ┌────────────┴────────────┐
        │                         │
   CLOSED CANDLES            LIVE CANDLE
        │                         │
        └────────────┬────────────┘
                     ↓
              REGIME ENGINE
                     ↓
              MARKET BIAS
                  (H1)
                     ↓
              SETUP ENGINE
                 (15M)
                     ↓
             MICRO STRUCTURE
                 (5M/1M)
                     ↓
             TRIGGER ENGINE
                     ↓
          ENTRY EVENT + TTL
                     ↓
          RISK / FEE / SLIPPAGE
                     ↓
             CONFIDENCE SCORE
                     ↓
       ┌─────────────┼─────────────┐
       ↓             ↓             ↓
    WATCH        EARLY BUY    CONFIRMED BUY
```

**Kunci:** risk filter tidak boleh disamakan dengan signal detector.

---

# 12. Diagnosis Final: Kenapa BUY Sering Terlewat?

Jika harus dirangkum menjadi satu kalimat:

> **Engine sekarang lebih dirancang untuk membuktikan bahwa BUY sangat aman daripada mendeteksi BUY sedini mungkin.**

Untuk Swing, karakter ini masih masuk akal.

Untuk Second-Wave, masih sesuai dengan strategi berbasis qualification.

Untuk **Scalping, ini adalah mismatch desain.**

Scalping membutuhkan:

```text
early detection
+ event memory
+ realtime trigger
+ fast invalidation
+ risk adjustment
```

Sedangkan implementasi sekarang cenderung:

```text
MTF confirmation
+ hard gates
+ historical validation gate
+ regime veto
+ current-candle trigger
```

Akibatnya peluang sering baru dianggap valid **setelah harga bergerak**, bukan saat peluang mulai terbentuk.

---

# 13. Prioritas Implementasi yang Disarankan

Urutan implementasi paling aman:

1. **Perbaiki lifecycle trigger 1M + TTL.**
2. **Pisahkan forming candle vs closed candle.**
3. **Hapus hard veto Walk-Forward.**
4. **Ubah sideways menjadi breakout-aware.**
5. **Tambahkan EARLY BUY.**
6. **Jadikan H1 bias sebagai soft gate.**
7. **Implementasikan DYNAMIC_AUTO sungguhan.**
8. **Samakan engine live dan backtest.**
9. **Tambahkan telemetry `reasonBlocked`.**
10. **Baru setelah itu tuning threshold RSI/volume/RR.**

> **Jangan langsung menurunkan semua threshold.** Itu solusi cepat tetapi berbahaya karena bisa mengubah engine menjadi signal spam. Masalah utama saat ini lebih banyak berada di **arsitektur decision flow dan event timing**, bukan sekadar angka threshold.

---

## Referensi Source yang Diaudit

- `engine/scalping/ScalpingMtfEvaluator.kt`
- `engine/scalping/FrameAnalyzer.kt`
- `engine/backtest/WalkForwardEvaluator.kt`
- `engine/backtest/BacktestEngine.kt`
- `engine/MarketStructureAnalyzer.kt`
- `engine/swing/SwingEvaluator.kt`
- `engine/secondwave/SecondWaveEvaluator.kt`
- `service/IndodaxMarketWebSocket.kt`
- `config/ScalpingSensitivity.kt`
- `test/.../ScalpingMtfEvaluatorTest.kt`

**Status audit:** `FOUND — structural causes identified; implementation fix belum dilakukan.`
