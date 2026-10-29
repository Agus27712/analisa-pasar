# Eksperimen: entri "beli di bawah, jual di atas" (mean reversion) — 26 pair

**Status: GAGAL** — tidak lolos kriteria kandidat (`docs/AGENTS.md`: net/trade > 0, lebih baik
dari pembanding di semua pair, CI 95% tidak menyentuh nol, ≥60 trade/pair, dan bertahan di data
di luar sampel). Arah "beli di bawah" **terukur lebih baik** dari beli di puncak pada exit yang
sama, tetapi hasilnya **tidak bertahan** di 40% data terakhir dan tidak memenuhi ambang sampel.
**Jangan di-port ke app.**

**Tanggal:** 2026-10-29
**Tujuan:** menguji sisi entry yang belum pernah diukur di repo ini. Eksperimen
`exp_entry_price_percentile_ADA-DOGE-XRP.md` membuktikan entry engine lama khas berada di
**persentil 93** harga 1 hari. Eksperimen ini membalik tesisnya: beli saat harga **murah**
(oversold/koreksi), lalu jual di atas (EMA20/EMA50/band atas Bollinger/puncak 7 hari).

**Data:** `data/tokocrypto_v8` — 26 pair USDT Tokocrypto. Tiga timeframe diuji terpisah:
H1 4.999 bar (208 hari), H4 4.999 bar (833 hari), D1 2.205 bar (6,04 tahun).
Rezim pasar dari `BTCUSDT_1d.csv` (close D1 vs EMA200 D1, 3.360 bar, 2017-08-17 s/d 2026-10-28).

**Alat:** `tools/entry_buy_low_lab.py` (baru). Hanya membaca CSV; tidak menyentuh app, config,
atau gate engine.

**Perintah (yang menghasilkan angka di laporan ini):**

```sh
python3 tools/entry_buy_low_lab.py --tf 1h --slim \
  --out data/hasil/exp_buy_low_entry_v8_1h_regime.json
python3 tools/entry_buy_low_lab.py --tf 4h --walk-forward 6 --slim \
  --out data/hasil/exp_buy_low_entry_v8_4h_regime.json
python3 tools/entry_buy_low_lab.py --tf 1d --slim \
  --out data/hasil/exp_buy_low_entry_v8_1d_regime.json
# pembanding urutan intrabar (optimistis)
python3 tools/entry_buy_low_lab.py --tf 4h --same-bar tp_first --slim \
  --cells "dip_rsi25|atr3.0/5.0|18,dip_rsi25|atr3.0/3.0|18,control_top_rsi70|atr3.0/5.0|18,control_random|atr3.0/5.0|18,dip_rsi25|atr1.5/2.0|18,control_random|atr1.5/2.0|18" \
  --out data/hasil/exp_buy_low_entry_v8_4h_tp_first.json
```

**Biaya:** friksi all-in Tokocrypto USDT **0,9688% per trade round-trip** (fee 0,4044% × 2 +
slippage 0,08% × 2), sumber `FEE_TRUTH.md` v1.1. Semua angka "net" di bawah sudah dipotong
friksi ini.

---

## 1. Ringkasan

| Pertanyaan | Jawaban terukur |
|---|---|
| Apakah beli-bawah mengalahkan beli-di-puncak pada exit yang sama (H4)? | **Ya.** `dip_rsi25` **+1,39%**/trade vs kontrol beli-puncak **+0,35%** vs entri acak **−0,68%** (exit `atr3.0/5.0`, hold 18 bar) |
| Seberapa murah entry-nya dibanding engine lama? | Median entry `dip_rsi25` di **persentil 0** dari 168 bar terakhir; **0%** trade masuk di persentil ≥80. Engine lama: persentil 93 |
| Lolos uji di luar sampel (40% data terakhir)? | **Tidak.** Train +2,94% → test **−0,89%** |
| H1 (208 hari, 26 pair) bisa menutup friksi? | **Tidak satu sel pun.** Sel terbaik +1,07% dengan CI 95% masih menyentuh nol |
| D1 (6 tahun) positif? | **Ya secara rata-rata** (+3,19%/trade, target EMA20, hold 21) tetapi hanya **200 trade untuk 26 pair** dan test +0,92% |
| Memenuhi kriteria kandidat repo? | **Tidak.** Tidak ada pair yang mencapai 60 trade; sampel terbesar 36 trade (ETHUSDT) |
| Ada sel yang layak di-port? | **Tidak ada.** |

