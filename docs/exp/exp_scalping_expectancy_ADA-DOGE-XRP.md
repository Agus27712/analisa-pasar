# Eksperimen: Win Rate Engine Scalping pada Geometri TP Kecil

**Tanggal:** 2026-10-19
**Tujuan:** menguji secara terukur apakah engine scalping bisa mencapai win rate yang diperlukan
pada geometri scalping sungguhan (SL kecil, TP kecil, horizon pendek).
**Data:** `data/tokocrypto_v4` — 87.000 bar M1 per pair, 2026-08-12 s.d. 2026-10-12 (60 hari).
**Pairs:** ADAUSDT, DOGEUSDT, XRPUSDT.
**Alat:** `tools/scalp_replay.py` (versi FEE_TRUTH v1.1) + analisis lanjutan trade UNRESOLVED
di atas data M1 yang sama.

> **Catatan parity 2026-10-20.** Angka di laporan ini diukur sebelum replay disamakan dengan
> app: belum ada time-stop CLASSIC 90 bar, S1 masih meloloskan breakout tanpa RVOL, dan S9 /
> `max_risk_atr` belum dipulihkan. App menutup CLASSIC pada 90 bar, jadi hasil 42,7% yang
> menunggu TP/SL hingga 8 jam **bukan win rate app**. Angka ini tetap sah sebagai eksperimen
> geometri pada harness lama.
>
> **Sudah dijalankan ulang dengan parity (2026-10-20):** win rate **14,88%**, avg R
> **−0,4898**, 963 dari 1.149 trade keluar lewat TIMEOUT. Laporan baru:
> `exp_scalping_expectancy_parity_ADA-DOGE-XRP.md`.

---

## 1. Ringkasan

| Angka | Nilai |
|---|---|
| Trade selesai (3 pair) | **349** |
| Win rate (horizon 8 jam) | **42,7%** |
| Win rate (+ lanjutan 48 jam) | **45,2%** |
| Ambang impas geometri ini | **60,1%** |
| Expectancy | **−0,25R** per trade |
| Gap yang harus ditutup | **14,9 poin persen** |

**Kesimpulan satu kalimat:** geometri scalping reachable memang bisa menghasilkan trade yang
selesai, tetapi entry engine hanya akurat 42,7% — jauh di bawah 60,1% yang dibutuhkan, dan gap
itu **tidak bisa ditutup dengan mengubah horizon atau parameter risiko**.

---

## 2. Latar belakang: masalah yang solicitado

Pemilik menandai (red flag) bahwa target TP2 ≈ 14,5% yang muncul pada konfigurasi default
(`min_friction_multiple = 6`) mustahil untuk mode scalping, dan bahwa standar scalping
menggunakan TP kecil: TP1 ~1%, TP2 >1%, SL <2% dari entry.

Pengukuran pada data M1 riil (60 hari, BTCUSDT) mengonfirmasi hal itu:

| Level | BTCUSDT salah satu arah, 8 jam |
|---:|---:|
| 2,0% | 14,6% |
| 5,81% (SL default sekarang) | 0,8% |
| 14,5% (TP2 default sekarang) | 0,0% |

Karena itu eksperimen ini memakai **satu geometri scalping**, bukan konfigurasi default.

---

## 3. Geometri eksperimen

Diturunkan dari rumus engine `ScalpingRiskEngine.kt:139`: `TP2 = k × (SL + F) + F`, dengan
`F = 0,9688%` (Tokocrypto taker USDT, FEE_TRUTH §8).

| Parameter | Nilai | Alasan |
|---|---:|---|
| `min_friction_multiple` | 2,0 | SL = 2 × F = **1,9376%** — versi longgar dari default 6× |
| `target_net_rr` (k) | 0,5 | TP2 = 0,5 × 2,9064 + 0,9688 = **2,4220%** — target scalping |
| `min_net_rr` | 0,5 | harus ikut k, kalau tidak semua sinyal ditolak gerbang |
| `lookahead_bars` | 480 | 8 jam — horizon scalp |
| `slippage_pct` | 0,08 | base case FEE_TRUTH §3 |
| fee | taker | jalur market; knob `order_type` dihapus 2026-10-20 |

**Identitas matematis penting.** Rumus impas (FEE_TRUTH §0) dengan `p = (SL+F)/(SL+TP2)`,
disubstitusi dengan rumus engine menghasilkan:

> **p_impas = 1 / (1 + k)**

Jadi win rate yang dibutuhkan **tidak bergantung pada SL maupun besarnya fee** — hanya pada
`target_net_rr` (k). Fee hanya menggeser *jarak harga* yang harus dicapai, bukan *tingkat
ketepatan* yang harus dimiliki. Untuk k = 0,5 → p_impas = 66,7% (dalam satuan gross);
dalam satuan replay (1R = SL+F) Threshold-nya **60,1%**.

---

## 4. Mengapa pair ini dipilih

Bukan tebakan — diukur dari volatilitas dan tren riil pada dataset 60 hari:

| Pair | Drift total 60 hari | P(gerak ≥ 2,45% dlm 8 jam) |
|---|---:|---:|
| **ADAUSDT** | +37,5% | **52,5%** |
| DOGEUSDT | +23,2% | 38,3% |
| **XRPUSDT** | +39,3% | **37,2%** |
| SOLUSDT | +47,7% | 28,5% |
| ETHUSDT | +34,5% | 19,4% |
| BNBUSDT | +23,4% | 12,5% |
| BTCUSDT | +31,8% | 9,5% |
| TRXUSDT | −1,7% | 0,2% |

Tiga pair teratas dipilih agar trade punya peluang selesai dalam horizon 8 jam. TRX tidak
lolos karena tidak tren sama sekali.

---

## 5. Hasil utama

| Pair | Sinyal ready | Trade selesai | Win | Loss | Win rate | Avg R | Floor aktif |
|---|---:|---:|---:|---:|---:|---:|---:|
| ADAUSDT | 201 | 134 | 57 | 77 | 42,5% | −0,292 | 59,7% |
| DOGEUSDT | 170 | 98 | 42 | 56 | 42,9% | −0,287 | 58,2% |
| XRPUSDT | 191 | 117 | 50 | 67 | 42,7% | −0,288 | 53,0% |
| **Gabungan** | **562** | **349** | **149** | **200** | **42,7%** | **−0,289** | — |

Konsistensi antar pair sangat tinggi (42,5–42,9%) — ini bukan noise sample kecil, ini sifat
entry engine.

**Rata-rata per hasil:** WIN = **+0,665R**, LOSS = **−1,000R** → ambang impas **60,1%**.

---

## 6. Analisis trade UNRESOLVED (hipotesis "tren berlanjut")

Hipotesis awal: trade yang belum selesai dalam 8 jam mungkinbias ke arah tren berlanjut
(artinya win rate sebenarnya lebih tinggi dari yang terlihat). **Hipotesis ini dibantah.**

Metode: untuk setiap trade UNRESOLVED, telusuri bar M1 setelah jendela 8 jam, maks 48 jam,
dengan aturan resolusi sama seperti replay (bar yang menyentuh SL dan TP sekaligus dihitung
kalah — konservatif).

| Pair | UNRESOLVED | ↓ jadi WIN | ↓ jadi LOSS | Masih terbuka >48 jam | Median waktu ke WIN |
|---|---:|---:|---:|---:|---:|
| ADAUSDT | 67 | 30 | 36 | 1 | 5,4 jam |
| DOGEUSDT | 72 | 31 | 29 | 12 | 11,9 jam |
| XRPUSDT | 74 | 31 | 27 | 16 | 7,9 jam |
| **Total** | **213** | **92** | **92** | **29** | — |

**92 menang vs 92 kalah** — sepersis. Tidak ada bias tren sama sekali.

Win rate setelah ditambah trade yang akhirnya selesai dalam 48 jam:

| Pair | 8 jam | +48 jam |
|---|---:|---:|
| ADAUSDT | 42,5% | 43,5% |
| DOGEUSDT | 42,9% | 46,2% |
| XRPUSDT | 42,7% | 46,3% |
| **Gabungan** | **42,7%** | **45,2%** |

Artinya: menambah waktu hanya menaikkan **2,5 poin persen** — jauh dari yang diharapkan.

---

## 7. Expectancy vs ambang

| Skenario | Win rate | Expectancy |
|---|---:|---:|
| Terukur, horizon 8 jam | 42,7% | **−0,289R** |
| Terukur, + lanjutan 48 jam | 45,2% | **−0,247R** |
| Ambang impas | 60,1% | 0,000R |
| Target pemilik | 72,5% | +0,207R |

