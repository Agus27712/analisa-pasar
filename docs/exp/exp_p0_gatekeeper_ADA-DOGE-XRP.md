# Eksperimen: Gatekeeper P0 (MTF, BTC, Regime, TTL) pada ADA-DOGE-XRP

**Tanggal:** 2026-10-22
**Tujuan:** mengukur dampak sinkronisasi P0 KriptoYoi pada engine scalping yang sudah ada:
gate MTF 4 TF, veto BTC, regime OR, dan TTL 5 candle M1.
**Data:** `data/tokocrypto_v4` — 87.000 bar M1 per pair, **2026-08-12 14:50 UTC s/d
2026-10-12 00:49 UTC** (±60,4 hari). BTC dari `BTCUSDT_1m.csv` (87.000 bar M1).
**Pair:** ADAUSDT, DOGEUSDT, XRPUSDT.
**Alat:** `tools/scalp_replay.py`.
**Pembanding:** `tools/scalp_replay.py` dan `data/config/scalp_config.json` pada commit
**`2dd69ad`** (keadaan sebelum P0), dijalankan dengan berkas yang sama dari git.

---

## 1. Ringkasan

| Angka | Baseline (`2dd69ad`) | P0 | Selisih |
|---|---:|---:|---:|
| Trade selesai | **996** | **620** | **−376 (−37,8%)** |
| Menang / kalah | 128 / 868 | 92 / 528 | −36 / −340 |
| Win rate | **12,85%** | **14,84%** | **+1,99 poin persen** |
| Avg R | **−0,1642** | **−0,1569** | **+0,0073R** |
| Profit factor | 0,141 | 0,172 | +0,031 |
| Max DD rata-rata per pair | 54,51R | 32,42R | −22,09R |
| Exit TIMEOUT | 990 | **616** | −374 |
| Exit SL | 6 | **4** | −2 |
| Exit TP1/TP2 | **0** | **0** | 0 |

**Kesimpulan:** gate P0 **mengurangi frekuensi** dan sedikit memperbaiki win rate serta avg R,
tetapi **tidak menghasilkan edge**. Avg R masih **−0,1569R** dan **616 dari 620 trade (99,4%)
keluar karena time-stop 90 menit**, bukan TP. Tidak ada satu pun trade yang menyentuh TP1/TP2
pada kedua konfigurasi. Perbaikan +0,0073R berada di dalam rentang noise: interval keyakinan 95%
baseline **(−0,1776, −0,1508)** dan P0 **(−0,1748, −0,1389)** saling tumpang tindih.

---

## 2. Yang diukur

Config P0 (dibaca `tools/scalp_replay.py` dari `data/config/scalp_config.json`):

| Parameter | Nilai | Arti |
|---|---:|---|
| `ttl_bars_m1` | 5 | umur setup 5 candle M1 di app |
| `mtf_min_bullish` | 3 | minimal 3 dari 4 TF bullish |
| `mtf_block_high_tf_bearish` | true | H1/M15 bearish memblokir LONG |
| `btc_veto_enabled` | true | veto BTC aktif |
| `btc_return_veto_pct` | −0,6 | return 5M terburuk di bawah −0,6% |
| `btc_atr_ratio_veto` | 2,5 | ATR ratio BTC di atas 2,5× |
| `btc_ema_period` | 20 | EMA 5M |
| `regime_adx_trending` | 25,0 | ambang tren kuat |
| `regime_adx_weak` | 20,0 | ambang ADX lemah |
| `regime_chop_ranging` | 61,8 | CHOP tinggi = ranging |
| `regime_chop_trend_max` | 55,0 | batas CHOP untuk tren |

Parameter ekonomi tidak diubah: `min_friction_multiple` **6,0**, `target_net_rr` **2,0**,
`min_net_rr` **1,15**, `slippage_pct` **0,08**, fee Tokocrypto USDT taker **0,4044%** per sisi.
Konsekuensinya risiko kotor rata-rata **5,813%**, TP1 **+7,99%** (≈1,375R kotor), TP2
**+14,53%** (≈2,5R kotor), dan time-stop **90 bar M1**.

Gate MTF mensyaratkan **4 TF diketahui** (fail-closed). H1/M15 tidak boleh bearish; M5 dan M1
boleh pullback selama total bullish minimal 3/4. Gerbang ditempatkan setelah Step4 supaya audit
tidak menyalahkan R:R ketika yang menolak adalah konteks pasar.

Veto BTC memakai candle 5M/1M yang **sudah tutup**: close 5M di bawah EMA20 dan return terburuk
dua candle < −0,6%, atau ATR ratio > 2,5× dengan minimal 2 dari 3 candle bearish. Pair BTC
sendiri tidak diveto.

---

## 3. Hasil per pair

