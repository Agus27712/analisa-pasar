# Laporan Verifikasi: UI Jurnal vs Candle Bursa vs Kode Engine

**Tanggal:** 2026-10-14
**Objek:** Tab **Histori Siklus Trade** (2 trade: GNS/USDT & AMP/USDT) — screenshot UI dari perangkat
**Metode:** screenshot UI ↔ candle M1 asli Tokocrypto (2.000 bar) ↔ pembacaan kode sumber
**Status:** lanjutan dari `LAPORAN-validitas-log-audit-2026-10-14.md`

---

## Ringkasan eksekutif

| # | Temuan | Bukti | Dampak |
|---|---|---|---|
| **1** | **RVOL dihitung dengan pembilang "menit yang belum selesai"** | kode + angka cocok 0,76× / 0,07× | RVOL **selalu** direndahkan 40–75% |
| **2** | RSI/MACD/EMA juga dari candle belum tutup | sweep close 0,724–0,726 mereproduksi UI | nilai log **tidak bisa direproduksi** dari data publik |
| **3** | `signalTime` = `buyTime`; `sellReason` konstanta; `isTrailingUsed` mati | terlihat di UI + kode | lag sinyal, alasan keluar, status trailing = tidak terukur |
| **4** | Peak & Drawdown ≠ ekstrem candle | GNS & AMP | metrik risiko **tidak akurat**, arah kesalahan tak konsisten |
| **5** | Label "Golden/Death Cross" + angka mentah 16 digit | terlihat di UI | salah tafsir sinyal |
| **6** | Record sampel **tidak** ada di DB ini | badge = 4, seed = 7 | risiko B2 belum terjadi (tapi masih ada) |

---

## 1. RVOL — pembilang memakai menit yang belum selesai ⚠️ **temuan utama**

### Kode
```
IndicatorMath.kt:414-423
fun relativeVolume(candles, period = 20): Double {
    val current = candles.last().volume          // <-- candle BERJALAN
    val hist    = candles.dropLast(1)            // 20 candle yang SUDAH tutup
    val avg     = hist.takeLast(period).average()
    return current / avg                         // menit sebagian ÷ menit penuh
}
```
```
CandleTimeUtil.kt:88-158  synthesizeRealtimeCandles()
   -> candle berjalan dibuat dengan volume = 0.0
   -> diisi lewat parameter `formingVolume` (LearningTradingEngine.kt:262)
ScalpingMtfEvaluator.kt:158  val rvol = IndicatorMath.relativeVolume(m1Candles, 20)
```

### Angka

| | GNS/USDT | AMP/USDT |
|---|---|---|
| Volume candle entry (menit penuh) | 58.106 | 12.135.276 |
| Rata-rata 20 candle tutup | 39.818 | 60.846.863 |
| **RVOL sebenarnya (menit penuh)** | **1,459×** | **0,199×** |
| RVOL di UI (log) | **0,76×** | **0,07×** |
| Volume menit sebagian (rekonstruksi) | 30.215 = **52%** bar | 4.247.347 = **35%** bar |
| Waktu berjalan saat sinyal | 31/60 detik = **52%** | ~45/60 detik = **75%** |

Rekonstruksi cocok: `30.215 / 39.818 = 0,759×` → UI menampilkan **0,76×**.
`4.247.347 / 60.846.863 = 0,070×` → UI menampilkan **0,07×**.

### Konsekuensi
- **RVOL direndahkan persis sebesar fraksi menit yang sudah berjalan** saat sinyal dipicu. Sinyal di detik ke-31 → RVOL terbaca ~52% dari nilai sebenarnya.
- Gate `rvolBreakoutMin = 1,2` (config Anda) jadi **tidak pernah bisa dinilai dengan benar** untuk sinyal intra-menit.

### Koreksi atas audit saya sebelumnya
> Saya pernah menulis: *"GNS entry lemah — RVOL 0,76× di bawah rata-rata, breakout di volume tipis."*

**Itu salah dan saya cabut.** RVOL sebenarnya **1,459× — di atas gate 1,2×**. Entry GNS **bukan** volume tipis. Angka 0,76× adalah artefak pengukuran, bukan kondisi pasar.

