# Temuan Bug Mode INTRADAY (`StrategyMode.OFFICE_DAILY`)

**Dibuat:** 2026-10-27 · **Branch:** `main` · **Versi app saat itu:** 3.9.2 (`versionCode 91`)

Berkas ini memuat temuan yang **masih hidup**. Temuan yang sudah ditutup dipangkas dari badan
dokumen dan diringkas di tabel "Sudah ditutup" (commit + berkas/uji penjaga).

| Paket | Topik | Status |
|---|---|---|
| **A** | Kebenaran angka biaya di replay (gross → net, preset legacy, taker/maker, partisi menang/kalah) | **TUTUP** (2026-10-27) |
| **B** | Jam sesi & gate R:R palsu (selang fase, jam evaluasi, TP1 tanpa clamp, klaim "72 jam", config intraday) | **TUTUP** (2026-10-27) |
| **C** | Geometri SL/TP tidak pernah lolos gate biaya (SL −1,5% / TP1 +2,8% ⇒ net R:R TP1 ≈0,87 di Indodax IDR, ≈0,85 di Tokocrypto IDR, ≈0,74 di USDT — vs ambang 1,5) | **TERBUKA** |
| **D** | Alat replay/ukur belum memakai fee truth & belum menutup trade di akhir jendela | **TERBUKA** |
| **E** | Mesin exit intraday (belum ada; exit hanya sesi Close Malam) | **TERBUKA** |
| **F** | UI & dokumen: kartu intraday belum menyebut basis net / sampel | **TERBUKA** |

---

## C — Geometri SL/TP tidak pernah lolos gate biaya (TERBUKA)

**Gejala terukur.** Setelah paket A+B, replay intraday menghasilkan **0 sinyal BUY** pada semua
dataset uji (BTC/IDR live 279 bar H1 dan tiga jendela riil September 2026). Sebelum perbaikan,
dataset yang sama menghasilkan 6 / 9 / 5 / 6 BUY.

**Sebab aritmetis, bukan bug baru.** Geometri di `IntradayEvaluator` adalah:

- `calculatedSl = max(support − 0,3·ATR, price × 0,972).coerceAtMost(price × 0,985)` ⇒ paling rapat **−1,5%**
- `calculatedTp1 = (price + 1,25·ATR).coerceIn(price × 1,028, price × 1,050)` ⇒ paling rapat **+2,8%**

Dengan biaya all-in Indodax IDR (taker dua sisi + slippage dua sisi, impas net **0,7940%**), dihitung
dengan rumus yang sama dengan app (`FeeCalculator.roundTrip`, multiplikatif):

| Komponen (Indodax IDR, geometri paling rapat) | Nilai |
|---|---|
| Risiko net (SL −1,5%) | ≈ **2,28%** |
| Imbalan net (TP1 +2,8%) | ≈ **1,99%** |
| Net R:R TP1 | ≈ **0,87** (terukur 0,82–0,87 di riwayat uji; Tokocrypto IDR ≈ **0,85**; Tokocrypto USDT ≈ **0,74**) |
| Ambang gate (`IntradayConfig.minNetRrTp1`) | **1,5** |

Koreksi (28 Okt 2026, review owner): laporan sebelumnya menulis "≈0,74 pada biaya Indodax IDR" —
angka itu hasil perhitungan **Tokocrypto USDT** (biaya 0,9735%) yang keliru dipindah ke konteks
Indodax. Angka Indodax IDR yang benar ≈0,87; tabel di atas dan `workplan2.md` §79 sudah diperbaiki.

Bahkan di ujung atas band TP1 (+5,0%), R:R maksimum hanya ≈1,83 (Indodax IDR) / ≈1,63 (USDT) —
ambang 1,5 hanya bisa dilampaui ekor atas band, itulah sebabnya BUY praktis tidak mungkin.

Jadi gate **bekerja benar** dan menolak seluruh sinyal. Yang salah adalah geometrinya, bukan
gate-nya. **Jangan menurunkan ambang** untuk mengembalikan jumlah sinyal: itu mengembalikan gate
palsu yang baru saja ditutup (paket B3).

> ⚠️ **Sampai paket C selesai: sinyal BUY intraday = 0.** Sinyal intraday **jangan dipakai**
> sebagai dasar keputusan (beli/jual/tahan) sampai geometrinya diperbaiki dan hasilnya
> diukur ulang. Ini bukan bug baru — tepatnya perilaku gate yang jujur, dan berkas ini
> yang mencatatnya.

