# Sweep lantai friksi (`min_friction_multiple`) — ADA/DOGE/XRP

Tanggal: 2026-10-22. Status: **selesai; 0 dari 27 kombinasi untung**. Replay-only, tidak ada
kode app yang diubah.

## Pertanyaan

Nilai bawaan `min_friction_multiple = 6.0` diwarisi dari eksperimen lama yang menilai hasil
dalam **R** (SL 0,5% dilaporkan −1,8R di 28/28 pair). R dihitung dari jarak SL, sehingga
membandingkan R antar-lantai friksi **bias ke arah SL lebar** — bukti lamanya melingkar.
Pertanyaan yang benar: kalau data, gate masuk, fee, dan aturan exit **sama**, apa dampak
lantai friksi terhadap **uang** (net %), bukan terhadap R?

## Jawaban singkat

1. **6.0 bukan penyebab kerugian.** Semua kelipatan 1,0–8,0 rugi; 6,0 berada di plateau,
   bukan di titik buruk.
2. **3,0 sedikit lebih baik dari 6,0** dalam uang: −0,993 vs −1,078 %/hari (rata-rata 3 pair,
   hold 420m) — sekitar **+0,085 %/hari**, dan masih **−60% dalam 60 hari**. Bukan edge.
3. **Di bawah 1,0 tidak bisa diuji secara sah:** geometri mengikat SL dan target
   (`tp2 = 2 × (SL + F) + F`), sehingga SL sangat rapat membuat `tp2/risk` melampaui
   `max_tp2_r` (5,0) dan sinyal **ditolak**. Pada kelipatan 0,5 hanya 3/7/10 trade dalam 60
   hari; angka "−0,2%/hari" itu artefak jumlah trade yang hampir nol, bukan hasil bagus.
4. **R menyesatkan, uang tidak.** Pada hold 420m, mult 8 punya avg R terbaik (−0,096) tetapi
   net %/trade lebih buruk (−0,747) daripada mult 3 (avg R −0,230, net −0,667). Peringkat R dan
   peringkat uang menunjuk pemenang yang berbeda; yang menentukan adalah net %.

## Metode

Alat baru `tools/friction_sweep.py`. Yang diubah **hanya** `min_friction_multiple`; data, gate
P0, fee, `target_net_rr`, `max_risk_atr`, dan aturan exit identik.

- Gate dievaluasi **sekali** per pair; masukan mentah `risk_levels` (struktur, ATR, skor)
  disimpan, lalu geometri risiko dihitung ulang untuk tiap kelipatan. Struktur/regime/skor
  tidak bergantung pada kelipatan, jadi ini setara dengan menjalankan replay penuh per
  kelipatan — dibuktikan kontrol di bawah.
- **Kontrol:** pada mult 6,0, hold 90m, hasilnya **persis** CLI replay: ADA 210 trade /
  WR 19,52% / avg R −0,1568; total 620 trade / WR 14,84%.
- Data: `data/tokocrypto_v4` (ADA/DOGE/XRP, 87.000 bar M1, 60,42 hari); fee truth USDT
  0,4044%/sisi + slippage 0,08%/sisi (F = 0,9688%).
- Exit: `classic` (SL/TP2 + time-stop), TP2 mengikuti `target_net_rr` 2,0. Hold 90/240/420m
  sebagai sensitivitas; aturan exit tetap sama di dalam setiap perbandingan.
- Metrik utama: **net %/trade dan net %/hari** (uang), dengan avg R hanya sebagai pembanding.

### Catatan geometri (kenapa kelipatan bukan tombol risiko sederhana)

`risk_levels` menetapkan:

```
SL_gross = max(struktur, 0.8×ATR, min_risk_pct, multiple × F)
TP2_gross = target_net_rr × (SL_gross + F) + F
```

Menurunkan kelipatan ikut menurunkan TP2, tetapi **TP2/risiko** naik. Begitu rasionya melewati
`max_tp2_r`, sinyal dibatalkan. Karena itu efek "SL lebih rapat" tidak bisa dipisahkan dari
"target lebih jauh secara relatif" — dan itu justru bagian dari dampak yang diukur.

## Hasil utama — hold 420m (rata-rata 3 pair)

| mult | SL % | TP2 % | N | WR | WR impas | avg R | net %/trade | **net %/hari** | max DD% | SL% | TIMEOUT% |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0,5* | 1,11–1,35 | 5,1–5,6 | 7 | 3,3% | 78,4% | −1,643 | −1,951 | −0,200 | −12,1 | 96,7 | 0,0 |
| 1,0 | 0,98 | 4,86 | 139 | 21,8% | 39,8% | −0,765 | −0,755 | −1,735 | −105,2 | 58,3 | 30,7 |
| 1,5 | 1,46 | 5,82 | 114 | 27,2% | 44,7% | −0,493 | −0,720 | −1,358 | −88,0 | 42,4 | 49,9 |
| 2,0 | 1,94 | 6,78 | 99 | 28,3% | 44,2% | −0,339 | **−0,657** | −1,075 | −74,9 | 27,8 | 66,5 |
| 3,0 | 2,91 | 8,72 | 90 | 28,4% | 44,3% | −0,230 | −0,667 | **−0,993** | −71,7 | 12,6 | 85,2 |
| 4,0 | 3,88 | 10,66 | 88 | 27,8% | 45,1% | −0,181 | −0,702 | −1,024 | −73,8 | 6,8 | 92,4 |
| 5,0 | 4,84 | 12,59 | 88 | 28,0% | 45,4% | −0,149 | −0,722 | −1,045 | −76,3 | 4,2 | 95,0 |
| 6,0 | 5,81 | 14,53 | 88 | 28,0% | 45,6% | −0,128 | −0,745 | −1,078 | −79,2 | 3,8 | 95,8 |
| 8,0 | 7,75 | 18,41 | 88 | 28,0% | 45,4% | −0,096 | −0,747 | −1,080 | −80,3 | 1,9 | 98,1 |

