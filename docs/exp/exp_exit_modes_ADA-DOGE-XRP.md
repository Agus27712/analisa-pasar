# Eksperimen: Mode exit EARLY di atas gate P0 + pengukuran MFE/MAE dari bar sinyal

**Tanggal:** 2026-10-22
**Tujuan:** (Fase A) mengukur MFE/MAE dari bar masuk sinyal pada horizon 30/60/90 menit untuk
menjawab "berapa yang benar-benar bisa dicapai dari entry P0", dan (Fase B) mengukur apakah
mode exit `early` (kunci profit kecil) memperbaiki avg R tanpa mengubah gate entry.
**Data:** `data/tokocrypto_v4` — sama dengan eksperimen P0: 87.000 bar M1 per pair,
2026-08-12 s/d 2026-10-12 (±60,4 hari). BTC `BTCUSDT_1m.csv`.
**Pair:** ADAUSDT, DOGEUSDT, XRPUSDT.
**Alat:** `tools/scalp_replay.py` (ditambah field MFE/MAE per trade) + config varian
`data/config/scalp_config_exit_early_p0.json` (salinan penuh config P0, satu-satunya perubahan:
`exit.mode = early`).

---

## 1. Ringkasan

| Angka | CLASSIC (kontrol P0) | EARLY (Fase B) |
|---|---:|---:|
| Sinyal siap / trade selesai | 620 / 620 | 658 / 658 |
| Menang / kalah | 92 / 528 | 99 / 559 |
| Win rate | 14,84% | 15,05% |
| Avg R | −0,1569 | −0,1522 |
| CI 95% avg R | (−0,1748, −0,1389) | (−0,1692, −0,1352) |
| Payoff (menang/kalah) | 0,986 | 0,988 |
| Ambang impas win rate | 50,3% | 50,3% |
| Profit factor | 0,172 | 0,175 |
| Exit TIMEOUT | 616 | 573 |
| Exit EARLY_PROFIT / BE_SL | 0 / 0 | **53 / 28** |
| Exit SL / TP | 4 / 0 | 4 / 0 |

**Kesimpulan:** mode `early` memang bekerja secara mekanis — 81 trade dibawa keluar melalui
kunci profit (53 early profit rata-rata **+0,3208R**, 28 ke impas net 0,00R) — tetapi
perbaikan avg R **+0,0047R** berada **di dalam noise** (kedua CI 95% tumpang tindih).
Batas Fase C tidak tercapai, jadi grid S2 tidak diuji. Tidak ada mode exit yang mengubah
masalah strukturalnya: ambang impas tetap ~50% win rate sementara hit rate engine berada di
~15%.

---

## 2. Fase A — MFE/MAE dari bar masuk sinyal (gate P0, config P0)

Digabung dari 618 sinyal siap yang punya jendela 90 menit penuh (2 sinyal terpotong akhir
data). Friksi round-trip Tokocrypto USDT = **0,9688%**; TP1 kotor ≈ **7,99%**; SL kotor
(from `min_friction_multiple` 6,0) ≈ **5,81%**.

| Horizon | Median MFE | P25 | P75 | Median MAE | P25 MAE | ≥1× friksi | ≥2× friksi | ≥4× friksi | ≥TP1 | SL tersentuh |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 30 menit | +0,271% | +0,098% | +0,616% | −0,330% | −0,631% | 14,40% | 4,53% | 0,65% | 0,00% | 0,00% |
| 60 menit | +0,429% | +0,150% | +0,948% | −0,459% | −0,916% | 24,43% | 9,55% | 2,43% | 0,00% | 0,16% |
| 90 menit | +0,506% | +0,191% | +1,190% | −0,562% | −1,071% | 31,55% | 13,43% | 4,37% | 0,16% | 0,65% |

Per pair (90 menit): ADA median +0,73% / P75 +1,47%; DOGE +0,45% / +0,92%; XRP +0,40% / +1,05%.

**Pembacaan:**

1. **Entry P0 memang membeli ke arah yang benar.** Median MFE positif di semua horizon
   (observasi owner "posisi naik setelah sinyal buy" terkonfirmasi secara data), tetapi
   magnitudonya kecil: median 90 menit **+0,51% ≈ setengah friksi round-trip**.