**Kesimpulan satu baris:** hipotesis "beli di bawah" **benar arah** tetapi **belum terbukti
menguntungkan secara tahan lama**; hasilnya bergantung rezim (bull +2,81% vs bear +0,26%) dan
seluruh keuntungan H4 hilang di periode bear 2026.

---

## 2. Metode

- **11 hipotesis beli-bawah + 2 kontrol.** Kontrol wajib: `control_top_rsi70` (beli saat
  RSI14 > 70 — kebalikan tesis) dan `control_random` (entri acak ber-seed, jumlah sinyal
  disamakan dengan median sinyal hipotesis nyata).
- **10 exit, 3 hold per timeframe.** Tiga keluarga exit: (a) SL/TP kelipatan ATR
  (1,5/2,0; 3,0/3,0; 3,0/5,0), (b) SL/TP persen tetap (3/3, 4/5, 6/8), (c) **exit berbasis
  target**: TP = EMA20, EMA50, band atas Bollinger, atau puncak 168 bar; SL = 3–4 ATR lebar.
- **Kenapa exit berbasis target, bukan SL/TP rapat.** Uji pertama alat ini memakai SL/TP ATR
  rapat; hasilnya gross semua hipotesis ≈ 0,00% karena stop rapat kena noise (median hold 5 bar).
  Itu mengukur geometri stop, bukan tesis beli-bawah. Karena itu exit target ditambahkan dan
  menjadi inti pengukuran.
- **Urutan eksekusi:** sinyal dihitung di close bar `i`, entry di **open bar `i+1`**, satu posisi
  per pair (non-overlap), sinyal saat posisi terbuka dilewati.
- **Split 60/40** per pair berdasarkan indeks bar; **walk-forward 6 jendela** berurutan untuk H4.
- **CI 95%** bootstrap 600 ulangan, seed tetap `20261029`.
- **Filter sadar-friksi** (`--min-tp-multiple 2.0`): trade dengan TP < 2 × friksi (1,94%)
  **dilewati**, bukan dipaksakan. Efeknya terukur dan hanya besar di H1:
  di `dip_rsi25|atr1.5/2.0|24` **457 dari 1.428 sinyal (32,0%)** dibuang; `atr3.0/3.0` 248;
  `atr3.0/5.0` 122. Di H4 filter ini hampir tidak menggigit (`atr3.0/5.0` membuang **0**).
- **Urutan intrabar tidak diketahui dari OHLC.** Dua batas diukur: `sl_first` (pesimistis,
  bawaan) dan `tp_first` (optimistis). Hasil H4 utama **identik sampai 4 desimal**
  (`dip_rsi25|atr3.0/5.0|18`: +1,3854% di kedua batas) karena TP (median 16,1%) jauh lebih lebar
  dari SL (median 9,7%), sehingga kejadian SL dan TP tersentuh di bar yang sama langka.
  Selisih terbesar yang terukur: `atr3.0/3.0|18` +1,3161 → +1,3397 (**+0,024 poin persen**).
- **Rezim:** label bull/bear dari BTC D1 close vs EMA200 D1 pada waktu entry, tanpa lookahead
  (EMA hanya memakai data sampai bar itu).

---

## 3. Hasil H4 — ini inti eksperimen

### 3.1 Semua hipotesis pada exit yang sama (`atr3.0/5.0`, hold 18 bar = 3 hari)

26 pair, 833 hari/pair (2024-07-18 s/d 2026-10-29), `sl_first`.