**Yang perlu diputuskan (butuh instruksi owner):** apakah TP1 dinaikkan (mis. ≥ +3,6%) atau SL
dilebarkan/dihitung dari ATR dengan lantai friksi, atau gate dipindah ke TP2 dengan konsekuensi
TP1 tidak lagi menjadi target realistis. Semua pilihan mengubah output sinyal, jadi **tidak
dikerjakan tanpa persetujuan**.

**Catatan tambahan yang ditemukan saat menguji (belum diperbaiki, kandidat C):**
`IntradayEvaluator` memakai `macdHist >= -0,002`, sedangkan `macdHist` adalah histogram MACD dalam
**satuan harga** (mis. −1.656.379 pada BTC/IDR), bukan persen. Praktisnya syarat itu berarti
"histogram MACD tidak negatif", dan ambangnya tidak berskala. Arahan owner (review 28 Okt 2026):
**ditangani di paket C** — unitnya diperbaiki (dinormalisasi atau ambangnya dihapus), lalu
dampaknya ke frekuensi sinyal **diukur**; tidak boleh dibiarkan dan tidak boleh diubah diam-diam.

## D — Alat replay/ukur belum memakai fee truth & belum menutup trade di akhir jendela (TERBUKA)

`IntradayReplayEngine` sekarang memakai `FeeSchedule.netProfitPct` (paket A) dan **mewajibkan**
`feeConfig` dioper pemanggil, jadi preset legacy tidak bisa terpakai lagi. Yang belum ada:

1. **Alat CLI/offline** untuk menjalankan replay intraday pada dataset riset (`data/tokocrypto_v7`,
   11 pair × 15m/1h/1m, 999 bar H1, 2026-09-15 20:00 → 2026-10-27 10:00 UTC). Saat ini replay hanya
   bisa dijalankan dari unit test (dan sebagian test menarik data live Indodax).
2. **Penutupan trade di akhir jendela** belum dibedakan dari `LOOKAHEAD_EXPIRY`. Trade yang tidak
   menyentuh SL/TP/sesi ditandai `UNRESOLVED` dan **dihitung sebagai kalah** (bukan dibuang) —
   itu benar untuk akuntansi, tetapi laporan belum memisahkan berapa yang benar-benar "tidak
   tuntas" versus "ditutup di akhir data".
3. **Tidak ada kontrol negatif berbasis dataset riset**: angka paket A+B dibuktikan lewat uji
   unit, belum lewat replay 11 pair.

## E — Mesin exit intraday belum ada (TERBUKA)

Mode intraday hanya punya satu exit: **SELL paksa di sesi Close Malam (19:30–23:30 WIB)** dan
TP1/TP2/SL statis di replay. Tidak ada trailing, break-even, atau time-stop khusus intraday.
`PaperExitStateMachine` yang ada adalah produk **scalping** (aturan area `app/AGENTS.md`), dan
`tryPaperAutoBuy` memang digerbang `StrategyMode.SCALPING` — jadi posisi intraday **tidak** memakai
mesin itu.

Riset exit-only untuk scalping sudah ditutup dua kali dengan hasil negatif (0/88 varian, 0/90 sel).
**Jangan** menyimpulkan dari situ bahwa exit intraday juga mustahil; tetapi juga **jangan** membangun
mesin exit intraday tanpa instruksi owner dan tanpa pengukuran tersendiri.

## F — UI & dokumen belum menyebut basis net / sampel (TERBUKA)

Kartu-kartu mode intraday belum diubah pada paket A+B. Yang perlu diselaraskan bila paket ini
dikerjakan:

- setiap angka laba/rugi intraday wajib menyebut **net** dan komponennya (fee beli, fee jual,
  slippage) — frasa "sudah bersih fee & pajak" dilarang (`app/AGENTS.md`, temuan J7);
- label "Net R:R" di kartu harus menyebut **TP1**, karena itulah basis gate sekarang;
- teks fase sesi wajib memakai selang setengah-terbuka yang sama dengan engine (11:30 milik Siang,
  15:30 Sore, 19:30 Close Malam, 23:30 Istirahat);
- klaim "72 jam" pada teks apa pun harus diganti jumlah candle (`IntradayConfig.flashDumpTraumaCandles`
  = 12 candle; ≈48 jam pada candle H4, ≈12 jam pada H1).

---

## Sudah ditutup