2. **"Minimal profit bersih" juga bukan zone median.** Untuk menutup biaya saja (≥1× friksi)
   butuh pergerakan ≥0,97%; baru **31,6%** sinyal yang punya peluang itu dalam 90 menit.
   Lantai profit audit S2 (2× friksi ≈ 1,94% kotor) hanya tersentuh **13,4%** sinyal.
3. **TP1 (7,99%) praktis mustahil dalam 90 menit: 1 dari 618 sinyal (0,16%).** Ini penjelas
   langsung kenapa seluruh eksperimen P0 menghasilkan 0 TP.
4. **SL lebar 5,81% bukan pengikat dalam 90 menit** (tersentuh ≤0,65% sinyal; 4 SL dalam
   620 trade). Masalahnya bukan stop terlalu sempit, melainkan **profit yang kecil dan tidak
   dikunci**.

---

## 3. Fase B — mode exit EARLY (S2 default 2,0×; trigger net 0,45%)

Rencana semestanya: kontrol `classic` dulu lalu `early` dengan gate entry identik (semua
parameter P0 dipertahankan penuh). Kontrol `classic` terukur dua kali (sebelum dan sesudah
patch alat) dan **keduanya persis angka laporan P0** (tabel §1 kolom CLASSIC) — patch alat
hanya menambah field, tidak mengubah matematika.

Per rincian alasan exit (gabungan tiga pair):

| Alasan exit | CLASSIC n (avg R) | EARLY n (avg R) |
|---|---:|---:|
| TIMEOUT | 616 (−0,1504) | 573 (−0,1964) |
| EARLY_PROFIT | — | 53 (**+0,3208**) |
| BE_SL (impas net) | — | 28 (0,0000) |
| SL | 4 (−1,1562) | 4 (−1,1562) |

Pembacaan: mode `early` membina populasi exit-nya persis sesuai kapasitas MFE — 81 trade
dikunci (12,3% dari 658) terhadap 13,4% sinyal yang dalam 90 menit berpeluang menembus
lantai S2 2× friksi. Setiap early profit yang dikunci berukuran **+0,32R** (≈1,9% kotor /
≈1,87% net — sesuai lantai S2). Tapi jumlahnya terlalu kecil untuk mengangkat rata-rata:
sisa trade TIMEOUT justru menjadi ekor kerugian lebih dalam (−0,1504 → −0,1964), karena
apelsin yang akan berakhir TIMEOUT menang dipindahkan ke cabang early/BE.

Win rate alami trip besi masih: variabel tercatat; **payoff terturun 0,986 vs 0,988** — hampir
sama. Ambang impas butuh WR **≈50,3%**; hit rate engine di sini ~15%.

**Verdict Fase B: belum ada edge.** Peningkatan +0,0047R terlalu kecil pada dataset ini.

---

## 4. Fase C — tidak dieksekusi

Gerbang eksekusi Fase C yang disepakati sebelum eksperimen: **"jalankan grid S2 {0, 1, 2}
hanya jika Fase B memperbaiki avg R di luar noise."** Fase B tidak memenuhi gerbang itu
(CI 95% klasik dan early saling tumpang tindih), jadi grid S2 tidak dijalankan. Bila owner
menguji S2 murah (3 run), jalankan perintah §7 dengan config salinan yang mengubah
`min_profit_friction_multiple` — jangan langsung menaikkan nilai tanpa angka.

---

## 5. Verifikasi

| Pemeriksaan | Hasil |
|---|---|
| Kontrol `classic` sebelum patch | 620 / 14,84% / −0,1569 / pf 0,172 — **persis laporan P0** |
| Kontrol `classic` sesudah patch | identik (membuktikan patch alat hanya menambah field) |
| `python3 tools/test_scalp_replay_parity.py` | **22 test OK** (18 lama + 4 uji MFE baru) |
| `python3 tools/check_md_text.py` | dijalankan sebelum commit |

---

## 6. Batasan yang wajib dibaca

