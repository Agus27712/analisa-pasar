# LAPORAN — Kesiapan Multi-Horizon (TEAM 1 + TEAM 2)

**Tanggal:** 2026-10-13 · **Branch:** `patch` · **Basis:** `main` @ `9e892fc`

---

## A. Ringkasan eksekutif

Aplikasi sekarang bisa menjawab pertanyaan yang sebelumnya tidak bisa dijawab: **"dari semua koin
yang saya pantau, mana yang benar-benar siap masuk — dan untuk horizon apa?"**

Sebelumnya aplikasi hanya punya satu kesimpulan per koin (mode aktif), dan kesimpulan itu tidak
memperhitungkan bahwa **biaya transaksi menentukan horizon yang layak**. Koin dengan ATR 0,3% tidak
bisa di-scalp di bursa berbiaya 0,97% round-trip, tetapi justru layak untuk swing karena stop loss
swing yang lebar membuat friksi tidak berarti.

Yang dikerjakan:

| | |
|---|---|
| **TEAM 1 (engine)** | Model + mesin kesiapan untuk 3 horizon, gate ekonomi berbasis kriteria `PairEdgeGate`, dan rencana exit-long eksplisit untuk scalping (beli → tahan → keluar saat profit) |
| **TEAM 2 (UI)** | Papan kesiapan responsif, kartu ringkasan di Dashboard, kartu per-koin di halaman detail, plus panduan lanjutan untuk agen berikutnya |

Hasil verifikasi: **364 test · 0 gagal · 1 dilewati** (baseline terukur `9e892fc` = 338, **+26**),
`assembleDebug` **BUILD SUCCESSFUL**, APK 26,5 MB.

---

## B. Masalah yang dijawab

### B.1 "Satu skor koin" tidak mungkin benar

Keputusan masuk ditentukan oleh **biaya**, dan biaya itu tetap sementara volatilitas koin berbeda:

| Koin | ATR M15 | SCALP | SWING |
|---|---|---|---|
| Tenang | 0,3% | friksi 0,97% menelan seluruh gerakan → **tidak layak** | stop lebar → friksi tidak berarti → **layak** |
| Wajar | 1,0% | bergerak 5,3% dalam 7 jam → **layak** | perlu konfirmasi struktur D1 |
| Liar | 4,0% | noise mengalahkan sinyal → **tertahan** | berisiko beli di puncak |

Karena itu kesiapan dinilai **terpisah per horizon**, masing-masing dengan timeframe, lantai friksi,
dan target net R:R sendiri. Satu koin boleh muncul tiga kali dengan status berbeda — itu informasi,
bukan kontradiksi.

### B.2 Exit long untuk scalping belum pernah dirumuskan sebagai satu kalimat

Engine paper sudah bisa beli–tahan–jual (`PaperExitResolver` + `ScalpExitConfig`), tetapi **aturan
exit-nya tidak pernah ditampilkan** kepada pengguna dalam bentuk yang bisa dibaca. Sekarang aturan
itu dibangun dari `ScalpExitParams` yang **sedang aktif**, sehingga UI tidak mungkin menampilkan
aturan yang berbeda dari yang benar-benar dijalankan engine.

---

## C. TEAM 1 — Engine

### C.1 Berkas baru (`agu.analys.engine.readiness`)

| Berkas | Baris | Isi |
|---|---:|---|
| `TradeHorizon.kt` | 104 | enum SCALP/INTRADAY/SWING: timeframe bias & trigger, jendela tahan, target net R:R, lantai friksi, penanda `validatedOutOfSample` |
| `HorizonReadiness.kt` | 128 | model murni: `ReadinessState`, `ReadinessCheck`, `TradePlan`, `HorizonReadiness`, `ReadinessBoard` |
| `HorizonReadinessEngine.kt` | 629 | penilaian satu (koin × horizon): 5 syarat + penghalang keras + rencana trade |
| `ReadinessScanner.kt` | 81 | `CandleSource` + pemindaian N koin × 3 horizon dengan dedup timeframe |

### C.2 Ambang per horizon