| Butir | Perbaikan | Bukti |
|---|---|---|
| **A1** Replay memakai P&L **gross** dikurangi fee **aditif** tanpa slippage | `IntradayReplayEngine.netPnlPct` → `FeeSchedule.netProfitPct` (multiplikatif, taker + slippage dua sisi) | `netPnlMemakaiFeeBursaPlusSlippage` |
| **A2** Exit Close Malam dinilai menang dari **gross** (`pnl >= 0`) | `classifyCloseMalam(netPnlPct)` + `isWin(net)` | `closeMalamMemakaiNet_bukanGross` |
| **A3** `feeConfig = TradingFeeConfig()` (preset legacy 0,11/0,21/0,32/0,42) sebagai bawaan | parameter `feeConfig` **wajib** tanpa nilai bawaan; semua pemanggil memakai `FeeSchedule.configFor(...)` | kompilasi + `RealBtcIndodaxIntradayReplayTest` |
| **A4** R:R intraday dinilai dengan tarif **maker** | `IntradayConfig.useMakerFee = false` diteruskan ke `FeeCalculator.roundTrip` dan `ConfluenceEvaluator` | `ambangGateMemakaiConfig_bukanLiteral` + `feeMakerDanSlippageDariConfig_ikutMengubahNetRr` (uji **perilaku** di dataset ter-commit) |
| **A5** `validEntries`/`falseSignals` dinaikkan di dalam loop; trade `AMBIGUOUS` tidak pernah dihitung | partisi lengkap: `validEntries = trades.count { isWin(it.netPnlPct) }`, `falseSignals = trades.size − validEntries`; profit factor net | `ringkasanReplayMemakaiNetDanPartisiLengkap` (dataset DOGEUSDT) + `labelPeristiwaDanKlasifikasiNet_tidakBisaBertentangan` |
| **B1** Batas fase tumpang tindih (`691..930`, `931..1170`, …) | selang setengah-terbuka `[mulai, selesai)` dengan konstanta bernama | `batasFaseSesi_setengahTerbuka`, `setiapMenitMasukTepatSatuFase` |
| **B2** Fase sesi dihitung dari **timestamp buka candle** (menggeser fase ≈4 jam pada H4) | `ModeSignalEvaluator.evaluateIntraday` meneruskan `nowMs`; replay memakai `timestamp + durasi bar` | `evaluatorMemakaiJamSekarang_bukanTimestampCandle` |
| **B3** `netRr` dari **TP2** lalu `coerceAtLeast(1.6)` ⇒ gate `>= 1.5` selalu benar | `netRrTp1` dari TP1 **tanpa clamp**; gate memakai `config.minNetRrTp1`; label "Min 1:1.8" diganti nilai config | `netRrDariTp1_tidakDiClamp`, `ambangGateMemakaiConfig_bukanLiteral` |
| **B4** Klaim "72 jam" padahal kode memindai 18 candle & trauma 12 candle | komentar, doc KDoc, label reasoning, dan laporan replay menyebut **12 candle** (= 48 jam pada H4) | `IntradayEvaluator` + laporan replay |
| **B5** Tidak ada config intraday; parameter gate tersebar sebagai literal | berkas baru `engine/intraday/IntradayConfig.kt` — **tanpa** state global/slider, semua nilai dioper eksplisit | dipakai `IntradayEvaluator` + `IntradayReplayEngine` |

**Angka terukur paket A+B (2026-10-27, PC, JDK 17, Gradle 9.7):**

| Ukuran | Sebelum | Sesudah |
|---|---|---|
| Test intraday | 12 hijau | **20 hijau** (8 test baru) |
| Seluruh unit test | **833** hijau, 0 gagal | **841** hijau, 0 gagal |
| `test01` (BTC/IDR live 279 bar H1) | 6 BUY · 0 false · WR **100%** · avg net **−0,21%** | 0 BUY · 0 trade · WR 0% |
| `test07/08/09` (September 2026) | 9 / 5 / 6 BUY | 0 / 0 / 0 BUY |
| Gross +0,30% di Indodax IDR | tampak untung | net **−0,49%** ⇒ kalah |

Penurunan sinyal ke nol **bukan regresi**: itu gate R:R yang sekarang jujur (lihat paket C).

**Kontrol negatif yang sudah dijalankan (batch A+B, 2026-10-27):** mengembalikan
`coerceAtLeast(1.6)`, menilai Close Malam dari `grossPnlPct`, dan mengoper `evaluationTimestamp = null`
membuat **4 test gagal** (`netRrDariTp1_tidakDiClamp`, `ambangGateMemakaiConfig_bukanLiteral`,
`ringkasanReplayMemakaiNetDanPartisiLengkap`, `evaluatorMemakaiJamSekarang_bukanTimestampCandle`).
Setelah dipulihkan, seluruh 20 test intraday hijau kembali.

**Kontrol negatif tambahan (28 Okt 2026, saat uji pindai sumber diganti uji perilaku)**
angka diukur dari run `:app:testDebugUnitTest`:

| Provokasi (perbaikan dimatikan) | Hasil terukur |
|---|---|
| Gate memakai literal 1,5 + fee/slip config diabaikan | **3 test gagal**: `ambangGateMemakaiConfig_bukanLiteral`, `feeMakerDanSlippageDariConfig_ikutMengubahNetRr`, `ringkasanReplayMemakaiNetDanPartisiLengkap` |
| Menang/kalah dihitung dari `grossPnlPct` lagi | `ringkasanReplayMemakaiNetDanPartisiLengkap` gagal (1/10) |
| Label peristiwa TP↔SL ditukar di replay engine | `labelPeristiwaDanKlasifikasiNet_tidakBisaBertentangan` gagal (1/10) |
| Fallback `System.currentTimeMillis()` dikembalikan di `ModeSignalEvaluator` | `nowMs tidak valid ditolak fail-closed, bukan ditutupi jam dinding` gagal (1/5) |

Dipulihkan → 22 test intraday + 5 `ModeSignalEvaluatorTest` hijau (27/27, 0 gagal).

**Penjaga:** `app/src/test/java/agu/analys/engine/intraday/IntradayAccountingAndSessionTest.kt`
(10 test — seluruhnya uji perilaku di atas `data/tokocrypto_v7/DOGEUSDT_1h.csv` ter-commit, tanpa
uji pindai sumber), `IntradayEvaluatorTest` (3), `RealBtcIndodaxIntradayReplayTest` (9).

---

## § Riwayat

- **2026-10-28 — Review owner atas commit `7c8d822`.** Temuan review dan tindak lanjutnya:
  1. **Angka R:R 0,74 salah konteks.** Tabel paket C sendiri memberi 2,01/2,29 ≈ 0,87; angka 0,74
     ternyata hasil biaya Tokocrypto USDT (0,9735%) yang keliru dipindah ke konteks Indodax IDR.
     Dikoreksi di berkas ini, `docs/AGENTS.md`, `app/AGENTS.md`, dan `workplan2.md` §79, pakai angka
     multiplikatif tepat (risiko net 2,28% / imbalan net 1,99% ⇒ **0,87**). Pesan commit `7c8d822`
     berisi 0,74 juga — tidak di-rewrite (sudah di-push); koreksinya tercatat di sini.
  2. **Uji pindai sumber diganti uji perilaku.** Semua `sumber.contains("...")` dihapus; penggantinya
     lari di atas dataset riset ter-commit (`data/tokocrypto_v7/DOGEUSDT_1h.csv`): ambang 0,0 vs
     99,0 membuktikan gate membaca config (77 → 0 sinyal), maker + slippage dari config membuktikan
     fee dioper, dan ringkasan net dihitung ulang dari trade rekaman. `println` debug dan
     asersi tautologi (`assertTrue(gross >= 0.0)` atas konstanta) dihapus. Kontrol negatifnya
     diukur (tabel di atas).
  3. **Konsistensi label ↔ net** dijaga `labelPeristiwaDanKlasifikasiNet_tidakBisaBertentangan`
     (dataset + invarian geometri 3 preset) — `outcome` tetap peristiwa (aturan A5), klasifikasi
     tetap net.
  4. **`nowMs <= 0` tidak lagi ditutupi `System.currentTimeMillis()`**: `ModeSignalEvaluator`
     kini fail-closed (null), deterministik. Penjaga ditambah di `ModeSignalEvaluatorTest`.
  5. **macdHist:** arahan owner — ditangani di **paket C**, unit diperbaiki + dampak frekuensi
     diukur, jangan dibiarkan.
  6. `Sinyal BUY intraday = 0` sampai paket C selesai — **sinyal intraday jangan dipakai untuk
     keputusan**; dicatat jelas di atas.
  Test: 27 (intraday 22 + ModeSignalEvaluatorTest 5) hijau, 0 gagal, plus kontrol negatif 3+1+1+1.

- **2026-10-27 — Paket A+B ditutup.** Replay intraday berhenti menilai diri sendiri dengan angka
  gross; gate R:R dihitung dari TP1 tanpa clamp; fase sesi memakai selang setengah-terbuka dan jam
  sekarang; config intraday terpisah dari slider scalping. Konsekuensi yang dilaporkan apa adanya:
  **0 sinyal BUY** pada semua dataset uji karena geometri SL/TP masih menghasilkan net R:R ≈0,87
  dengan biaya Indodax IDR (≈0,74 di USDT) (paket C terbuka). Tidak ada perubahan pada engine
  scalping; `ConfluenceEvaluator` hanya mendapat parameter `useMaker`/`slippagePct` dengan bawaan
  lama, sehingga SWING tidak berubah.