| Hipotesis | n | net/trade | CI 95% | WR | Pair positif | Train 60% | Test 40% |
|---|---:|---:|---|---:|---|---:|---:|
| **`dip_rsi25`** (RSI14 < 25) | 636 | **+1,39%** | [+0,69; +2,19] | 57,1% | **21/24** | +2,94% | **−0,89%** |
| `control_top_rsi70` (beli di puncak) | 1.348 | +0,35% | [−0,16; +0,88] | 46,3% | 16/26 | +0,30% | +0,41% |
| `control_random` (acak ber-seed) | 2.628 | **−0,68%** | [−1,00; −0,38] | 40,8% | 5/26 | −0,65% | −0,72% |
| `dip_rsi35_trend` | 266 | −0,45% | [−1,41; +0,67] | 44,7% | 4/14 | −0,08% | −1,06% |
| `dip_rsi30_trend` | 75 | −0,66% | [−2,93; +1,52] | 41,3% | 0/0 | +0,56% | −2,13% |
| `dip_ema20_min3pct` | 774 | −0,24% | [−0,88; +0,56] | 44,7% | 9/22 | −0,16% | −0,42% |
| `pullback_ema20_uptrend` | 2.226 | −0,79% | [−1,15; −0,44] | 38,1% | 2/26 | −0,66% | −0,99% |
| `pullback_ema50_uptrend` | 1.600 | −0,46% | [−0,86; −0,03] | 40,5% | 4/26 | −0,39% | −0,57% |
| `bb_lower_touch` | 3.642 | −0,94% | [−1,18; −0,70] | 42,4% | 2/26 | −0,86% | −1,04% |
| `drawdown5pct_7d` | 3.518 | −0,85% | [−1,10; −0,56] | 42,9% | 1/26 | −0,78% | −0,94% |
| `vwap_dev_1_5sd` | 3.611 | −0,90% | [−1,15; −0,66] | 41,8% | 2/26 | −0,96% | −0,83% |
| `range_bottom20` | 10 | +3,40% | — | 70,0% | 0/0 | — | — |

Catatan: `range_bottom20` hanya 10 trade — **tidak boleh** dikutip sebagai temuan. Hipotesis
"beli saat koreksi" yang populer (`bb_lower_touch`, `drawdown5pct_7d`, `vwap_dev_1_5sd`) justru
**rugi** dan jumlahnya ribuan trade, jadi kegagalan itu bukan soal sampel.

`dip_rsi25` adalah **satu-satunya** hipotesis beli-bawah yang positif, dan kemenangannya atas
kontrol jelas: **+2,07 poin persen** di atas entri acak dan **+1,04 poin persen** di atas beli
di puncak.

### 3.2 Sensitivitas exit

| Sel | n | net/trade | CI 95% | Pair positif | Train | Test |
|---|---:|---:|---|---:|---:|---:|
| `dip_rsi25\|atr3.0/5.0\|18` | 636 | **+1,39%** | [+0,69; +2,19] | 21/24 | +2,94% | −0,89% |
| `dip_rsi25\|atr3.0/3.0\|18` | 631 | +1,32% | [+0,61; +2,09] | 19/24 | +2,94% | −1,08% |
| `dip_rsi25\|atr3.0/5.0\|6` | 718 | +0,55% | [−0,03; +1,07] | 14/25 | +1,58% | −0,93% |
| `dip_rsi25\|atr1.5/2.0\|18` | 803 | +0,26% | [−0,20; +0,77] | 14/25 | +1,36% | −1,23% |
| `dip_rsi25\|tgt_ema50_sl4\|84` | 535 | +0,55% | [−0,58; +1,68] | 16/24 | +2,51% | −2,52% |
| `dip_rsi25\|tgt_ema20_sl3\|84` | 613 | +0,31% | [−0,57; +1,24] | 16/24 | +1,91% | −2,11% |

Pola yang konsisten: **semua** sel `dip_rsi25` di H4 positif di train dan **negatif di test**.
Itu bukan pilihan exit yang salah; itu perubahan rezim.

### 3.3 Per rezim BTC