---

## 2. RSI / MACD / EMA juga dari candle yang belum tutup

### GNS — sweep `close` candle berjalan

| close berjalan | RSI | MACD hist |
|---|---|---|
| 0,7180 (open bar) | 67,41 | 3,88e-04 |
| **0,7240** | 68,53 | 7,71e-04 |
| **0,7250** | 68,71 | 8,35e-04 |
| **0,7260** | 68,89 | **8,99e-04** |
| 0,7390 (close bar penuh) | 71,02 | 1,73e-03 |

**UI menampilkan RSI 68,4 dan MACD +9,010876114987423E-4** → cocok dengan `close` ≈ **0,724–0,726**, yaitu harga tick saat sinyal, **bukan** close bar penuh (0,739).

Kalau memakai bar yang sudah tutup penuh: RSI = **71,24** (bukan 68,4), MACD = **2,17e-03** (2,4× lebih besar dari yang tercatat).

### AMP — justru cocok dengan bar yang sudah tutup

| Field | UI (log) | Candle tutup | Candle berjalan |
|---|---|---|---|
| RSI(14) | **58,8** | **58,84** ✓ | 58,84 |
| MACD hist | **-6,5614555958088215E-7** | **-6,561e-07** ✓ | -1,298e-06 |
| EMA20 | **0,000786** | **0,0007861959** ✓ | 0,0007873201 |
| EMA50 | **0,000763** | **0,00076299442** ✓ | 0,00076436719 |

MACD cocok sampai **9 digit signifikan**. Artinya basis bar berbeda antar trade.

### Kesimpulan §2
Nilai indikator di jurnal bergantung pada **(a) harga tick pada milidetik evaluasi** dan **(b) lag cache candle lokal**. Akibatnya:
- Tidak bisa direproduksi oleh pihak ketiga dari data bursa publik.
- Dua trade yang berjarak 1 jam pun memakai basis bar yang berbeda.

---

## 3. Tiga field yang terlihat salah langsung dari screenshot

| Field | GNS | AMP | Akar masalah |
|---|---|---|---|
| `Waktu Sinyal` | 13/10 **23:58** | 14/10 **00:51** | `TradeHistoryRecorder.kt:89 signalTime = now` → **identik dengan Waktu Buy** |
| `Waktu Buy` | 13/10 **23:58** | 14/10 **00:51** | — (tidak ada lag yang bisa diukur) |
| `Pemicu Jual` | `SIMULATION_SELL` | `SIMULATION_SELL` | konstanta, bukan alasan sebenarnya |
| `Trailing Stop Lock` | `NONAKTIF` | `NONAKTIF` | `TradeHistoryRecorder.kt:110 isTrailingUsed = false` (hardcode) |

Konsekuensi praktis: dari jurnal, **tidak mungkin** membedakan exit manual vs TP vs SL vs trailing — padahal itu justru variabel paling penting untuk evaluasi strategi.

---

## 4. Peak & Drawdown tidak sama dengan ekstrem candle

| | UI (jurnal) | Candle asli | Selisih |
|---|---|---|---|
| **GNS** Peak | 0,78 (**+7,6%**) | 0,789 (**+8,83%**) | **−1,23 pp** |
| **GNS** Drawdown | **−5,24%** | 0,680 (**−6,21%**) | **+0,97 pp** |
| **AMP** Peak | 0,00081 (**+1,5%**) | 0,000815 (**+2,13%**) | −0,63 pp |
| **AMP** Drawdown | **−2,76%** | 0,000776 (**−2,757%**) | ±0 (akurat) |

Kedua field berasal dari **tick yang tersampling aplikasi**, bukan dari high/low candle bursa. Untuk AMP akurat, untuk GNS tidak — **konsistensi tidak terjamin**, jadi tidak layak jadi dasar audit risiko.

> Catatan: GNS benar-benar menyentuh **−6,21%** di bawah entry (bar 00:04, low 0,680) sementara SL engine berada di 0,6789 (−6,31%). Selisihnya hanya **0,16%**. Trade ini nyaris kena stop.