1. Satu jendela data (60 hari), tiga pair, fee Tokocrypto USDT taker. Pair IDR / Indodax
   tidak diuji.
2. Orderbook diasumsikan tersedia (`--assume-orderbook-ok`) — hasil tidak mengukur kualitas
   fill atau slippage nyata.
3. Iuran M5/H1 disintesis dari M1 (grid UTC), sama seperti replay lain.
4. Mode exit lain (`trail`, `full`, `tp1_be`) TIDAK diuji di eksperimen ini — rencana mulai
   dari `early` dulu. `tp1_be` praktis mati di geometri P0 (TP1 7,99%) tanpa perlu run.
5. Jumlah sinyal siap berbeda antar config (620 vs 658) karena exit lebih awal membuka
   posisi baru di bar-bar sisa — ini efek waktu, bukan perubahan gate entry. Bandingkan
   per-trade expectancy, bukan jumlah trade mentah.
6. Field `mfe_exit_resolved` di output run ini terisi nol (field `exit_idx` tidak ikut
   disalin ke rekam trade) — tidak mempengaruhi kesimpulan di atas; rincian per-alasan exit
   (§3) dipakai sebagai perbandingan alternatives. Bila dipakai di eksperimen berikutnya,
   tambahkan `exit_idx` ke merge field.
7. Tidak ada klaim edge dari hasil ini. Avg R tetap negatif pada dua config.

---

## 7. Perintah reproduksi

```sh
# Kontrol klasik (config P0 bawaan)
for p in ADAUSDT DOGEUSDT XRPUSDT; do
  python3 tools/scalp_replay.py --pair "$p" --data data/tokocrypto_v4 \
    --config data/config/scalp_config.json \
    --assume-orderbook-ok --out "/tmp/opencode/exit-exp/classic-mfe/$p.json"
done

# Early (satu-satunya perubahan: exit.mode = early)
for p in ADAUSDT DOGEUSDT XRPUSDT; do
  python3 tools/scalp_replay.py --pair "$p" --data data/tokocrypto_v4 \
    --config data/config/scalp_config_exit_early_p0.json \
    --assume-orderbook-ok --out "/tmp/opencode/exit-exp/early/$p.json"
done
```

---

## 8. Langkah berikutnya (untuk keputusan owner)

1. Mode exit di geometri saat ini (risiko 5,81%, lantai profit 1,94%) **tidak menghasilkan
   edge** — angkanya terukur dan konsisten dengan §61 (gap adalah jarak akurasi entry, bukan
   jarak parameter) dan §65 (P0 memperbaiki gate tapi tidak menciptakan edge).
2. Secara struktural: unit R ter-anchored ke risiko 5,81%, sedangkan profit yang bisa
   dikunci kira-kira 0,32R. Dengan payoff ~0,99, impas butuh WR ~50%. Dua jalur yang wajar
   diperhitungkan owner: (a) menguji **unit risiko lebih kecil** — artinya revisi
   `min_friction_multiple` < 6 atau definisi risiko berbasis ATR, tapi itu **melanggar
   aturan lantai 6×** yang WAJIB di AGENTS, jadi baru layak bila ada bukti replay baru
   lengkap + uji app; (b) menigkatkan akurasi entry secara berpotensi (bukan mengubah gate
   ambang — itu dilarang tanpa instruksi).
3. Untuk "exit strategi" murni dalam geometri sekarang: mode `trail`/`full` tinggal diukur
   bila owner mau (perintah §7 dengan config salinan), tapi hasil early yang sudah di luar
   noise mengisyaratkan keduanya tidak akan berbeda pada unit exit yang sama.

## Lanjutan (2026-10-22): TP net kecil + stop rapat

Tindak lanjut butir 3 di atas ada di **`exp_exit_net_tp_ADA-DOGE-XRP.md`**. Ringkas: 90 varian
`net_tp`/`net_tp_stop` di 3 pair **semua negatif**; batas atas WR (exit sempurna di high, tanpa
stop) berada di bawah WR impas, jadi line exit-only ditutup. Jangan memakai angka di laporan ini
sebagai dasar menaikkan/menurunkan parameter exit.