| Horizon | Timeframes | Lantai friksi | Target net R:R | Jendela | Divalidasi? |
|---|---|---|---|---|---|
| SCALP | H1 / M15 / M1 | **6×** (sama dengan `ScalpingConfig`) | **2,0** (sama) | 28 bar M15 | **ya** |
| INTRADAY | H4 / H1 / M15 | 4× | 1,5 | 12 bar H1 | belum |
| SWING | D1 / H4 / H1 | 3× | 2,0 | 14 bar H4 | belum |

Lantai friksi INTRADAY/SWING sengaja lebih longgar **dengan alasan**: stop mereka berbasis H1/H4
yang secara alami jauh lebih lebar dari stop scalp M1/M15, sehingga lantai 6× tidak akan pernah
mengikat. Menuliskan 6× di sana hanya akan menciptakan penjaga semu — penjaga yang selalu lolos
sama tidak bergunanya dengan penjaga yang selalu menolak.

### C.3 Lima syarat per horizon

**SCALP** — tren H1 searah · momentum M15 (RSI 50–75, di atas EMA20) · dorongan M1 (RVOL ≥ 1,2×,
candle naik) · volatilitas sehat (ATR M15 0,3–1,5%) · **gate ekonomi**.

**INTRADAY** — sesi boleh buka (WIB) · tren H4 searah · kekuatan tren H1 (ADX ≥ 20) · bukan trauma
dump 72 jam · gate ekonomi.

**SWING** — tren D1 searah · harga tidak extended (≤ 8% di atas EMA20 D1) · momentum H4 sehat
(RSI 45–70) · struktur higher low H4 · gate ekonomi.

**Penghalang keras** (langsung `TERTAHAN`, apa pun syarat lainnya): ATR M15 > 3,5% untuk scalp;
harga > 15% di atas EMA20 D1 untuk swing.

### C.4 Gate ekonomi — kesalahan desain yang ditemukan dan dibuang

Ini bagian terpenting dari laporan ini, karena versi pertama saya **salah** dan kesalahannya tidak
kelihatan sampai dihitung.

**Versi pertama** membandingkan risiko alami (`0,8 × ATR`) terhadap lantai friksi. Untuk USDT
Tokocrypto lantai = 5,81%, sehingga syaratnya menjadi **ATR ≥ 7,26%** — di atas ambang volatilitas
ekstrem (3,5%). Artinya papan akan **selalu kosong**. Saringan yang selalu menolak sama tidak
bergunanya dengan saringan yang selalu menerima.

**Akar kesalahannya:** lantai friksi adalah **cara beroperasi**, bukan syarat kelayakan. §9 justru
menemukan bahwa dengan lantai 6× trade menjadi berdurasi 1–8 jam dan **28 dari 32 pair tetap
positif**. Jadi yang perlu disaring bukan "apakah SL dipaksa lebar", melainkan "apakah koin ini
bergerak cukup jauh untuk menutup biaya".

**Versi final** memakai kriteria yang **sama dengan `PairEdgeGate`** yang sudah diuji data:
pergerakan harus ≥ **2×** biaya round-trip, dengan estimasi `ATR × √bar`.

Contoh nyata:

| ATR M15 | Estimasi gerak (√28) | ÷ biaya 0,9688% | Hasil |
|---|---|---|---|
| 1,0% | 5,29% | **5,46×** | lolos |
| 0,5% | 2,65% | **2,73×** | lolos tipis |
| 0,3% | 1,59% | **1,64×** | **gagal** — dan memang harus gagal |

Skala **akar** dipakai, bukan linier, karena `ATR × n` mengasumsikan harga bergerak searah setiap
bar — itu skenario tren sempurna. Untuk lintasan acak, simpangan tumbuh sebanding akar jumlah
langkah. Memakai skala linier akan melebih-lebihkan kemampuan koin dan membuat gate lolos terlalu
mudah. Ada test khusus yang mengunci pilihan ini.

### C.5 Exit long (buy → hold → exit = profit)

`TradePlan` membawa: `entry`, `stopLoss`, `takeProfit1`, `takeProfit2`, `riskPct`, `rewardPct`,
`netRr` (dihitung `FeeCalculator`), `frictionFloorPct`, `tp2R`, dan `exitRule`.

`exitRule` untuk SCALP dibangun dari `ScalpingRuntimeConfig.current.exit` — mode yang benar-benar
aktif. Contoh keluaran untuk mode FULL:

> Beli di area entry, tahan, keluar saat profit net mencapai target (TP2) atau kena SL. Mode Full:
> TP1 50% lalu SL ke breakeven net (0,40%), trailing 0,35% dari puncak, early-profit ≥ 0,45% net.
> Time-stop 90 bar M1.

INTRADAY: keluar sebelum sesi close malam (19:30 WIB), tidak menahan melewati tengah malam.
SWING: keluar di TP2/resistance atau saat close di bawah EMA50 D1.

---

## D. TEAM 2 — UI

### D.1 Berkas baru

| Berkas | Baris | Isi |
|---|---:|---|
| `viewmodel/TradingViewModelReadiness.kt` | 139 | papan dari cache (tanpa jaringan), refresh, permintaan data, polling 60 detik |
| `ui/components/readiness/ReadinessUi.kt` | 200 | pemetaan status→warna, chip, bilah skor, baris syarat, baris rencana |
| `ui/components/readiness/ReadinessBoardCard.kt` | 189 | ringkasan Dashboard: hitungan siap/memanas + kandidat teratas |
| `ui/components/readiness/HorizonReadinessCard.kt` | 257 | kartu 3-horizon untuk halaman detail |
| `ui/screens/ReadinessBoardScreen.kt` | 518 | papan penuh: pemilih horizon, saringan, daftar, responsif 1–2 kolom |

### D.2 Keputusan tampilan

1. **Responsif lewat lebar nyata** (`BoxWithConstraints` → 1 atau 2 kolom), bukan orientasi.
   Sebelumnya aplikasi hanya punya satu jalan untuk layar lebar: memaksa landscape — tidak berguna
   di tablet atau jendela desktop.
2. **Alasan selalu terlihat.** Setiap baris menampilkan syarat mana yang gagal dan dengan angka
   berapa. Papan yang hanya berkata "belum siap" tanpa alasan tidak bisa ditindaklanjuti.
3. **`BELUM DIVALIDASI`** tampil menempel pada horizon INTRADAY/SWING, supaya pengguna tidak
   menyimpulkan ketiganya setara dengan SCALP yang sudah diuji data.
4. **Status `SIAP` disertai peringatan** bahwa itu hasil saringan, bukan ajakan beli, dan eksekusi
   uang riil masih diblokir.

### D.3 Berkas yang diubah

| Berkas | Δ | Perubahan |
|---|---:|---|
| `MainActivity.kt` | +6 | rute `AppScreen.READINESS` |
| `model/TradingViewModels.kt` | +1/−1 | `AppScreen.READINESS` |
| `viewmodel/TradingViewModel.kt` | +24 | 4 StateFlow papan + job |
| `viewmodel/TradingViewModelNavigation.kt` | +23 | `openReadiness`, `openReadinessSymbol` |
| `viewmodel/TradingViewModelSync.kt` | +9 | hook polling saat startup |
| `ui/screens/DashboardScreen.kt` | +12 | kartu ringkasan |
| `ui/screens/DetailChartScreen.kt` | +30 | kartu 3-horizon + refresh saat pair berubah |
| `workplan2.md` | +126 | §14 |
| `AGENTS.md` | +66/−2 | bagian "MULAI DARI SINI" + aturan engine baru + aturan Git |

---

## E. Verifikasi

| | Baseline (`9e892fc`) | Sesudah |
|---|---|---|
| Test | **338** (diukur via `git stash`, bukan dari ingatan) | **364** · 0 gagal · 1 dilewati |
| Kelas test | 48 | 50 |
| APK debug | — | 26,5 MB (27.810.732 byte) |

**Test baru (+26):**

| Berkas | Jumlah | Sorotan |
|---|---:|---|
| `HorizonReadinessEngineTest.kt` | 16 | fixture deterministik; gate ekonomi ditolak untuk koin tenang **dan** diterima untuk koin wajar; bobot ganda gate ekonomi; lantai friksi & target net R:R sesuai horizon; penghalang keras; sesi intraday |
| `ReadinessScannerTest.kt` | 10 | dedup timeframe (10 pembacaan untuk 2 simbol, bukan 18); batas baris; `NO_DATA` bukan kesimpulan palsu; harga live dipakai |