---

## 5. Label & format yang menyesatkan (terlihat di UI)

1. **`MACD=Golden Cross (+9.010876114987423E-4)`** dan **`MACD=Death Cross (-6.5614555958088215E-7)`**
   - Histogram **bukan** persilangan. Logika: `hist >= 0 → "Golden Cross"`.
   - Nilai AMP **−6,6e-7** secara praktis nol, tapi dicap **"Death Cross"** (sinyal bearish) — label tegas di atas noise.
2. **Angka mentah tanpa pembulatan** — 16 digit desimal ditampilkan apa adanya.
3. **`VWAP 1M: 0.00`** (AMP) — nilai kosong dirender jadi `0.00`, bukan `-`. Terbaca seperti VWAP benar-benar nol.
4. **Dua taksonomi di satu kartu**: `STRONG ENTRY: Skor 68` **dan** `Skor 68/100 (WATCH)`.
   - `STRONG ENTRY` = **stage** (dari `ScalpingStage`)
   - `WATCH` = **score band** (`ScoreBreakdown.category`, rentang 60–74)
   - Dua sistem penilaian berbeda ditampilkan berdampingan tanpa penjelasan.

---

## 6. Soal record sampel (koreksi untuk diri sendiri)

Badge UI: **Histori Siklus Trade = 4**, **Log & Akurasi Sinyal = 5**.
Seed di kode berjumlah **7** record (`TradeHistoryRecorder.kt:371-620`).

→ Karena badge = 4, **seed tidak terpanggil** di DB ini. Kekhawatiran B2 saya **tidak terjadi di sini** — dengan asumsi 4 siklus itu memang trade nyata Anda.

Risiko tetap ada: seed menyisipkan `HIT_TP1`, `HIT_TP2`, `STOP_LOSS`, `TRAILING_STOP` + `isTrailingUsed = true`, sementara **semua trade nyata Anda menampilkan `SIMULATION_SELL` + `NONAKTIF`**. Sekali Anda menekan "Hapus Semua" lalu buka ulang app, 7 record palsu itu masuk ke tabel yang sama — dan secara visual tidak bisa dibedakan dari trade asli.

---

## 7. Jawaban atas pertanyaan audit

**1. Apakah input ke database sesuai dengan output engine?**
Sebagian. **Sesuai:** skor, regime, setup, dan teks alasan trigger memang berasal dari engine.
**Tidak sesuai:** `signalTime` (= waktu beli), `sellReason` (konstanta), `isTrailingUsed` (hardcode), `Peak`/`Drawdown` (tick tersampling), dan RSI/MACD/RVOL (basis candle belum tutup).

**2. Apakah ada kesalahan info?**
Ya — dan yang paling merusak adalah **RVOL**, karena angka yang salah itu **ikut masuk ke logika keputusan engine**, bukan cuma ke laporan.

**3. Apakah export sesuai dengan output sinyal?**
Tidak sepenuhnya — snapshot diambil saat Anda menekan order (bukan saat sinyal terbit), dan sebagian field diisi konstanta.

**4. Apakah log ini valid untuk audit?**
**Belum.** Layak untuk **anekdot per-trade** (harga beli, harga jual, durasi hold, PnL — ini semua benar). **Tidak layak** untuk win rate, expectancy, evaluasi likuiditas, atau tuning parameter.

---

## 8. Prioritas perbaikan (usulan — belum dieksekusi)

### P0 — kredibilitas data
| # | Perbaikan | Titik |
|---|---|---|
| 1 | **RVOL: selaraskan basis.** Numerator pakai candle yang sudah tutup, ATAU normalisasi volume menit berjalan (`vol / elapsedFraction`) | `IndicatorMath.kt:414` |
| 2 | Tandai/normalisasi seluruh indikator agar basisnya satu bar yang jelas; simpan juga `barTimestamp` + `isClosed` di log | `ScalpingMtfEvaluator.kt:158` |
| 3 | `signalTime` dari engine (waktu sinyal terbit), bukan `now` | `TradeHistoryRecorder.kt:89` |
| 4 | `sellReason` tulis alasan sebenarnya (MANUAL / TP1 / TP2 / SL / TRAIL / TIME_STOP) | jalur simulasi sell |
| 5 | `isTrailingUsed` dari state mesin exit, bukan `false` hardcode | `TradeHistoryRecorder.kt:110` |
| 6 | `Peak`/`Drawdown` ambil dari candle bursa (high/low), atau beri label eksplisit "dari tick" | `SimulationTradeModels` |

