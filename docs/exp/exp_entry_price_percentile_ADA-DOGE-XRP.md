# Eksperimen: persentil harga entri P0 (1/3/7 hari)

**Tanggal:** 2026-10-24
**Tujuan:** menjawab keluhan owner "beli di pucuk". Untuk setiap entri non-overlap yang
dipilih engine, ukur posisi harga entri dalam rentang 1/3/7 hari terakhir dan return maju.
**Data:** `data/tokocrypto_v4` — ADAUSDT, DOGEUSDT, XRPUSDT; 87.000 bar M1 per pair,
2026-08-12 s/d 2026-10-12 (60,4 hari).
**Sampel:** 620 entri `classic` hold 90m (sama dengan kontrol replay P0).
**Alat:** `tools/entry_percentile.py` (baru). Hanya membaca data dan cache kandidat P0;
tidak mengubah gate, exit, atau config.

---

## 1. Ringkasan

| Pertanyaan | Jawaban terukur (gabungan 620 entri) |
|---|---|
| Harga entri ada di persentil berapa dalam 1 hari terakhir? | **median 93,3** (75,6% entri di kuintil teratas) |
| Posisi dalam rentang high-low 1 hari? | **median 81,7%** dari low ke high |
| Persentil 3 hari | median **84,6** |
| Persentil 7 hari | median **82,7** |
| Return close 1 hari ke depan | median **−0,108%** |
| Return close 3 hari ke depan | median **−0,051%** |
| Return close 7 hari ke depan | median **+1,148%** |
| Kuintil teratas, return 7 hari | median **−2,596%** vs kuintil terbawah **+8,259%** (n=293 vs 43) |

**Kesimpulan:** keluhan "beli di pucuk" **terkonfirmasi secara posisi** — entry P0 khas
berada di 93 persentil hari sebelumnya dan 82% dari rentang harian. Efeknya pada return
maju pendek tipis negatif (1 hari −0,11%), lalu positif di 7 hari karena tren berlanjut.
Angka ini menjelaskan mengapa scalp 90 menit sulit: entry membeli kekuatan yang sudah
terlanjur naik, sementara friksi 0,9688% harus dibayar lebih dulu.

---

## 2. Metode

- Entri = close bar sinyal, non-overlap, dipilih `classic` hold 90m (620 trade).
- **Persentil**: fraksi close pada jendela 1/3/7 hari terakhir yang ≤ harga entri. Nilai 100
  berarti entry adalah close tertinggi di jendela. Jendela dianggap cukup bila memuat
  minimal 80% bar yang diminta.
- **Posisi rentang**: `(entry − low) / (high − low) × 100` pada jendela yang sama.
- **Return maju**: close pada `i + hari × 1440`, plus MFE/MAE kotor di dalam jendela.
- Return maju hanya dihitung bila jendela depan penuh; karena itu n berkurang di ujung data
  dan di awal data (jendela belakang belum penuh).

---

## 3. Hasil per pair

| Pair | Jendela | n | Median persentil | Kuintil atas | Kuintil bawah | Posisi rentang | Return close | MFE | MAE |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| ADAUSDT | 1 hari | 210 | 93,86 | 80,5% | 0,0% | 83,6% | −0,088% | +3,351% | −2,833% |
| ADAUSDT | 3 hari | 206 | 91,00 | 63,1% | 0,0% | 76,2% | +0,591% | +6,151% | −4,859% |
| ADAUSDT | 7 hari | 203 | 91,72 | 61,6% | 8,4% | 77,5% | +1,916% | +12,494% | −6,500% |
| DOGEUSDT | 1 hari | 193 | 92,71 | 72,5% | 0,0% | 81,3% | −0,250% | +1,934% | −1,996% |
| DOGEUSDT | 3 hari | 189 | 81,25 | 51,3% | 1,6% | 65,0% | −0,360% | +4,608% | −4,131% |
| DOGEUSDT | 7 hari | 176 | 79,79 | 49,4% | 11,4% | 67,0% | +2,285% | +11,173% | −6,094% |
| XRPUSDT | 1 hari | 217 | 93,33 | 73,7% | 0,5% | 80,7% | +0,064% | +2,050% | −1,581% |
| XRPUSDT | 3 hari | 211 | 77,94 | 47,4% | 2,4% | 59,8% | −0,189% | +5,645% | −3,656% |
| XRPUSDT | 7 hari | 201 | 76,29 | 46,8% | 10,4% | 55,7% | +0,541% | +7,414% | −5,539% |