| Pair | Baseline trade | P0 trade | Baseline WR | P0 WR | Baseline avg R | P0 avg R | Δ avg R |
|---|---:|---:|---:|---:|---:|---:|---:|
| ADAUSDT | 343 | 210 | 15,45% | 19,52% | −0,1646 | −0,1568 | +0,0078 |
| DOGEUSDT | 318 | 193 | 10,69% | 11,92% | −0,1706 | −0,1552 | +0,0154 |
| XRPUSDT | 335 | 217 | 12,24% | 12,90% | −0,1576 | −0,1584 | −0,0008 |
| **Gabungan** | **996** | **620** | **12,85%** | **14,84%** | **−0,1642** | **−0,1569** | **+0,0073** |

Perbaikan **tidak konsisten**: XRP justru sedikit lebih buruk. Ini bukan tanda gate rusak,
melainkan bukti bahwa sampel 60 hari dan tiga pair tidak cukup untuk menyimpulkan edge.

### 3.1 Semua trade keluar karena waktu

| Exit | Baseline | P0 |
|---|---:|---:|
| TIMEOUT (90 bar) | 990 | 616 |
| SL | 6 | 4 |
| TP1 | 0 | 0 |
| TP2 | 0 | 0 |
| EARLY_PROFIT / BE / TRAIL | 0 | 0 |

Dengan geometri saat ini, TP1 berjarak **+7,99%** dari entry dan time-stop hanya **90 menit**.
Pada 60 hari dan tiga pair, **tidak ada** trade yang mencapainya. Artinya:

1. Win rate di laporan ini **bukan** peluang menyentuh TP. Menang/kalah ditentukan oleh tanda
   P&L saat time-stop — 92 kemenangan P0 semuanya TIMEOUT dengan profit kecil.
2. Rata-rata menang **+0,2194R** dan rata-rata kalah **−0,2224R** (payoff **0,986**). Dengan
   payoff itu, impas butuh win rate **≈50,3%**, bukan 14,84%.
3. Gate entry tidak bisa memperbaiki masalah exit. Ini konsisten dengan status owner:
   **slider entry sudah ada, exit strategi belum.**

---

## 4. Hitungan gate

| Pair | Bar Step4 ditolak MTF | Bar Step4 ditolak BTC | Pemeriksaan BTC (per menit) | Pemeriksaan dengan veto BTC |
|---|---:|---:|---:|---:|
| ADAUSDT | 1.249 | 14 | 66.991 | 421 |
| DOGEUSDT | 1.088 | 4 | 68.493 | 364 |
| XRPUSDT | 1.188 | 25 | 66.409 | 375 |
| **Total** | **3.525** | **43** | **201.893** | **1.160** |

**Cara membaca:** angka gate adalah **bar sinyal siap (Step4 lolos)**, bukan jumlah trade yang
hilang. Replay memegang satu posisi sampai exit, jadi ratusan bar yang ditolak gate bisa berada
di dalam satu jendela posisi yang sama. Trade yang benar-benar hilang adalah 376 (tabel §3).
Kolom BTC menghitung **pemeriksaan per menit** saat replay mengevaluasi bar, bukan candle 5M:
veto dihitung ulang tiap menit dari jendela 5M yang sudah tutup.

Veto BTC aktif pada 1.160 dari 201.893 pemeriksaan (**0,57%**). Pada jendela ini BTC naik,
sehingga gate BTC hampir tidak pernah mengubah keputusan. MTF adalah gate yang dominan.

---

## 5. Ablasi ADAUSDT

Semua baris memakai kode P0 dan config yang sama; hanya dua sakelar gate yang diubah.

| Varian | Trade | Menang | WR | Avg R | Max DD | Exit |
|---|---:|---:|---:|---:|---:|---|
| Baseline `2dd69ad` | 343 | 53 | 15,45% | −0,1646 | 56,47R | 342 TIMEOUT, 1 SL |
| `regime_only` (MTF 0, BTC off) | 347 | 58 | 16,71% | −0,1622 | 56,27R | 346 TIMEOUT, 1 SL |
| `btc_only` (MTF 0, BTC on) | 346 | 59 | 17,05% | −0,1623 | 56,17R | 345 TIMEOUT, 1 SL |
| `mtf_only` (MTF 3, BTC off) | 210 | 41 | 19,52% | −0,1560 | 32,77R | 209 TIMEOUT, 1 SL |
| `full` (MTF 3, BTC on) | 210 | 41 | 19,52% | −0,1568 | 32,93R | 209 TIMEOUT, 1 SL |

Pembacaan:

- **Regime OR saja** menambah 4 trade dan +0,0024R dibanding baseline. Itu noise, bukan edge.
- **MTF saja** memotong 137 trade dari `regime_only`, menaikkan WR +2,81 poin persen dan avg R
  +0,0062R. Gate ini yang paling banyak menyaring, tetapi hasilnya tetap negatif.
- **BTC saja** hanya mengubah 1 trade dibanding `regime_only` dan avg R bergerak −0,0001R.
  Pada jendela ini veto BTC praktis netral; jangan mengklaim BTC memperbaiki hasil.
- **Full vs MTF saja**: jumlah trade sama, avg R −0,0008R. BTC memblokir satu trade yang
  kebetulan menang. Sekali lagi: sampel terlalu kecil untuk menyimpulkan arah.