### P1 — keterbacaan & kesalahan tafsir
| # | Perbaikan |
|---|---|
| 7 | Ganti label `Golden Cross`/`Death Cross` → `Histogram > 0` / `< 0` |
| 8 | Format angka: 4–6 desimal signifikan, bukan `%E` mentah |
| 9 | `VWAP 1M` kosong → tampilkan `-`, bukan `0.00` |
| 10 | Pisahkan penamaan **stage** dan **score band** agar tidak tertukar di satu kartu |

### P2 — integritas sampel
| # | Perbaikan |
|---|---|
| 11 | Tambah flag `isSample` pada record seed + penanda visual di UI + kecualikan dari statistik |

---

## Lampiran — data mentah

### Jejak M1 GNS/USDT (WIB)

```
23:56  O=0.695  H=0.706  L=0.693  C=0.702  V=17,376
23:57  O=0.704  H=0.730  L=0.702  C=0.720  V=58,738
23:58  O=0.718  H=0.739  L=0.715  C=0.739  V=58,106   <== ENTRY 23:58:31 @ 0.725
23:59  O=0.739  H=0.739  L=0.725  C=0.736  V=61,761
00:00  O=0.736  H=0.789  L=0.727  C=0.776  V=166,657  <== puncak candle 0.789
00:01  O=0.774  H=0.777  L=0.748  C=0.748  V=70,127
00:02  O=0.749  H=0.768  L=0.734  C=0.756  V=50,384
00:03  O=0.757  H=0.757  L=0.723  C=0.726  V=55,148
00:04  O=0.726  H=0.730  L=0.680  C=0.700  V=97,669   <== dasar candle 0.680
00:05  O=0.699  H=0.742  L=0.694  C=0.731  V=56,458
00:06  O=0.731  H=0.750  L=0.727  C=0.735  V=36,537
00:07  O=0.738  H=0.763  L=0.733  C=0.763  V=102,718  <== EXIT 00:07:55 @ 0.758
```

### Jejak M1 AMP/USDT (WIB)

```
00:49  O=0.000791  H=0.000806  L=0.000783  C=0.000803  V=21,711,105
00:50  O=0.000801  H=0.000815  L=0.000798  C=0.000798  V=26,948,112
00:51  O=0.000798  H=0.000803  L=0.000792  C=0.000802  V=12,135,276  <== ENTRY 00:51:45 @ 0.000798
00:52  O=0.000801  H=0.000805  L=0.000798  C=0.000803  V=4,459,517
00:53  O=0.000801  H=0.000808  L=0.000791  C=0.000797  V=27,802,598
00:54  O=0.000799  H=0.000807  L=0.000799  C=0.000804  V=6,391,316
00:55  O=0.000802  H=0.000803  L=0.000792  C=0.000799  V=23,817,555
00:56  O=0.000800  H=0.000801  L=0.000785  C=0.000787  V=34,570,394
00:57  O=0.000787  H=0.000787  L=0.000779  C=0.000783  V=35,137,999
00:58  O=0.000784  H=0.000795  L=0.000781  C=0.000789  V=27,033,113
00:59  O=0.000789  H=0.000791  L=0.000778  C=0.000781  V=17,039,480
01:00  O=0.000781  H=0.000786  L=0.000776  C=0.000784  V=23,486,577  <== dasar candle 0.000776
01:01  O=0.000785  H=0.000799  L=0.000785  C=0.000799  V=22,887,600
01:02  O=0.000796  H=0.000815  L=0.000796  C=0.000813  V=41,390,863  <== EXIT 01:02:47 @ 0.00081
```
