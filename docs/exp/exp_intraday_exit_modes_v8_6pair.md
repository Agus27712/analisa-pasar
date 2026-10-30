# Eksperimen mode exit intraday — 6 pair `data/tokocrypto_v8` (H4)

**Status: GAGAL.** Tidak satu pun dari **7 varian exit** mengalahkan kontrol secara berarti, dan
**semua varian tetap negatif**. Mesin exit intraday (`IntradayExitEngine`) **tidak disambungkan ke
aplikasi**; bawaan tetap `SESSION_CLOSE`.

- **Tanggal ukur:** 2026-10-30 (PC, JDK 17, Gradle 9.7).
- **Alat:** `app/src/test/java/agu/analys/engine/intraday/replay/IntradayExitModeSweepTest.kt`.
- **Dataset:** `data/tokocrypto_v8`, timeframe **4h**, 6 pair (LINKUSDT, AVAXUSDT, LTCUSDT,
  TRXUSDT, ADAUSDT, PEPEUSDT) — 4.999 bar/pair (PEPEUSDT 2.999).
- **Fee:** Tokocrypto USDT all-in, round-trip **0,974%** multiplikatif, slippage 0,080%/sisi.
- **Config pengukuran:** `maxTp1Pct = 8,0%` (**bukan** bawaan app; bawaan tetap **5,0%**),
  `minNetRrTp1 = 1,500`, `macdHistMinPct = 0,0`, lookahead **6 bar** (24 jam), pemanasan 220 bar.

Perintah:

```
RUN_INTRADAY_EXIT_SWEEP=true INTRADAY_EXIT_SWEEP_CAP=8.0 \
  INTRADAY_EXIT_SWEEP_PAIRS=LINKUSDT,AVAXUSDT,LTCUSDT,TRXUSDT,ADAUSDT,PEPEUSDT \
  ./gradlew :app:testDebugUnitTest \
  --tests "agu.analys.engine.intraday.replay.IntradayExitModeSweepTest"
```

Durasi **42 menit 28 detik**. Keluaran: `app/build/intraday-exit-sweep.md`.

**Satu variabel berubah.** Entry, fee, gate, dan geometri **sama** di semua baris; hanya aturan
keluar yang berbeda. Invarian "BUY identik" diperiksa per pair di dalam test (`buyIndices`), jadi
mode exit terbukti tidak menyentuh sinyal.

## Kontrol tervalidasi terhadap alat paket D

Kontrol `SESSION_CLOSE` di 6 pair ini menghasilkan **53 BUY / 52 trade / total net −33,046%** —
identik dengan hasil alat replay riset (`IntradayResearchReplayTest`, cap 8,0%) pada enam pair yang
sama: LINK 12 BUY/12 trade (−15,614%), AVAX 12/11 (−8,666%), LTC 9/9 (−1,784%), TRX 8/8 (−2,232%),
ADA 6/6 (−2,747%), PEPE 6/6 (−2,004%); jumlahnya 53 BUY, 52 trade, **−33,047%** (selisih 0,001 poin
dari pembulatan). Artinya kedua alat mengukur jalur yang sama, bukan jalur kedua.

## Window penuh

| Varian exit | BUY | Trade | Menang | Win rate | Avg net/trade | Total net | Tidak tuntas | Data habis | Gross+ / net− |
|---|---|---|---|---|---|---|---|---|---|
| **kontrol `SESSION_CLOSE`** | 53 | 52 | 15 | 28,846% | **−0,636%** | −33,046% | 0 | 0 | 16 |
| `TP1_BREAKEVEN` | 53 | 52 | 15 | 28,846% | −0,739% | −38,434% | 0 | 0 | 16 |
| `TRAIL_ATR` 1,0× (arm TP1) | 53 | 52 | 15 | 28,846% | −0,739% | −38,434% | 0 | 0 | 16 |
| `TRAIL_ATR` 1,5× (arm TP1) | 53 | 52 | 15 | 28,846% | −0,739% | −38,434% | 0 | 0 | 16 |
| `TRAIL_ATR` 1,0× (arm entry) | 53 | 52 | 13 | 25,000% | −0,753% | −39,131% | 0 | 0 | 12 |
| `TIME_STOP` 3 bar | 53 | 52 | 16 | 30,769% | **−0,587%** | −30,514% | 0 | 0 | 15 |
| `TIME_STOP` 6 bar | 53 | 52 | 15 | 28,846% | −0,636% | −33,046% | 0 | 0 | 16 |
| `TIME_STOP` 12 bar | 53 | 52 | 15 | 28,846% | −0,636% | −33,046% | 0 | 0 | 16 |

**Tidak ada satu baris pun yang positif.** Varian terbaik (`TIME_STOP` 3 bar) hanya **+0,049
poin persen** di atas kontrol — jauh di dalam noise, dan masih **−0,587%/trade**.

## Potongan window dan walk-forward (avg net/trade, jumlah trade dalam kurung)