---

## 6. Batasan yang wajib dibaca

1. **TTL tidak diuji oleh replay ini.** Replay masuk posisi pada bar sinyal; TTL mengatur berapa
   lama setup boleh menunggu sebelum entry di app. Angka trade di sini **tidak membuktikan**
   manfaat TTL. TTL baru terbukti lewat paper/observasi app, bukan harness ini.
2. **M5 disintesis dari M1** (grid UTC), bukan data M5 langsung dari bursa. Gate M5 karena itu
   mengukur struktur yang sama dengan M1 yang diagregasi, bukan data pasar M5 independen.
3. **BTC memakai candle yang sudah tutup** dari CSV, sama seperti app membuang candle berjalan.
   Namun app mengambil BTC dari jaringan; waktu tutup dan kualitas data bisa berbeda.
4. **Orderbook diasumsikan tersedia** (`--assume-orderbook-ok`). Step2 lolos tanpa data
   orderbook nyata; hasil ini tidak mengukur kualitas fill atau slippage nyata.
5. **Satu jendela data, tiga pair, fee Tokocrypto USDT taker.** Pair IDR, Indodax, dan mode exit
   lain tidak diuji.
6. **Win rate bukan hit rate TP.** 99,4% trade P0 berakhir TIMEOUT; menang berarti profit kecil
   saat time-stop. Jangan membandingkan angka ini dengan laporan yang punya TP2 tercapai
   (mis. `exp_scalping_expectancy_parity_ADA-DOGE-XRP.md`, avg R −0,4898), karena geometri
   exit-nya berbeda.
7. **Angka gate adalah bar, bukan trade.** Jangan mengutip 3.525 sebagai "trade yang diselamatkan
   gate".

---

## 7. Verifikasi kode

| Pemeriksaan | Hasil |
|---|---|
| `./gradlew :app:testDebugUnitTest` | **104 kelas, 797 test, 0 gagal, 1 dilewati** |
| `python3 tools/test_scalp_replay_parity.py` | **18 test, OK** |
| `python3 tools/check_md_text.py` | dijalankan sebelum commit |

Test Kotlin baru: `SignalTtlTest`, `MtfAlignmentPolicyTest`, `BtcPulseAnalyzerTest`; test lama
yang diperbarui: `SignalLifecycleManagerTest`, `ScalpingMtfEvaluatorTest`,
`ScalpingMtfEvaluatorAuditTest`, `ScalpingRuntimeConfigTest`, `MarketRegimeEngineTest`,
`ModeSignalEvaluatorTest`, `ScalpingSignalFieldsTest`.

---

## 8. Perintah reproduksi

Baseline (kode dan config sebelum P0, commit `2dd69ad`):

```sh
git show 2dd69ad:tools/scalp_replay.py > /tmp/scalp_replay_baseline.py
git show 2dd69ad:data/config/scalp_config.json > /tmp/scalp_config_baseline.json
for p in ADAUSDT DOGEUSDT XRPUSDT; do
  python3 /tmp/scalp_replay_baseline.py \
    --pair "$p" --data data/tokocrypto_v4 \
    --config /tmp/scalp_config_baseline.json \
    --assume-orderbook-ok --out "/tmp/p0-baseline/$p.json"
done
```

P0 (kode dan config di branch `patch` setelah perubahan ini):

```sh
for p in ADAUSDT DOGEUSDT XRPUSDT; do
  python3 tools/scalp_replay.py \
    --pair "$p" --data data/tokocrypto_v4 \
    --config data/config/scalp_config.json \
    --assume-orderbook-ok --out "/tmp/p0-after/$p.json"
done
```

Ablasi ADA: salin `data/config/scalp_config.json`, ubah `mtf_min_bullish` dan
`btc_veto_enabled` sesuai tabel §5, lalu jalankan perintah yang sama dengan `--config` berkas
salinan.

BTC otomatis dicari di `--data` (`BTCUSDT_1m.csv`, atau `BTCIDR_1m.csv` untuk pair IDR). Bila
tidak ada, output menulis `btc_source.available=false` dan kolom veto **tidak mengukur apa pun**.
Berkas BTC yang disebut eksplisit tetapi tidak ada akan **gagal**, bukan diam-diam dimatikan.

---

## 9. Keputusan

1. P0 **tetap dipertahankan** karena aturannya benar dan gate-nya bekerja seperti yang diminta;
   ia bukan sumber kerugian.
2. **Jangan promosikan ke uang riil.** Avg R masih negatif dan seluruh exit bertumpu pada
   time-stop.
3. Prioritas berikutnya **bukan menambah gate entry**, melainkan **exit strategi**: TP1/TP2 saat
   ini tidak pernah tercapai dalam 90 menit. Setiap perubahan exit harus diukur ulang dengan
   tabel yang sama, termasuk jumlah TIMEOUT dan trade UNRESOLVED.
4. TTL perlu diuji di paper/app, bukan diklaim dari replay.
