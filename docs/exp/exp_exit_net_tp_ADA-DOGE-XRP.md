# Eksperimen exit net-TP: TP kecil + stop rapat (ADA/DOGE/XRP)

Tanggal: 2026-10-22. Status: **selesai, hasil negatif**. Replay-only — tidak ada kode app
yang diubah dan tidak ada parameter yang direkomendasikan untuk produksi.

## Pertanyaan

Owner meminta exit yang **konsisten**: "minimal di satu hari ada 1 exit net, yang penting
konsisten", dengan TP **tidak** dikejar terlalu tinggi. Eksperimen ini menjawab: apakah ada
kombinasi TP-net kecil + stop rugi rapat yang memberi expectancy positif sekaligus memenuhi
kriteria frekuensi itu, pada sinyal P0 yang sudah ada?

## Vonis

**Tidak.** Dari **88 varian** yang diuji di tiga pair (20 varian tanpa stop tambahan, 60 varian
dengan stop rapat, 8 varian horizon 1–2 hari; masing-masing keluarga punya kontrol `classic`
sendiri), **tidak satu pun** menghasilkan net positif.
Varian terbaik masih **−0,41% per hari** pada unit risiko P0, dan justru yang paling tidak
konsisten (0,39 exit net/hari). Kriteria "1 exit net/hari" paling banyak tercapai di **54,6%
hari**, bukan setiap hari.

Sebabnya bukan tuning: **batas atas matematis** dari sinyal ini berada di bawah WR impas untuk
setiap kombinasi yang diuji (lihat § Batas atas). Exit tidak bisa memperbaiki entry yang median
pergerakan menguntungkannya lebih kecil daripada friksi.

## Metode

### Mode exit baru (replay-only)

Ditambahkan ke `tools/scalp_replay.py`:

- `net_tp` — keluar saat profit **NET** (fee beli+jual+slippage) ≥ `exit.net_tp_pct`.
  Harga eksekusi dihitung `_price_for_net(entry, net_pct, fee, slip)`, kebalikan persis dari
  `_net_profit_pct`.
- `net_tp_stop` — `net_tp` + stop rugi harga `exit.stop_gross_pct` yang **lebih rapat** daripada
  SL engine. Urutan prioritas per bar: **SL engine → NET_SL → NET_TP → TP2**. Gap yang menembus
  SL engine tetap dicatat `SL` (pesimistis, bukan diisi di harga stop).
- Kedua mode hanya hidup di replay. **Jangan disambungkan ke app tanpa instruksi owner**; stop
  rapat melanggar lantai friksi 6× yang berlaku untuk geometri entry produksi.

Penjaga: 2 uji baru di `tools/test_scalp_replay_parity.py` (total **25 test**):
`test_net_tp_exits_at_configured_net_target`,
`test_net_tp_never_fires_below_target_and_sl_wins_ambiguity`,
`test_net_tp_stop_cuts_before_engine_sl_and_gap_stays_sl`.

### Alat grid dan kontrolnya

`tools/exit_grid.py` mengevaluasi gate P0 **sekali per pair** (bagian mahal, ±4,5 menit) lalu
menjalankan puluhan varian exit di atas cache itu (±1 menit per pair). Keputusan gate tidak
bergantung pada config exit, jadi cache tidak mengubah hasil — dibuktikan kontrol:

| Pair | Kontrol `classic 90m` (grid) | Baseline P0 |
|---|---|---|
| ADA | 210 trade, WR 19,52%, avg R −0,1568 | 210 |
| DOGE | 193 trade, WR 11,92%, avg R −0,1552 | 193 |
| XRP | 217 trade, WR 12,90%, avg R −0,1584 | 217 |
| Total | 620 trade, WR 14,84% | 620 |

Angka kontrol **identik** dengan `docs/exp/exp_p0_gatekeeper_ADA-DOGE-XRP.md`, sehingga grid
sah dibandingkan dengan baseline.

### Data & fee

- `data/tokocrypto_v4`, M1 87.000 bar/pair = **60,42 hari**, HTF diagregasi dari M1.
- Fee truth `data/config/scalp_config.json`: USDT taker 0,4044%/sisi, slippage 0,08%/sisi,
  friksi aditif round-trip **0,9688%**, `min_friction_multiple` 6,0.
- Satu posisi pada satu waktu (sama dengan replay app); hold panjang otomatis mengurangi jumlah
  entry karena bar selama posisi dilewati.
- R dihitung terhadap risiko P0 (SL 5,81%), jadi avg R dan net% saling melengkapi:
  net% = R × 5,81.

## Hasil A — `net_tp` tanpa stop tambahan

21 varian per pair (5 TP × 4 hold + kontrol). Ringkasan rata-rata 3 pair:

| Varian | WR | avg R | exit net/hari | hari ada exit net | net %/hari |
|---|---|---|---|---|---|
| `net_tp 1.5%` / 1440m | 57,3% | −0,1300 | 0,68 | 32,6% | **−0,899** |
| `net_tp 1.0%` / 1440m | 62,0% | −0,1461 | 0,85 | 35,9% | −1,155 |
| `net_tp 0.75%` / 1440m | 68,5% | −0,1335 | 1,08 | 41,4% | −1,198 |
| `net_tp 0.5%` / 1440m | 70,8% | −0,1488 | 1,19 | 41,9% | −1,439 |
| `classic 90m` (kontrol) | 14,8% | −0,1568 | 0,51 | 27,6% | −3,119 |

WR bisa dinaikkan sampai **75,7%** (ADA, `net_tp 0,75%/1440m`), tetapi avg R tetap negatif:
kemenangan kecil (+0,5–0,75% net) tidak menutup kerugian yang tersisa. Tidak ada varian positif.

## Hasil B — `net_tp_stop` (60 varian per pair)

Stop rugi harga diuji 1,0/1,5/2,0/2,5% × TP net 0,4/0,5/0,75/1,0/1,5% × hold 240/420/1440m.
Enam teratas rata-rata 3 pair:

| Varian | WR | WR impas | win net% | loss net% | exit/hari | hari% | net %/hari |
|---|---|---|---|---|---|---|---|
| `tp1.5/sl2.5/1440m` | 51,6% | 65,8% | +1,46 | −2,84 | 0,77 | 33,7% | **−0,894** |
| `tp1.5/sl2.0/1440m` | 47,4% | 63,3% | +1,47 | −2,55 | 0,78 | 33,7% | −1,051 |
| `tp1.0/sl2.5/1440m` | 56,3% | 74,1% | +0,99 | −2,84 | 0,95 | 37,0% | −1,146 |
| `tp1.0/sl2.0/1440m` | 53,0% | 72,1% | +0,99 | −2,56 | 1,00 | 37,5% | −1,271 |
| `tp0.75/sl2.5/1440m` | 60,8% | 79,9% | +0,74 | −2,97 | 1,20 | 42,5% | −1,378 |
| `tp0.4/sl2.0/1440m` | 60,8% | 87,2% | +0,40 | −2,71 | **1,47** | **45,2%** | −1,947 |

Per pair untuk varian berfrekuensi tertinggi (`tp0.4/sl2.0/1440m`): ADA 1,82 exit/hari
(54,6% hari, −2,06%/hari), DOGE 1,21 (43,0%, −1,94%), XRP 1,37 (38,1%, −1,84%).

**Pelajaran penting dari tabel ini:** stop rapat **tidak** memperbaiki rasio, karena friksi
aditif. TP net 0,4% berarti kenaikan kotor ≈1,38%; stop kotor 2,0% berarti kerugian net
≈2,9%. Rasio net jadi ≈1:7. Mengetatkan stop dari 2,5% ke 1,0% tidak menolong: pada ADA
`tp0.4/sl1.0/420m` WR 44,1% sedangkan WR impasnya 81,9%.

## Hasil C — horizon panjang & TP lebih besar (uji batas)

Hold 1440/2880m × TP net 1,0/1,5/2,0/3,0%, 3 pair. Semua negatif; terbaik:

| Varian | WR | WR impas | exit/hari | hari% | net %/hari |
|---|---|---|---|---|---|
| `net_tp 3.0%` / 2880m | 51,3% | 58,8% | 0,39 | 23,2% | **−0,410** |
| `net_tp 3.0%` / 1440m | 45,2% | 54,9% | 0,41 | 27,0% | −0,533 |
| `net_tp 2.0%` / 2880m | 61,3% | 70,1% | 0,56 | 29,2% | −0,535 |

Semakin jauh TP, semakin kecil frekuensinya (0,39 exit/hari) — kebalikan dari kriteria owner.

## Batas atas: kenapa exit-only mustahil di sinyal ini

Untuk setiap sinyal siap, dihitung apakah **HIGH**-nya pernah mencapai harga TP net di dalam
horizon — yaitu skenario **exit sempurna** (jual tepat di puncak, stop tidak pernah kena). Ini
batas atas WR yang tidak mungkin dilampaui strategi exit mana pun:

| Hold | TP net | ADA | DOGE | XRP |
|---|---|---|---|---|
| 1440m | 1,0% | 72,0% | 54,5% | 60,4% |
| 1440m | 1,5% | 65,4% | 51,4% | 55,2% |
| 1440m | 3,0% | 52,0% | 40,2% | 41,6% |
| 2880m | 3,0% | 60,9% | 48,1% | 46,6% |