Gap: **14,9 poin persen**. Ini jarak **akurasi entry**, bukan jarak parameter.

---

## 8. Batasan eksperimen (apa yang TIDAK dibuktikan)

1. **Satu config, tiga pair, 60 hari.** Pola (42,7%) konsisten, tapi cakupannya sempit.
2. **Target terdekat.** Akurasi 42,7% diukur pada TP2 2,42% — target *paling mudah*.
   Menaikkan TP2 ke 5% biasanya **menurunkan** win rate, bukan menaikkannya. Jadi
   estimasi "k = 1,34 mungkin unbiased" dari analisis awal adalah **terlalu optimistis**
   dan belum diuji.
3. **Data historis, bukan paper trade aplikasi.** Replay memakai quirk porting yang
   disetel agar sama dengan aplikasi, tapi tetap bukan data eksekusi nyata.
4. **Horizon replay yang dipakai pendek sekali** sehingga TP2 14,5% terlihat mustahil —
   tapi itu konfigurasi **berbeda**, bukan bukti bahwa konfigurasi default salah.
5. **Belum ada time-stop 90 bar.** App CLASSIC menutup posisi pada 90 bar M1; harness yang
   dipakai di sini menunggu hingga 480 bar. Karena itu 349 trade "selesai" tidak setara dengan
   exit app, dan 213 UNRESOLVED tidak akan sebanyak itu bila dijalankan dengan parity sekarang.
   Pengulangan parity sudah dilakukan: `exp_scalping_expectancy_parity_ADA-DOGE-XRP.md`.

---

## 9. Keputusan yang dibutuhkan

Tiga arah, tidak satu pun yang sudah diuji:

| Opsi | Isi | Risiko |
|---|---|---|
| (a) Akhiri program scalping | Akui engine ini bukan alat scalping di Tokocrypto (F ≈ 1%) | effort scalp dibuang |
| (b) Ubah framing ke swing | k = 2, SL ~2%, TP2 ~4,9%, horizon 24 jam — satu-satunya zona yang belum terbukti negatif | Perlu replay horizon 24 jam |
| (c) Perbaiki evaluator dulu | Kejar win rate ≥ 60% pada jarak scalping | Proyek riset, tidak pasti tercapai |

**Rekomendasi penulis: (b) sebagai langkah berikutnya yang murah** — satu replay tambahan
(lookahead 1440) akan memberi tahu apakah opsi (a) atau (b) yang benar, sebelum ada
pengeluaran waktu lebih lanjut.

---

## Lampiran — perintah yang dijalankan

```bash
# 1. Config scalping (berbeda dari data/config/scalp_config.json hanya pada 4 baris)
#    min_friction_multiple: 2.0, target_net_rr: 0.5, min_net_rr: 0.5, lookahead_bars: 480

# 2. Replay 3 pair ( ganti ADAUSDT -> DOGEUSDT, lalu XRPUSDT )
python3 tools/scalp_replay.py --pair ADAUSDT --data data/tokocrypto_v4 \
  --assume-orderbook-ok --config cfg_scalping.json --out ADAUSDT.json

# 3. Analisis UNRESOLVED: telusuri bar M1 setelah jendela 8 jam, maks 48 jam,
#    aturan resolusi sama dengan replay (SL dahulu kalau satu bar Sentuh dua sisi)
```

**Referensi angka yang sama di dokumen lain:**
- `workplan2.md` §60.5 — replay default pra-parity mencatat 0 trade selesai; setelah time-stop
  90 bar dipasang, 350 trade selesai (semua TIMEOUT).
- `workplan2.md` §61 — ringkasan eksperimen ini.
- `FEE_TRUTH.md` §6 — catatan bahwa tabel backtest lama tidak dapat direproduksi.

---

## Cara mengulang eksperimen (catatan untuk agen berikutnya)

Semua angka di dokumen ini **diukur**, bukan karangan. Kalau Anda mengulang:
- stream replay butuh ~10 menit per pair di Android/CI, jadi jalankan 3 pair paralel.
- angka volatilitas & reachability (Bagian 2 & 4) dihitung langsung dari CSV M1,
  bukan dari output replay.
- jangan menulis ulang hasil tanpa mengulang pengukuran — kalau angkanya beda,
  catat perbedaannya di sini.