### E.1 Kontrol negatif

Test yang selalu hijau tidak membuktikan apa pun. Saya lumpuhkan dua hal sekaligus dan membuktikan
test-nya benar-benar menangkap:

| Yang dilumpuhkan | Hasil |
|---|---|
| `MIN_EDGE_RATIO` 2,0 → 0,1 | ❌ `Gate ekonomi harus gagal untuk koin yang nyaris tidak bergerak` |
| `ATR × √bar` → `ATR × bar` (linier) | ❌ `expected:<5.291502622129181> but was:<28.0>` |
| keduanya (efek gabungan) | ❌ `expected:<3> but was:<4>` (jumlah syarat lolos) |

Keduanya dikembalikan dan diverifikasi ulang lewat `grep` — tidak ada sisa penanda
`NEGATIVE-CONTROL` di `app/src`.

---

## F. Batasan dan risiko

| # | Batasan | Dampak |
|---|---|---|
| 1 | **INTRADAY & SWING belum divalidasi keluar-sampel** | Ambangnya masuk akal tetapi **belum dibuktikan punya edge**. UI menandainya. Jangan dipromosikan ke uang riil tanpa uji data. |
| 2 | **Papan membaca cache, bukan mengambil data** | `MtfCacheManager` hanya mem-prefetch M1/M15/H1/H4 — **D1 tidak pernah**, sehingga SWING hampir selalu `DATA KURANG` sampai pengguna menekan "Ambil data candle" (dibatasi 8 simbol sekali tekan). |
| 3 | **Tanpa orderbook** | Keputusan entry scalping yang sebenarnya tetap milik `ScalpingMtfEvaluator`. Mesin ini menyaring koin, bukan menggantikan evaluator. |
| 4 | **Belum ada eksekusi otomatis dari papan** | Papan hanya menilai. Auto-buy paper tetap lewat `PaperAutoTradeCoordinator` dan tetap mensyaratkan `action == BUY`. |
| 5 | **Polling 60 detik** | Menambah kerja CPU saat aplikasi terbuka. Dibatasi 40 simbol × 3 horizon per pemindaian. |
| 6 | **`requestReadinessData` memicu jaringan** | Dibatasi 8 simbol sekali tekan; tanpa batas itu pengguna bisa memicu puluhan permintaan beruntun. |

**Kesiapan uang riil tetap BELUM.** Ini menambah *kejujuran penyaringan*, bukan menambah edge.
Profil exit masih skew negatif dan gate ketat keluar-sampel hanya 5 pair.

---

## G. Langkah berikutnya

Diurutkan nilai dibagi risiko (detail di `docs/PANDUAN-LANJUT-AGEN.md` §4):

1. **P4.1 — walk-forward antar-bulan.** Validasi saat ini hanya satu potongan train/test; satu
   potongan bisa kebetulan menguntungkan. Pertanyaan yang harus dijawab: apakah tanda positif
   bertahan di **semua** jendela?
2. **P4.4 — rehidrasi FSM paper: pulihkan progres** (`barsHeld`/`isBeArmed`/`isPartialDone`/`peak`
   masih reset saat restart).
3. **P4.2 — preferensi maker untuk pair IDR** (taker 0,8144% vs maker 0,6144%). Mengubah angka net
   R:R di seluruh UI — ukur ulang, jangan hanya ganti konstanta.
4. **P5 — data Indodax + replay TAPI v2.**
5. **P6 — auto-execute uang riil: DIBLOKIR** sampai 1 dan 4 selesai.

---

## Lampiran — berkas baru (baris)

```
engine/readiness/TradeHorizon.kt            104
engine/readiness/HorizonReadiness.kt        128
engine/readiness/HorizonReadinessEngine.kt  629
engine/readiness/ReadinessScanner.kt         81
viewmodel/TradingViewModelReadiness.kt      139
ui/components/readiness/ReadinessUi.kt      200
ui/components/readiness/ReadinessBoardCard.kt 189
ui/components/readiness/HorizonReadinessCard.kt 257
ui/screens/ReadinessBoardScreen.kt          518
test/.../HorizonReadinessEngineTest.kt      334
test/.../ReadinessScannerTest.kt            176
docs/PANDUAN-LANJUT-AGEN.md                 213
```