\* Degenerate: sinyal ditolak `max_tp2_r`; angka tidak sebanding.

Per pair (hold 420m):

| mult | ADA net %/hari | DOGE net %/hari | XRP net %/hari |
|---|---|---|---|
| 1,0 | −1,779 | −1,816 | −1,609 |
| 2,0 | −1,229 | −1,006 | −0,990 |
| 3,0 | −1,017 | −0,928 | −1,033 |
| 4,0 | −1,009 | −1,015 | −1,050 |
| 6,0 | −1,139 | −1,034 | −1,061 |
| 8,0 | −1,098 | −1,067 | −1,076 |

## Efek hold (jumlah putaran transaksi) lebih besar daripada efek lantai

Rata-rata 3 pair, net %/hari:

| mult | hold 90m | hold 240m | hold 420m |
|---|---|---|---|
| 3,0 | −3,129 | −1,533 | −0,993 |
| 6,0 | −3,119 | −1,668 | −1,078 |

Menahan lebih lama mengurangi **jumlah** trade (satu posisi pada satu waktu), jadi kerugian
harian mengecil — bukan karena entry menjadi lebih baik. Pada mult 6, biaya per trade
−0,75% ≈ 0,8× friksi; angka itu hampir tidak berubah dari mult 1 sampai 8. Inilah tanda
entry tidak punya edge: setiap transaksi selesai membayar sekitar satu kali friksi.

## R vs uang: bukti bias R

Hold 420m, rata-rata 3 pair:

| mult | avg R | peringkat R | net %/trade | peringkat uang |
|---|---|---|---|---|
| 3,0 | −0,230 | ke-5 | **−0,667** | **ke-1** |
| 8,0 | **−0,096** | **ke-1** | −0,747 | ke-8 |

R memberi "medali" kepada SL terlebar justru ketika uangnya lebih buruk. Karena itu kriteria
lama berbasis R (−1,8R pada SL 0,5%) tidak boleh dipakai lagi untuk memilih lantai friksi.

## Apakah 6,0 tetap dipertahankan?

Ya, untuk alasan yang **berbeda** dari dokumen lama:

1. 3,0 memang sedikit lebih baik (+0,085 %/hari), tetapi tetap −0,99 %/hari; memindahkan
   bawaan tidak mengubah kesimpulan "tidak layak".
2. 3,0 menghasilkan stop-out jauh lebih sering (12,6% vs 3,8%) dengan DD setara
   (−71,7% vs −79,2%); 6,0 lebih konservatif dan lebih dekat ke perilaku app saat ini.
3. Keunggulan 3,0 kecil dan hanya diukur pada 3 pair/60 hari; menurunkannya butuh bukti yang
   lebih kuat, bukan sekadar peringkat tipis.

**Yang harus dihentikan:** mengutip bukti lama "0,5% → −1,8R di 28/28 pair" sebagai alasan
lantai 6×. Alasan yang berlaku sekarang: sweep uang ini menunjukkan semua kelipatan rugi dan
6,0 berada di plateau; bukan karena R.

## Kalau saya yang trading dengan aplikasi ini

Saya **tidak akan** memakai konfigurasi mana pun di tabel ini. Yang terbaik masih kehilangan
≈1% modal per hari dengan DD 66–77% dalam 60 hari. Kerugian kumulatif ≈60% bila dijumlahkan
(tanpa bunga majemuk); bila di-compound harian tetap ≈45%. Modal Rp 100 juta menjadi
Rp 40–55 juta. Tidak ada pilihan lantai friksi yang memperbaiki itu, karena
masalahnya bukan lantai friksi, melainkan tidak adanya edge di entry: per transaksi selesai
rata-rata membayar 0,65–0,9% net, hampir persis satu friksi.

## Batasan

- 3 pair, 60,42 hari, satu rezim data; bukan bukti lintas pasar.
- Net % dijumlahkan (posisi berukuran tetap), bukan bunga majemuk; DD adalah drawdown kurva
  net % kumulatif, bukan drawdown ekuitas dengan compounding.
- Exit `classic` dengan TP2 yang ikut berskala; mode exit lain tidak diuji di sini (lihat
  `exp_exit_net_tp_ADA-DOGE-XRP.md`, yang juga negatif).
- TTL tidak dimodelkan; entry dibekukan pada gate P0.
- Fill di harga level; tidak ada antrean/partial fill.
- Gate dievaluasi sekali per pair, lalu geometri dihitung ulang per kelipatan; kontrol mult 6,0
  identik CLI, tetapi kombinasi lain tidak diuji ulang lewat replay penuh (biaya 7 menit/pair).

## Reproduksi

```sh
python3 tools/test_scalp_replay_parity.py
python3 tools/friction_sweep.py --pair ADAUSDT --data data/tokocrypto_v4 --holds 90,240,420
python3 tools/friction_sweep.py --pair DOGEUSDT --data data/tokocrypto_v4 --holds 90,240,420
python3 tools/friction_sweep.py --pair XRPUSDT --data data/tokocrypto_v4 --holds 90,240,420
```

Kontrol cepat: `--multiples 6 --holds 90` harus memberi 210 trade / WR 19,52% / avg R −0,1568
untuk ADA. Cache gate ada di `/tmp/friction_sweep_cache`; hapus bila config gate berubah.
