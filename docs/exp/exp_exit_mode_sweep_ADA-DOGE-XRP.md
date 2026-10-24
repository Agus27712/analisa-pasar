# Eksperimen: sweep 5 mode exit pada sinyal P0

**Tanggal:** 2026-10-24
**Tujuan:** mengukur apakah salah satu geometri exit yang sudah ada (`classic`, `tp1_be`,
`early`, `trail`, `full`) memperbaiki **net %** pada entry P0. Gate entry, fee, dan lantai
friksi tidak diubah.
**Data:** `data/tokocrypto_v4` — ADAUSDT, DOGEUSDT, XRPUSDT; 87.000 bar M1 per pair,
2026-08-12 s/d 2026-10-12 (60,4 hari); BTC `BTCUSDT_1m.csv`.
**Alat:** `tools/exit_mode_sweep.py` (baru). Kandidat gate dievaluasi sekali lalu dipakai
ulang untuk semua mode. Cache P0 yang sudah ada dipakai; kontrol identik membuktikan isinya
sama. Alat mendukung `--rebuild` untuk membangun cache dari nol.
**Config:** `data/config/scalp_config.json` (fee USDT, `min_friction_multiple = 6,0`,
`target_net_rr = 2,0`, exit default).

---

## 1. Ringkasan

| Pertanyaan | Jawaban terukur |
|---|---|
| Ada mode exit yang positif? | **Tidak. 0 dari 90 sel pair × mode × hold** (net %/trade). |
| Ada sel agregat yang positif? | **Tidak. 0 dari 30 sel agregat** (2 metode × 5 mode × 3 hold). |
| Sel terbaik | `early` 420m, XRPUSDT: **−0,666%/trade** (masih rugi). |
| Perbaikan terbesar vs classic | `tp1_be` 240m: −0,7756 vs −0,8420 net/trade (same-entry), tetapi net/hari tetap **−1,54%**. |
| Win rate bisa naik? | Ya: `trail` 420m mencapai WR 39,92% (same-entry) dan 44,99% (sequential), tetapi net/trade tetap negatif. |
| Keputusan | Tidak ada kandidat yang lolos kriteria. Tidak ada perubahan app/config. |

Kontrol `classic` 90m wajib dan terukur identik CLI: **620 trade / WR 14,84% / avg R −0,1569**.

---

## 2. Metode

Dua cara membandingkan, keduanya dilaporkan agar tidak saling menutupi:

1. **Sequential** — meniru replay/CLI: setelah exit, sinyal berikutnya boleh masuk. Jumlah
   trade bisa berbeda antar-mode karena exit yang lebih cepat membebaskan slot berikutnya.
2. **Same-entry** — entri yang dipilih `classic` pada hold yang sama di-resolve ulang oleh
   semua mode. N identik antar-mode (620/359/263), sehingga perbedaan murni berasal dari exit.

Semua R memakai satuan yang sama: `R = profit_net_pct / risiko_kotor_pct`. Karena R
didefinisikan oleh jarak SL, perbandingan utama memakai **net %**, bukan R.

Kriteria kandidat ditetapkan sebelum melihat hasil:

- net/trade > 0;
- lebih baik daripada `classic` di ketiga pair;
- CI 95% bootstrap avg net/trade tidak menyentuh 0;
- minimal 60 trade per pair.

Tidak ada sel yang memenuhi satu pun dari syarat utama.

---

## 3. Hasil sequential (paritas CLI)