| Sel | Bull (BTC > EMA200 D1) | Bear (BTC < EMA200 D1) |
|---|---|---|
| `dip_rsi25\|atr3.0/5.0\|18` | **+2,81%** (n=281) | +0,26% (n=355) |
| `dip_rsi25\|atr3.0/3.0\|18` | +3,05% (n=277) | −0,04% (n=354) |
| `control_top_rsi70\|atr3.0/5.0\|18` | +0,60% (n=913) | −0,17% (n=435) |
| `control_random\|atr3.0/5.0\|18` | −0,38% (n=1.465) | −1,05% (n=1.163) |

Di rezim bear, `dip_rsi25` masih positif tetapi tipis: **+0,26%** vs **−1,05%** pada entri
acak. Angka itu juga **tidak stabil antar subperiode** — di paruh test, trade ber-rezim bear
justru **−1,14%** (n=235) walau rata-rata seluruh sampel bear +0,26% (n=355). Jadi label rezim
saja tidak cukup menjelaskan hasil; yang berubah adalah perilaku pasar 2026.

### 3.4 Walk-forward 6 jendela (833 hari/pair dibagi 6)

| Jendela | Rentang | n | net/trade | Rezim isi |
|---|---|---:|---:|---|
| W1 | 2024-07-18 .. 2024-12-03 | 39 | +2,69% | bull 27 / bear 12 |
| W2 | 2024-12-03 .. 2025-04-21 | 188 | +2,53% | bull 121 / bear 67 |
| W3 | 2025-04-21 .. 2025-09-07 | 60 | +6,20% | bull 57 / bear 3 |
| W4 | 2025-09-07 .. 2026-01-24 | 137 | +1,18% | bull 53 / bear 84 |
| W5 | 2026-01-24 .. 2026-06-12 | 168 | **−1,79%** | **bear 168 / bull 0** |
| W6 | 2026-06-12 .. 2026-10-29 | 44 | +1,53% | bull 23 / bear 21 |

**5 dari 6 jendela positif**, dengan catatan penting: W1 dan W6 hanya 39 dan 44 trade, dan
**satu-satunya jendela negatif adalah jendela yang seluruhnya bear**. Pasar bear 2026 itu nyata,
bukan ujung data: BTC D1 di bawah EMA200 pada **180 dari 181 hari** di semester pertama 2026.

### 3.5 Per pair (`dip_rsi25|atr3.0/5.0|18`)

| Pair | n | net/trade | Median | WR | MFE | MAE | Median TP | Median SL |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| HBARUSDT | 20 | +5,26% | +4,27% | 75,0% | 9,7% | −3,4% | 14,3% | 8,6% |
| WLDUSDT | 26 | +4,09% | +4,41% | 73,1% | 10,9% | −3,9% | 21,3% | 12,8% |
| LINKUSDT | 26 | +2,78% | +3,04% | 65,4% | 7,5% | −3,9% | 15,7% | 9,4% |
| ARBUSDT | 30 | +2,61% | +2,83% | 60,0% | 9,1% | −4,2% | 18,4% | 11,0% |
| TAOUSDT | 26 | +2,50% | +0,09% | 53,9% | 10,5% | −5,7% | 19,4% | 11,6% |
| AVAXUSDT | 26 | +2,38% | +2,46% | 65,4% | 7,8% | −4,0% | 16,8% | 10,1% |
| AAVEUSDT | 26 | +2,35% | +0,99% | 57,7% | 7,6% | −4,1% | 18,9% | 11,3% |
| FETUSDT | 33 | +1,71% | +2,77% | 60,6% | 7,3% | −3,9% | 19,3% | 11,6% |
| QNTUSDT | 17 | +1,69% | +0,92% | 58,8% | 8,8% | −3,7% | 14,3% | 8,6% |
| ADAUSDT | 26 | +1,57% | +1,48% | 61,5% | 6,1% | −5,7% | 17,3% | 10,4% |
| DOGEUSDT | 30 | +1,56% | +3,06% | 60,0% | 8,3% | −4,2% | 15,8% | 9,5% |
| NEARUSDT | 22 | +1,53% | +3,41% | 63,6% | 8,4% | −4,6% | 18,0% | 10,8% |
| SUIUSDT | 29 | +1,39% | +0,31% | 55,2% | 8,5% | −4,2% | 17,9% | 10,8% |
| LTCUSDT | 23 | +1,12% | +0,83% | 56,5% | 6,2% | −2,7% | 13,6% | 8,1% |
| XRPUSDT | 27 | +0,89% | +0,62% | 55,6% | 6,8% | −5,0% | 14,5% | 8,7% |
| TRXUSDT | 23 | +0,84% | −0,04% | 47,8% | **2,3%** | −1,7% | **5,3%** | 3,2% |
| UNIUSDT | 33 | +0,74% | +0,50% | 57,6% | 5,7% | −4,5% | 15,7% | 9,4% |
| ZECUSDT | 21 | +0,43% | −2,27% | 42,9% | 10,9% | −7,1% | 21,3% | 12,8% |
| PEPEUSDT | 15 | +0,30% | −0,55% | 40,0% | 4,2% | −4,6% | 16,6% | 9,9% |
| BTCUSDT | 30 | +0,11% | +0,67% | 60,0% | 3,1% | −1,9% | 7,8% | 4,7% |
| ENAUSDT | 24 | +0,10% | +2,83% | 54,2% | 8,9% | −6,2% | 22,1% | 13,2% |
| ETHUSDT | 36 | −0,02% | −0,26% | 50,0% | 5,2% | −5,2% | 11,7% | 7,0% |
| SOLUSDT | 30 | −0,24% | −1,06% | 46,7% | 4,6% | −5,4% | 15,0% | 9,0% |
| BNBUSDT | 26 | −0,37% | +0,88% | 53,9% | 4,4% | −2,2% | 10,1% | 6,1% |