| Varian exit | Awal 60% | Akhir 40% | Fold 1 | Fold 2 | Fold 3 | Fold 4 |
|---|---|---|---|---|---|---|
| kontrol `SESSION_CLOSE` | +0,013% (19) | −1,009% (33) | +0,890% (9) | −1,487% (9) | −0,796% (15) | −0,828% (19) |
| `TP1_BREAKEVEN` | −0,271% (19) | −1,009% (33) | +0,868% (9) | −1,487% (9) | −1,142% (15) | −0,828% (19) |
| `TRAIL_ATR` 1,0× (arm TP1) | −0,271% (19) | −1,009% (33) | +0,868% (9) | −1,487% (9) | −1,142% (15) | −0,828% (19) |
| `TRAIL_ATR` 1,5× (arm TP1) | −0,271% (19) | −1,009% (33) | +0,868% (9) | −1,487% (9) | −1,142% (15) | −0,828% (19) |
| `TRAIL_ATR` 1,0× (arm entry) | −0,262% (19) | −1,035% (33) | +0,664% (9) | −1,265% (9) | −1,223% (15) | −0,810% (19) |
| `TIME_STOP` 3 bar | +0,009% (19) | −0,930% (33) | +0,774% (9) | −1,378% (9) | −0,635% (15) | −0,819% (19) |
| `TIME_STOP` 6 bar | +0,013% (19) | −1,009% (33) | +0,890% (9) | −1,487% (9) | −0,796% (15) | −0,828% (19) |
| `TIME_STOP` 12 bar | +0,013% (19) | −1,009% (33) | +0,890% (9) | −1,487% (9) | −0,796% (15) | −0,828% (19) |

`TIME_STOP` 3 bar lebih ringan dari kontrol di hampir semua irisan, tetapi **4 dari 6 irisan tetap
negatif** (akhir 40% **−0,930%** dengan 33 trade, fold 2 −1,378%, fold 3 −0,635%, fold 4 −0,819%).
Dua irisan yang positif (awal 60% +0,009%, fold 1 +0,774%) hanya berisi 19 dan 9 trade. Tidak ada
bukti di luar sampel.

## Distribusi alasan keluar (window penuh)

| Varian exit | Alasan | Jumlah |
|---|---|---|
| kontrol `SESSION_CLOSE` | `CLOSE_MALAM_SESSION_EXIT` | 42 |
| kontrol `SESSION_CLOSE` | `STOP_LOSS_HIT` | 8 |
| kontrol `SESSION_CLOSE` | `TP1_HIT` | 2 |
| `TP1_BREAKEVEN` | `CLOSE_MALAM_SESSION_EXIT` | 44 |
| `TP1_BREAKEVEN` | `STOP_LOSS_HIT` | 8 |
| `TRAIL_ATR` 1,0× (arm TP1) | `CLOSE_MALAM_SESSION_EXIT` | 44 |
| `TRAIL_ATR` 1,0× (arm TP1) | `STOP_LOSS_HIT` | 8 |
| `TRAIL_ATR` 1,5× (arm TP1) | `CLOSE_MALAM_SESSION_EXIT` | 44 |
| `TRAIL_ATR` 1,5× (arm TP1) | `STOP_LOSS_HIT` | 8 |
| `TRAIL_ATR` 1,0× (arm entry) | `CLOSE_MALAM_SESSION_EXIT` | 23 |
| `TRAIL_ATR` 1,0× (arm entry) | `STOP_LOSS_HIT` | 1 |
| `TRAIL_ATR` 1,0× (arm entry) | `TRAIL_STOP_HIT` | 28 |
| `TIME_STOP` 3 bar | `CLOSE_MALAM_SESSION_EXIT` | 13 |
| `TIME_STOP` 3 bar | `STOP_LOSS_HIT` | 5 |
| `TIME_STOP` 3 bar | `TIME_STOP` | 32 |
| `TIME_STOP` 3 bar | `TP1_HIT` | 2 |
| `TIME_STOP` 6 bar | `CLOSE_MALAM_SESSION_EXIT` | 42 |
| `TIME_STOP` 6 bar | `STOP_LOSS_HIT` | 8 |
| `TIME_STOP` 6 bar | `TP1_HIT` | 2 |
| `TIME_STOP` 12 bar | `CLOSE_MALAM_SESSION_EXIT` | 42 |
| `TIME_STOP` 12 bar | `STOP_LOSS_HIT` | 8 |
| `TIME_STOP` 12 bar | `TP1_HIT` | 2 |

## Bacaan yang wajib ikut dikutip

1. **`TP1_BREAKEVEN` lebih buruk dari kontrol, bukan lebih baik** (−0,739% vs −0,636%). Sebabnya
   terlihat di distribusi: dua trade yang tadinya keluar di `TP1_HIT` (menang) sekarang **tidak
   keluar di TP1** — keduanya berlanjut sampai Close Malam dan berakhir lebih buruk. Break-even
   setelah TP1 hanya berguna bila harga memang berbalik melewati titik impas; di sampel ini tidak.