Bandingkan dengan WR impas varian terbaik pada horizon yang sama:

- `tp1.0/sl2.5/1440m`: batas atas 72,0 / 54,5 / 60,4% vs WR impas **76,8 / 72,6 / 72,8%**.
- `net_tp 3.0%/2880m`: batas atas 60,9 / 48,1 / 46,6% vs WR impas **64,2 / 59,3 / 53,0%**.

Di **setiap** pair, batas atas berada **di bawah** WR impas. Artinya bahkan exit yang mustahil
(tanpa stop, jual tepat di puncak) tidak cukup untuk impas. Exit-only **tidak bisa** menutup
selisihnya; masalahnya ada di entry, bukan di exit.

Dua angka pendukung yang sudah diukur sebelumnya (`docs/exp/exp_exit_modes_ADA-DOGE-XRP.md`):
median MFE 90 menit hanya **+0,506% ≈ setengah friksi**, dan hanya **31,6%** sinyal pernah
mencapai ≥1× friksi dalam 90 menit.

## Kriteria owner: "1 exit net/hari"

Frekuensi dan expectancy saling bertukar, dan tidak ada titik yang memenuhi keduanya:

- Frekuensi tertinggi: **1,82 exit net/hari** (ADA, `tp0.4/sl2.0/1440m`) tetapi hanya **54,6%
  hari** yang punya minimal satu exit net, dan net **−2,06%/hari**.
- Varian dengan hari-ada-exit-net tertinggi di ketiga pair: maksimum **54,6%** (ADA), 43,0%
  (DOGE), 38,1% (XRP). Tidak ada varian yang mencapai "setiap hari".
- Varian dengan kerugian terkecil justru paling jarang: 0,39 exit net/hari, 23,2% hari.

Jadi kriteria "minimal 1 exit net per hari" **tidak tercapai secara konsisten**, dan kalaupun
frekuensinya dipaksa, hasilnya tetap rugi.

## Batasan

- Replay-only; tidak membuktikan perilaku app (belum ada mode ini di `ScalpingConfig`).
- Stop `net_tp_stop` sengaja lebih rapat daripada lantai friksi 6×. Ini **alat eksperimen**,
  bukan rekomendasi produksi; memakainya di app akan melanggar aturan lantai friksi.
- Satu posisi pada satu waktu, tanpa compounding; net% dijumlahkan per trade, bukan bunga
  majemuk atas ekuitas.
- 3 pair, 60,42 hari, satu regime data; hasil bisa berbeda di pasar lain.
- Entry dibekukan pada gate P0; TTL tidak dimodelkan di replay (lihat aturan P0). Yang diuji
  murni keputusan exit pada sinyal yang sama.
- Fill stop di harga stop kecuali gap yang menembus SL engine (dicatat `SL`). Tidak ada
  modeling antrean/partial fill.

## Kesimpulan & langkah berikutnya

1. **Line riset exit-only ditutup untuk sinyal P0 saat ini.** Tidak ada parameter exit yang
   layak direkomendasikan; jangan promosikan ke uang riil.
2. Syarat agar exit bisa positif: median MFE pada horizon terpilih harus **≥ 2× friksi**
   (≈1,94% kotor). Sekarang median 90 menit hanya +0,51%, dan pada 1440m pun batas atas WR
   masih di bawah WR impas.
3. Perbaikan harus datang dari **entry** (atau universe pair), bukan dari exit. Sesuai
   `AGENTS.md`, menambah gate entry baru menunggu **instruksi eksplisit owner** — laporan ini
   berhenti di diagnosis, tidak mengubah gate.
4. Jangan mengutip angka WR tinggi (mis. 75,7%) sebagai keberhasilan: itu WR tanpa expectancy;
   avg R dan net%/hari tetap negatif.

## Reproduksi

```sh
python3 tools/test_scalp_replay_parity.py
python3 tools/exit_grid.py --pair ADAUSDT --data data/tokocrypto_v4 --bounds
python3 tools/exit_grid.py --pair ADAUSDT --data data/tokocrypto_v4 --grid net_tp
python3 tools/exit_grid.py --pair ADAUSDT --data data/tokocrypto_v4 --grid net_tp_stop
python3 tools/exit_grid.py --pair ADAUSDT --data data/tokocrypto_v4 \
  --grid net_tp --holds 1440,2880 --tps 1.0,1.5,2.0,3.0
```

Cache evaluasi bar disimpan di `/tmp/exit_grid_cache` (bawaan), jadi menjalankan beberapa grid
untuk pair yang sama tidak mengulang evaluasi 4,5 menit. Hapus cache bila config gate berubah.