CHIPUSDT (n=4) dan BANKUSDT (n=7) tidak masuk hitungan pair. **21 dari 24 pair positif**, tetapi
**tidak satu pair pun mencapai 60 trade** — sampel terbesar ETHUSDT baru 36 trade. Ini sendiri
sudah menggugurkan kriteria kandidat.

### 3.6 Bagaimana 636 trade itu keluar

| Jalur keluar | Jumlah | Porsi |
|---|---:|---:|
| TIMEOUT (18 bar habis) | 413 | 64,9% |
| TP (5 ATR) | 75 | 11,8% |
| SL (3 ATR) | 148 | 23,3% |

Median MFE **+6,79%**, median MAE **−4,20%**, tetapi net median hanya **+1,27%**. Artinya:
trade khas sempat naik 6,8% dan hanya **±19%** dari MFE itu yang jadi uang. Sebagian besar trade
**tidak mencapai target** dan keluar karena waktu habis — jadi hasil ini bukan bukti "target
tercapai", melainkan bukti "berada di sisi beli saat pasar naik selama 3 hari".

---

## 4. Hasil H1 — friksi menang

H1: 26 pair × 4.999 bar (208 hari), grid 360 sel (12 hipotesis × 10 exit × 3 hold). Sel dengan
net tertinggi di seluruh grid H1 adalah `dip_ema20_min3pct|tgt_swinghi7d_sl4|168` **+1,35%**
(n=152), dan CI 95%-nya tetap menyentuh nol. Sel yang paling relevan:

| Sel | n | net/trade | CI 95% | WR | Pair positif | Train | Test |
|---|---:|---:|---|---:|---:|---:|---:|
| `dip_ema20_min3pct\|tgt_bbupper_sl3\|168` | 179 | **+1,07%** | [−0,75; +3,35] | 50,3% | 6/8 | +1,10% | +1,05% |
| `dip_ema20_min3pct\|tgt_swinghi7d_sl4\|168` | 152 | +1,35% | [−0,92; +4,07] | 46,7% | 4/7 | +1,60% | +1,18% |
| `dip_rsi25\|atr3.0/3.0\|24` | 458 | −0,35% | [−0,70; +0,03] | 51,3% | 7/24 | −0,67% | +0,08% |
| `dip_rsi25\|atr1.5/2.0\|24` | 449 | −0,68% | [−1,00; −0,37] | 48,5% | 4/21 | −0,85% | −0,47% |
| `dip_rsi25\|atr3.0/5.0\|168` | 480 | −0,78% | [−1,30; −0,21] | 39,2% | 8/24 | −1,26% | −0,10% |
| `dip_rsi25\|tgt_bbupper_sl3\|168` | 485 | −1,07% | [−1,63; −0,52] | 33,2% | 6/25 | −1,56% | −0,37% |
| `control_top_rsi70\|atr3.0/3.0\|24` | 1.292 | −0,56% | [−0,84; −0,32] | 48,0% | 1/25 | −0,90% | −0,24% |
| `control_random\|atr3.0/3.0\|24` | 2.085 | −0,96% | [−1,14; −0,76] | 40,3% | 0/25 | −1,11% | −0,75% |

Bacaan:

1. **Arah beli-bawah tetap menang** (`dip_rsi25` −0,35 vs acak −0,96 vs beli-puncak −0,56)
   tetapi semuanya **negatif**: gross `dip_rsi25|atr3.0/3.0|24` hanya **+0,62%**, sedangkan
   friksi 0,9688%. Di H1 friksi lebih besar dari seluruh pergerakan yang bisa ditangkap.
2. `dip_ema20_min3pct` di H1 **bukan** RSI: entry di **persentil 72**, bukan persentil 0. Ia
   menang karena menahan jauh lebih lama (median hold 33 bar, target band atas) dan punya MFE
   median 11,0%, bukan karena "membeli sangat murah".
3. Sel itu juga **tidak lolos**: CI 95% menyentuh nol dan hanya **8 pair** yang punya ≥10 trade
   (179 trade dari 26 pair yang diuji).
4. Filter sadar-friksi di H1 membuang 32% sinyal pada ATR 1,5/2,0. Artinya: pada timeframe ini,
   sebagian besar sinyal secara matematis **tidak mungkin** menutup biaya.

---

## 5. Hasil D1 — positif di rata-rata, tetapi tidak bisa dipakai sebagai bukti

D1: 26 pair × 2.205 bar (6,04 tahun). Exit target hold 21 bar (21 hari):

| Sel | n | net/trade | CI 95% | Median | WR | Pair positif | Train | Test |
|---|---:|---:|---|---:|---:|---:|---:|---:|
| `dip_rsi25\|tgt_ema20_sl3\|21` | 200 | **+3,19%** | [+0,31; +6,29] | +3,32% | 56,0% | 7/9 | +4,79% | +0,92% |
| `dip_rsi25\|tgt_swinghi7d_sl4\|21` | 193 | +3,57% | [+0,29; +7,01] | +2,11% | 53,9% | 5/8 | +6,54% | −0,89% |
| `dip_rsi25\|tgt_bbupper_sl3\|21` | 200 | +2,92% | [−0,25; +5,96] | +2,04% | 54,5% | 6/9 | +5,56% | −0,80% |
| `dip_rsi25\|tgt_ema50_sl4\|21` | 193 | +2,90% | [−0,05; +5,74] | +2,16% | 53,9% | 5/8 | +5,34% | −0,78% |
| `dip_rsi25\|atr3.0/5.0\|7` | 204 | +1,55% | [−0,50; +3,52] | +2,52% | 59,8% | 2/7 | +3,54% | −1,02% |
| `control_top_rsi70\|atr3.0/5.0\|7` | 1.030 | **+2,60%** | [+1,57; +3,66] | **−0,28%** | 49,5% | 17/23 | +2,96% | +2,10% |
| `control_top_rsi70\|tgt_bbupper_sl3\|21` | 675 | −0,63% | [−1,35; +0,14] | +2,35% | 81,5% | 10/22 | −0,66% | −0,59% |
| `control_top_rsi70\|tgt_swinghi7d_sl4\|21` | 1.204 | +0,75% | [−0,20; +1,75] | +2,66% | 72,7% | 12/23 | +1,09% | +0,27% |
| `control_random\|tgt_bbupper_sl3\|21` | 950 | −1,00% | [−1,95; +0,05] | +1,74% | 55,4% | 10/25 | −0,16% | −2,08% |
| `control_random\|tgt_swinghi7d_sl4\|21` | 978 | +0,50% | [−1,00; +1,86] | −0,93% | 48,0% | 15/25 | +1,82% | −1,20% |