2. **`TRAIL_ATR` dengan arm TP1 identik digit demi digit dengan `TP1_BREAKEVEN`** (0 `TRAIL_STOP_HIT`
   di keduanya). Artinya trail tidak pernah aktif/tersentuh setelah TP1 pada sampel ini — mengubah
   pengali 1,0× → 1,5× **tidak mengubah apa pun**. Jangan mengutip "trail 1,5× lebih longgar" sebagai
   temuan: pada data ini keduanya tidak pernah dieksekusi.
3. **`TRAIL_ATR` arm entry justru terburuk** (−0,753%, 28 `TRAIL_STOP_HIT`, 13 menang). Ini pola yang
   sama dengan temuan exit scalping: keluar lebih awal memotong pemenang sementara friksi round-trip
   0,974% tetap dibayar penuh.
4. **`TIME_STOP` 3 bar adalah satu-satunya varian yang sedikit membaik** (+0,049 poin persen,
   16 vs 15 menang) dan tetap **negatif**. Ini juga pola yang sama dengan replay scalping: menahan
   lebih lama hanya mengurangi frekuensi friksi, tidak menciptakan edge.
5. **Kriteria kandidat tidak terpenuhi satu pun.** Kriteria ditetapkan **sebelum** hasil dibaca
   (`docs/AGENTS.md`): net/trade > 0; lebih baik dari kontrol di **semua** pair; CI 95% tidak
   menyentuh nol; sampel ≥ 60 trade/pair. Hasilnya: (a) **tidak ada** varian dengan net/trade > 0;
   (b) perbandingan per pair tidak dihitung karena syarat pertama sudah gagal — laporan alat ini
   hanya memuat agregat dan irisan, jadi klaim "lebih baik di semua pair" **tidak** bisa dibuat dari
   data ini; (c) CI 95% tidak dihitung, alasan sama; (d) sampel maksimum 12 trade/pair (LINKUSDT),
   jauh di bawah 60.
6. **Keputusan:** `IntradayExitEngine` tetap **tidak disambungkan ke aplikasi**. Bawaan
   `IntradayExitConfig.DEFAULT` = `SESSION_CLOSE` dipertahankan (perilaku lama), `maxTp1Pct` tetap
   **5,0%**, `minNetRrTp1` tetap **1,5**. Tidak ada gate yang dilonggarkan dan tidak ada config yang
   diubah untuk "membuat exit ini kelihatan berhasil".

## Batasan eksperimen (apa yang TIDAK dibuktikan)

1. **Hanya 6 pair** dari 26 pair dataset, dipilih karena merekalah yang punya BUY di kontrol pada
   cap 8,0% (pair tanpa BUY tidak mungkin punya trade karena entry identik). Cakupan ini **bukan**
   seluruh universe intraday.
2. **Hanya USDT.** Pair IDR (biaya all-in lebih murah) belum terukur di alat ini.
3. **Satu timeframe (H4) dan satu window data.** `TIME_STOP` dihitung dalam **jumlah bar**
   (H4 = 4 jam/bar), bukan jam — 3 bar = 12 jam. Jangan membandingkan angkanya dengan time-stop
   scalping yang memakai menit.
4. **Cap 8,0% adalah config pengukuran, bukan bawaan.** Dengan bawaan 5,0% replay yang sama
   menghasilkan **0 BUY**, sehingga sweep exit tidak bisa dijalankan sama sekali.
5. **Tidak ada kontrol negatif khusus eksperimen ini** selain invarian "BUY identik antar varian"
   yang diperiksa di dalam test. Kontrol negatif yang dijalankan pada paket ini adalah untuk penjaga
   perilaku (`IntradayExitEngineTest`, `IntradayReplayWindowAccountingTest`), bukan untuk sweep.
6. **Angka ini bukan bukti edge dan bukan dasar uang riil.** Sinyal intraday sudah negatif sebelum
   exit diubah; mengubah exit tidak memperbaikinya di sampel ini.

## Eksperimen terkait

- `exp_intraday_exit_modes_v8_6pair.md` (berkas ini) — exit intraday, 6 pair H4.
- Replay riset paket D: `app/build/intraday-research-replay.md` (cap 5,0% ⇒ 0 BUY) dan
  `app/build/intraday-research-replay-cap8.md` (cap 8,0% ⇒ 105 BUY/102 trade, avg net −0,646%).
- Sweep batas atas TP1: `app/build/intraday-maxtp1-sweep.md` (3–10%).
- Exit scalping (pembanding pola, **bukan** angka yang sama): `exp_exit_mode_sweep_ADA-DOGE-XRP.md`
  (0/90 sel), `exp_exit_net_tp_ADA-DOGE-XRP.md` (0/88 varian).