| Hold | Mode | N | WR% | avg R | net/trade % | net/hari % | DD% | TIMEOUT% |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 90m | classic | 620 | 14,84 | −0,1569 | −0,9118 | −3,119 | −565,3 | 99,4 |
| 90m | tp1_be | 620 | 14,84 | −0,1565 | −0,9097 | −3,112 | −564,0 | 99,4 |
| 90m | early | 658 | 15,05 | −0,1522 | −0,8847 | −3,212 | −582,1 | 87,1 |
| 90m | trail | 675 | 19,70 | −0,1535 | −0,8924 | −3,324 | −602,4 | 83,7 |
| 90m | full | 675 | 19,70 | −0,1536 | −0,8928 | −3,325 | −602,6 | 83,7 |
| 240m | classic | 359 | 22,28 | −0,1449 | −0,8420 | −1,668 | −302,3 | 98,6 |
| 240m | tp1_be | 359 | 22,84 | −0,1334 | −0,7756 | −1,536 | −279,1 | 98,6 |
| 240m | early | 427 | 24,36 | −0,1338 | −0,7777 | −1,832 | −332,1 | 71,4 |
| 240m | trail | 447 | 34,45 | −0,1418 | −0,8242 | −2,033 | −368,4 | 68,7 |
| 240m | full | 447 | 34,45 | −0,1418 | −0,8242 | −2,033 | −368,4 | 68,7 |
| 420m | classic | 263 | 28,14 | −0,1278 | −0,7430 | −1,078 | −237,6 | 95,8 |
| 420m | tp1_be | 263 | 28,14 | −0,1303 | −0,7575 | −1,099 | −236,2 | 95,8 |
| 420m | early | 343 | 32,07 | −0,1273 | −0,7398 | −1,400 | −266,8 | 60,3 |
| 420m | trail | 369 | 44,99 | −0,1297 | −0,7542 | −1,535 | −287,1 | 55,8 |
| 420m | full | 369 | 44,99 | −0,1298 | −0,7545 | −1,536 | −287,1 | 55,8 |

**Catatan:** `early` dan `trail` menambah jumlah trade (658/675 pada 90m) karena exit lebih
cepat membebaskan slot. Itu tidak menolong: net/hari justru lebih buruk daripada classic
(−3,21/−3,32 vs −3,12) karena setiap putaran tambahan membayar friksi.

---

## 4. Hasil same-entry (isolasi exit)

N sama antar-mode. CI 95% bootstrap (1.000 resample) untuk net/trade.

| Hold | Mode | N | WR% | avg R | net/trade % | net/hari % | CI 95% net/trade |
|---|---:|---:|---:|---:|---:|---:|---|
| 90m | classic | 620 | 14,84 | −0,1569 | −0,9118 | −3,119 | [−1,0248; −0,8023] |
| 90m | tp1_be | 620 | 14,84 | −0,1565 | −0,9097 | −3,112 | [−1,0228; −0,7982] |
| 90m | early | 620 | 14,35 | −0,1513 | −0,8792 | −3,008 | [−0,9718; −0,7833] |
| 90m | trail | 620 | 17,74 | −0,1536 | −0,8927 | −3,054 | [−0,9818; −0,8070] |
| 90m | full | 620 | 17,74 | −0,1536 | −0,8929 | −3,055 | [−0,9821; −0,8072] |
| 240m | classic | 359 | 22,28 | −0,1449 | −0,8420 | −1,668 | [−1,0303; −0,6416] |
| 240m | tp1_be | 359 | 22,84 | −0,1334 | −0,7756 | −1,536 | [−0,9612; −0,5626] |
| 240m | early | 359 | 21,45 | −0,1402 | −0,8152 | −1,615 | [−0,9768; −0,6432] |
| 240m | trail | 359 | 28,41 | −0,1450 | −0,8427 | −1,669 | [−0,9892; −0,6856] |
| 240m | full | 359 | 28,41 | −0,1450 | −0,8427 | −1,669 | [−0,9892; −0,6856] |
| 420m | classic | 263 | 28,14 | −0,1278 | −0,7430 | −1,078 | [−1,0439; −0,3985] |
| 420m | tp1_be | 263 | 28,14 | −0,1303 | −0,7575 | −1,099 | [−1,0459; −0,4331] |
| 420m | early | 263 | 28,14 | −0,1394 | −0,8101 | −1,176 | [−1,0351; −0,5742] |
| 420m | trail | 263 | 39,92 | −0,1367 | −0,7949 | −1,153 | [−1,0180; −0,5630] |
| 420m | full | 263 | 39,92 | −0,1367 | −0,7949 | −1,153 | [−1,0180; −0,5630] |

Semua CI berada **seluruhnya di bawah nol**.

---

## 5. Bauran exit (same-entry, 90m)

| Mode | TIMEOUT | SL | BE_SL | TRAIL_SL | EARLY_PROFIT |
|---|---:|---:|---:|---:|---:|
| classic | 616 | 4 | 0 | 0 | 0 |
| tp1_be | 616 | 4 | 0 | 0 | 0 |
| early | 551 | 1 | 21 | 0 | 47 |
| trail | 537 | 1 | 0 | 82 | 0 |
| full | 537 | 1 | 0 | 81 | 1 |