Bacaan yang harus dijaga:

1. **Pada exit yang sama dengan target**, `dip_rsi25` menang jauh: +2,92% vs beli-puncak −0,63%
   vs acak −1,00% (`tgt_bbupper_sl3|21`), dan +3,57% vs +0,75% vs +0,50% (`tgt_swinghi7d_sl4|21`).
2. **Tetapi kontrol negatif yang sama juga untung** pada exit ATR (`control_top_rsi70|atr3.0/5.0|7`
   **+2,60%**, CI 95% seluruhnya di atas nol, 17/23 pair positif). Pada exit itu beli di puncak
   **mengalahkan** beli di bawah (+2,60 vs +1,55). Penyebabnya terlihat di kolom median: median
   kontrol itu **−0,28%** — rata-ratanya dinaikkan segelintir ekor kanan. Jadi rata-rata D1
   **tidak bisa** dipakai sebagai bukti keunggulan beli-bawah.
3. Sampel D1 sangat tipis: **200 trade untuk 26 pair** (7,7/pair) dalam 6 tahun, hanya 9 pair
   dengan ≥10 trade. Median TP 28,6% dan SL 25% menunjukkan trade ini berbulan-bulan.
4. `dip_rsi25|tgt_ema20_sl3|21` satu-satunya sel D1 yang test-nya tetap positif (+0,92%), tetapi
   turun dari +4,79% dan CI-nya melebar [+0,31; +6,29].

---

## 6. Apa yang benar-benar terbukti, dan apa yang tidak

**Terbukti (terukur):**

1. **Arah entry beli-bawah memang lebih baik daripada beli-di-puncak** pada exit yang identik.
   H4: +1,39 vs +0,35 vs −0,68 (636/1.348/2.628 trade). H1: −0,35 vs −0,56 vs −0,96. Ini
   menjawab keluhan "beli di pucuk" secara langsung di dua horizon.
2. **Entry `dip_rsi25` benar-benar murah:** median persentil 0 dari 168 bar, 0% trade di
   persentil ≥80. Bandingkan engine lama: median persentil 93,3 (`exp_entry_price_percentile_*`).
3. **Friksi 0,9688% adalah lantai, bukan detail.** Di H1, gross `dip_ema20_min3pct|tgt_bbupper_sl3|168`
   hanya +2,04% → net +1,07%, dan gross `dip_rsi25|atr3.0/3.0|24` +0,62% → net −0,35%. Artinya
   trade H1 harus menangkap ±1% bersih dulu, dan hampir tidak ada sel yang bisa.
4. **Keuntungan H4 terkonsentrasi di rezim bull.** Bull +2,81% (n=281) vs bear +0,26% (n=355);
   seluruh keuntungan train hilang di test (−0,89%) karena test memuat pasar bear 2026.

**Tidak terbukti / tidak boleh diklaim:**

1. **Bukan strategi yang lolos uji luar sampel.** H4 test −0,89%; D1 turun dari +4,79% ke +0,92%;
   H1 sel terbaik CI-nya menyentuh nol.
2. **Bukan cukup sampel.** Tidak ada pair dengan ≥60 trade; maksimum 36 (ETHUSDT, H4).
3. **Bukan "exit sudah terpecahkan".** 64,9% trade H4 keluar TIMEOUT; hasil ini soal waktu dan
   rezim, bukan ketepatan target.
4. **Bukan bukti bahwa beli-bawah selalu menang.** Di D1 pada exit ATR, beli di puncak menang.
5. **Bukan hasil yang boleh dipakai untuk uang riil.** Sama seperti semua temuan engine
   sebelumnya: riset offline.