Ketiga pair menunjukkan pola yang sama: persentil 1 hari sangat tinggi (92–94), lalu
menurun untuk jendela lebih panjang (XRP paling tidak ekstrem).

---

## 4. Return maju per kuintil persentil (gabungan 620 entri)

| Jendela | Kuintil | n | Median return close | % positif | Median MFE | Median MAE |
|---|---:|---:|---:|---:|---:|---:|
| 1 hari | 0–20 | 1 | +0,409% | 100,0% | +0,409% | 0,000% |
| 1 hari | 20–40 | 16 | +0,473% | 68,8% | +1,178% | −0,704% |
| 1 hari | 40–60 | 46 | −0,473% | 34,8% | +0,943% | −1,129% |
| 1 hari | 60–80 | 85 | −0,602% | 32,9% | +1,585% | −1,287% |
| 1 hari | 80–100 | 466 | +0,264% | 52,4% | +2,829% | −2,270% |
| 3 hari | 0–20 | 8 | −0,163% | 37,5% | +3,380% | −3,203% |
| 3 hari | 20–40 | 44 | +0,918% | 59,1% | +3,553% | −3,105% |
| 3 hari | 40–60 | 97 | +0,369% | 53,6% | +4,944% | −3,708% |
| 3 hari | 60–80 | 104 | −1,509% | 40,4% | +6,063% | −4,419% |
| 3 hari | 80–100 | 320 | +0,640% | 52,5% | +6,607% | −4,338% |
| 7 hari | 0–20 | 43 | +8,259% | 97,7% | +14,968% | −5,034% |
| 7 hari | 20–40 | 100 | +3,684% | 76,0% | +11,983% | −5,723% |
| 7 hari | 40–60 | 52 | −0,916% | 40,4% | +9,311% | −6,136% |
| 7 hari | 60–80 | 44 | −4,715% | 31,8% | +7,447% | −6,674% |
| 7 hari | 80–100 | 293 | −2,596% | 39,9% | +9,908% | −6,394% |

**Pembacaan yang hati-hati:**

1. Pada 1 hari, kuintil teratas justru tidak paling buruk (+0,264%), tetapi kelompok 40–80
   negatif. Persentil tinggi bukan sinyal jual jangka pendek yang bersih.
2. Pada 7 hari, kuintil teratas negatif (−2,596%) sementara kuintil terbawah sangat positif
   (+8,259%). Sampel kuintil terbawah hanya 43 dan terkonsentrasi di periode/rezim tertentu;
   jangan membacanya sebagai aturan umum.
3. Persentil tinggi di sini sebagian mekanis: dalam tren naik, close terbaru hampir selalu
   yang tertinggi. Karena itu angka ini **menggambarkan posisi entry**, bukan bukti kausal
   bahwa entry menyebabkan kerugian.

---

## 5. Implikasi

- Keluhan owner benar untuk dimensi posisi: engine membeli setelah harga naik, bukan di
  diskon. Ini konsisten dengan watak momentum engine.
- Namun "beli di pucuk" bukan bug gate yang bisa diperbaiki dengan satu filter persentil:
  return 1 hari kuintil teratas masih +0,264%, dan 7 hari positif untuk sebagian entry.
  Filter persentil akan mengubah komposisi entry, bukan otomatis menciptakan edge.
- Untuk scalp 90 menit, friksi tetap masalah utama: median MFE 90 menit +0,506% < 0,9688%.
  Entry yang lebih rendah akan membantu, tetapi harus diukur sebagai perubahan entry, bukan
  sebagai exit.

**Keputusan:** tidak ada perubahan gate/exit/app. Diagnostik ini bahan diskusi owner, bukan
rekomendasi trading.

---

## 6. Batas eksperimen

- 3 pair, 60,4 hari, satu rezim pasar; jendela return maju saling tumpang tindih.
- Persentil memakai close; entry sebenarnya adalah close bar sinyal. Slippage/fee tidak
  dimasukkan ke return maju (angka kotor).
- Kuintil bawah 7 hari kecil (n=43) dan bias periode; jangan mengutipnya tanpa konteks.
- Cache kandidat sama dengan eksperimen exit; kontrol 620/14,84/−0,1569 sudah diverifikasi.

---

## 7. Verifikasi

- `python3 tools/test_scalp_replay_parity.py` → **30 test OK**.
- Perintah:

```bash
python3 tools/entry_percentile.py \
  --pairs ADAUSDT,DOGEUSDT,XRPUSDT --data data/tokocrypto_v4 \
  --config data/config/scalp_config.json \
  --cache-dir /tmp/friction_sweep_cache \
  --out data/hasil/exp_entry_price_percentile.json
```