Pada 90 menit, TP1 (+7,99% kotor) praktis tidak tersentuh, sehingga `tp1_be` identik dengan
`classic`. `full` juga hampir identik dengan `trail`: early profit hanya terpicu 1 kali.
Menambah fitur tidak menambah hasil.

---

## 6. Temuan

1. **Exit-only tidak menciptakan edge pada sinyal P0.** Semua 90 sel pair × mode × hold
   negatif. Ini bukan soal parameter: entry membeli pergerakan yang median MFE 90 menit-nya
   (+0,506%) masih di bawah friksi round-trip (0,9688%).
2. **Win rate tinggi bukan keberhasilan.** `trail` menaikkan WR sampai 39,92% (same-entry,
   420m) dan 44,99% (sequential), tetapi net/trade tetap −0,75 s.d. −0,80% karena kemenangan
   dipotong kecil dan kerugian tetap sekitar satu unit risiko.
3. **Menahan lebih lama hanya mengurangi frekuensi membayar friksi.** Net/hari classic naik
   dari −3,12 (90m) ke −1,08 (420m), tetapi tetap negatif. Ini sejalan dengan sweep lantai
   friksi: biaya per trade ≈ satu friksi.
4. **`tp1_be` bukan kandidat.** Ia hanya sedikit lebih baik pada 240m (karena beberapa TP1
   tersentuh), tetapi tetap −1,54%/hari dan tidak lebih baik pada 90m/420m.
5. **`full` tidak memberi nilai tambah di atas `trail`.** TP1 dan early hampir tidak pernah
   mendahului trailing pada data ini.
6. **Re-entry memperburuk, bukan memperbaiki.** Mode yang keluar lebih cepat menghasilkan
   lebih banyak trade dan net/hari lebih negatif.

---

## 7. Keputusan

- Tidak ada mode yang memenuhi kriteria kandidat. **Tidak ada perubahan kode app, config
  default, atau jalur uang riil.**
- Riset exit-only untuk sinyal P0 **ditutup lagi** setelah dibuka kembali oleh owner pada
  2026-10-24. Membuka lagi memerlukan mekanisme baru yang eksplisit (mis. `partial_net`),
  bukan pengulangan grid yang sama.
- Bottleneck yang terukur tetap **entry/universe**: median MFE 90 menit hanya sekitar
  setengah friksi. Keputusan gate entry tetap menunggu instruksi owner.

---

## 8. Batas eksperimen

- Hanya 3 pair dan 60,4 hari; satu rezim pasar. Hasil tidak boleh digeneralisasi ke pair lain
  tanpa pengukuran ulang.
- Cache kandidat dibangun pada 2026-10-23 dari data yang sama; kontrol 620/14,84/−0,1569
  identik membuktikan isi cache tidak berubah. Alat menyediakan `--rebuild`.
- DD pada tabel same-entry bersifat indikatif: trade boleh tumpang tindih, jadi bukan kurva
  ekuitas satu posisi.
- Bootstrap hanya untuk same-entry (perbandingan bersih). Sequential dilaporkan tanpa CI.
- Angka R tidak boleh dipakai untuk memilih mode tanpa melihat net %: R dihitung dari SL.

---

## 9. Verifikasi

- Kontrol `classic` 90m: **620 trade / WR 14,84% / avg R −0,1569** (cocok dengan CLI).
- `python3 tools/test_scalp_replay_parity.py` → **30 test OK** (26 lama + 4 uji baru).
- Kontrol negatif: `bucket_of()` sengaja dirusak → uji `test_percentile_rank_and_bucket`
  **GAGAL**, lalu dikembalikan dan **OK**.
- Perintah:

```bash
python3 tools/exit_mode_sweep.py \
  --pairs ADAUSDT,DOGEUSDT,XRPUSDT --data data/tokocrypto_v4 \
  --config data/config/scalp_config.json \
  --modes classic,tp1_be,early,trail,full --holds 90,240,420 \
  --cache-dir /tmp/friction_sweep_cache \
  --out data/hasil/exp_exit_mode_sweep.json
```