**Pembanding pasif.** Angka di laporan ini hanya bisa dibandingkan dengan pembanding yang
**diukur di repo**: `classic` P0 **−0,1569 R/trade** (`exp_p0_gatekeeper_*`). Pembanding pasif
seperti bunga produk stablecoin di bursa (Flexible/Locked/Earn) **belum diukur di repo ini** dan
tidak boleh dikutip dari laporan ini sebagai hasil eksperimen; kalau owner ingin memakainya
sebagai pembanding, ia perlu diukur terpisah (suku bunga berubah, ada risiko bursa, dan tidak ada
risiko pasar).

---

## 7. Batasan eksperimen (wajib dibaca sebelum memakai angka)

- **Bar OHLC, bukan tick.** Entry memakai open bar berikutnya; SL/TP dievaluasi pada high/low bar.
  Urutan intrabar tidak diketahui — dua batas dilaporkan, selisih terukur ≤0,024 poin persen
  pada sel utama, sehingga bukan sumber utama hasil.
- **Slippage diasumsikan 0,08% per sisi, tetap.** Tidak diuji slippage lebih buruk, spread
  melebar saat panik, atau kegagalan order. Justru saat `dip_rsi25` aktif (pasar jatuh) slippage
  cenderung lebih buruk dari asumsi.
- **Satu posisi per pair, tanpa short, tanpa leverage, tanpa pendanaan.** Tidak ada biaya
  transfer, penarikan, atau pajak.
- **Universum 26 pair adalah daftar pair Tokocrypto hari ini.** Pair yang sudah delisting tidak
  masuk, jadi ada bias survivorship ringan.
- **H4 hanya 833 hari/pair.** Itu memuat satu pasar bull (2024-2025) dan satu bear (2026).
  Kesimpulan "hanya bekerja di bull" berdiri di atas dua contoh rezim, bukan statistik rezim.
- **Regime memakai BTC saja.** Altcoin bisa bear walau BTC bull; label ini penyederhanaan.
- **D1 6 tahun tetapi sampel trade tipis** (200 trade/26 pair) karena sinyal RSI<25 jarang.
- **Filter `--min-tp-multiple 2.0` adalah pilihan parameter**, bukan hukum. Di H1 filter ini
  membuang 32% sinyal; kalau dimatikan, angka H1 akan berubah (lebih banyak trade dengan TP di
  bawah biaya).
- **Tidak diuji:** cross-exchange spread/transfer, staking, futures, grid/limit order bertingkat,
  ukuran posisi dinamis, dan pemilihan pair otomatis.
- **Tidak ada perubahan app, config, atau gate.** `scalp_config.json`, `IntradayConfig`, dan gate
  P0 tidak disentuh.

---

## 8. Eksperimen terkait

- `exp_entry_price_percentile_ADA-DOGE-XRP.md` — asal keluhan "beli di pucuk": entry engine lama
  di persentil 93,3. Laporan ini menguji sisi kebalikannya.
- `exp_p0_gatekeeper_ADA-DOGE-XRP.md` — baseline pembanding `classic` (620 trade, WR 14,84%,
  avg R −0,1569).
- `exp_exit_net_tp_ADA-DOGE-XRP.md` dan `exp_exit_mode_sweep_ADA-DOGE-XRP.md` — bukti exit-only
  gagal (0/88 varian, 0/90 sel). Laporan ini menjelaskan **kenapa** exit tidak menyelamatkan:
  entry lama membeli di harga mahal, dan entry murah pun belum cukup.
- `exp_friction_multiple_ADA-DOGE-XRP.md` — lantai friksi bukan penyebab utama di engine lama;
  laporan ini menunjukkan friksi tetap menjadi lantai keras di horizon pendek.

---

## 9. Riwayat

| Tanggal | Perubahan |
|---|---|
| 2026-10-29 | Eksperimen dijalankan (H1/H4/D1, 11 hipotesis + 2 kontrol, 10 exit, split 60/40, walk-forward 6 jendela, dua batas intrabar). Status **GAGAL**: tidak lolos uji luar sampel, sampel per pair <60, H1 di bawah friksi. Tidak ada perubahan kode app. |
