# AGENTS.md — aturan area `docs/` (dokumen & eksperimen)

Berkas ini dipisahkan dari `AGENTS.md` akar pada **2026-10-27** supaya aturan area tidak ikut
dimuat pada setiap permintaan. Isinya **salinan apa adanya** dari aturan yang dulu ada di akar.

**Dibaca kapan:** setiap kali menyentuh `docs/`, menulis laporan, memindahkan temuan bug,
menjalankan/menulis eksperimen, atau mengutip angka hasil. Aturan penulisan Markdown, Git, dan
"yang tidak boleh dilakukan agen" tetap di `AGENTS.md` akar.

---

## Folder temuan bug (`docs/bugs/`)

**Sumber kebenaran status temuan audit.** Setiap berkas berisi **hanya temuan yang masih hidup**
di badan dokumen; temuan yang sudah ditutup diringkas di tabel "Sudah ditutup" (commit + pointer
laporan). ⚠️ **Status di sini sudah diverifikasi langsung ke kode dan direview owner pada
2026-10-16** pada `patch` @ `547048d` — **agen tidak perlu cek kode untuk memastikan statusnya.**
Verifikasi ulang hanya perlu kalau: (a) kamu akan **memperbaiki** butir yang masih hidup, atau
(b) ada commit baru setelah `547048d` yang menyentuh area terkait (buka `git log --oneline -5`).

Aturan main:

- **Sebelum memperbaiki bug** di area yang pernah diaudit, **baca berkas temuannya dulu** —
  supaya tidak mengulang audit, tidak menambal gejala, dan tidak menghapus bagian yang memang
  sudah benar (setiap berkas punya bagian "Yang sudah benar — jangan diutak-atik").
- **Setelah sebuah temuan diperbaiki**, **pangkas butir itu dari badan dokumen**, pindahkan ke
  tabel "Sudah ditutup" (commit + pointer laporan), perbarui tabel status di header, dan
  tambahkan baris ke § Riwayat. Jangan biarkan badan dokumen memuat butir yang sudah tidak hidup
  — pembaca berikutnya akan membuang waktu memperbaikinya ulang.
- **Aturan pemindahan (sejak 2026-10-17).** Begitu **seluruh** isi sebuah berkas temuan tertutup,
  berkas itu **tidak boleh tinggal di `docs/bugs/`**. Pindahkan dengan `git mv`:

  ```
  docs/bugs/temuan_bugs_<topik>.md  →  docs/laporan_temuan_bugs_<topik>.md
  ```

  Berkas hasil pindahan **adalah laporannya**. Tidak perlu membuat `docs/LAPORAN-batch*.md` baru:
  ringkasan tiap batch ditulis ke § Riwayat berkas itu dan ke `workplan2.md`. `docs/bugs/` hanya
  boleh berisi temuan yang masih hidup.
- **Jangan menambah laporan per batch.** Berkas `docs/LAPORAN-batch*-*.md` yang sudah ada adalah
  arsip historis — biarkan, jangan diperbanyak. Kalau butuh mencatat pekerjaan, tambahkan baris
  ke berkas laporan temuan yang bersangkutan (atau ke `workplan2.md`).
- Daftar berkas di folder ini bisa bertambah untuk area lain (exit, P&L, UI, dsb.) dengan
  pola penamaan `temuan_bugs_<topik>.md`.

Saat ini berisi **satu** berkas temuan hidup: **`temuan_bugs_intraday.md`** (dibuat 2026-10-27;
paket A+B sudah tutup, paket C–F masih terbuka). Sebelumnya folder ini kosong sejak 2026-10-18.
Riwayat
berkasnya:

- **`temuan_bugs_paper_trade.md` → DIPINDAHKAN 2026-10-18** ke
  **`docs/laporan_temuan_bugs_paper_trade.md`** (batch 6o — **seluruh P1–P9 tuntas**).
  Ringkas: P1–P4 (batch 3b/3c), P5 (batch 5d), P6 bagian 1 (batch 5c), P8 (batch 6d), P7
  (batch 6e), lalu **P6 bagian 2 + P9 ditutup batch 6o** — `peak` kini melihat high intrabar M1
  (`PaperIntrabarPeakResolver`), dan mematikan auto-exit saat runtime menyerahkan posisi ke jalur
  auto-sell lama (`PaperHandBackResolver` + `handBackAllPaperEnginesToLegacy()`).
  **Jangan membuat ulang berkas di `docs/bugs/` dengan nama itu.**
  **Batas penting:** seluruh temuan `P*` hanya menyentuh posisi **paper** (`isReal = false`) —
  bukan kerusakan uang riil, tetapi posisi paper adalah dasar seluruh keputusan "apakah scalp
  punya edge".
- **~~`temuan_koneksi_tokocrypto_indodax.md`~~ — DIPINDAHKAN 2026-10-18** ke
  **`docs/laporan_temuan_bugs_koneksi_tokocrypto_indodax.md`**. Seluruh K1–K15 tertutup
  (batch 6j–6n), jadi berkasnya keluar dari `docs/bugs/` dan sekarang berperan sebagai
  laporannya. **Jangan membuat ulang berkas di `docs/bugs/` dengan nama itu.**
  Yang berlaku sekarang: daftar pair Tokocrypto **hanya** dari `common/symbols` resmi (864 simbol,
  `data.list`); `exchangeInfo` gateway (3.729 simbol Binance) tidak boleh dipakai untuk discovery.
  Gate listing dan `type` dibaca fail-closed di `TokocryptoSymbolFeed`. WebSocket Indodax memakai
  kanal `market:summary-24h` dengan token utuh 105 karakter; kanal `chart:tick-<pair>` tidak pernah
  menerbitkan data. Socket usang disambung ulang lewat `reconnectIfStale()` → `reconnectNow()`,
  **bukan** lewat `start()` — `start()` menjalankan `stop()` dan membuang candle menit berjalan.
  Ambang usang ada di `RealtimeSocketHealth` (zombie 20 detik, diam 6 × interval ping), bukan 30
  detik frame. Kebijakan sambung-ulang ada di `ReconnectBackoff` (reset hanya setelah stabil,
  jitter ±20%, jeda menyerah 5 menit), status live dipisah per bursa (`wsLiveToko`/`wsLiveIndodax`),
  worker latar & `fetchTickers` Indodax hanya menyentuh watchlist/pair yang diminta
  (`/api/ticker/<pair>`, bukan `/api/summaries`), dan `getSymbolType` mengembalikan
  `SYMBOL_TYPE_UNKNOWN = 0` (bukan default `1`) dengan log sekali per simbol.
  Watchlist hanya menerima simbol yang ada di bursa aktif (`WatchlistEntryGate` + `hasSymbol`
  di kedua repository; fail-closed, tanpa fallback "simpan dulu"), dan dashboard memakai satu
  gate listing `DashboardRanking.symbolListed` — `uniqueTicks` disaring sekali di awal, quick
  filter WATCHLIST dan fallback statis ikut tersaring.
- **~~`temuan_bugs_jurnal_pnl.md`~~ — DIHAPUS 2026-10-17.** Seluruh J1–J10 sudah tertutup:
  batch 3a (J1, J2), batch 4 (J7), batch 5a (J4 + N10), batch 5b (J3), batch 5e (J6 + N11),
  batch 5f (J5), batch 6a (J10 sisa — `PaperArmSignal`), batch 6b (J9 — `TradeSignalConfidence`),
  batch 6c (J8 — `RealTradePnlResolver`). Tidak ada butir hidup, jadi berkasnya tidak punya alasan
  untuk tetap ada — jejak auditnya ada di laporan per batch + riwayat git.
  **Jangan membuat ulang berkas ini.** Kalau muncul temuan baru di area jurnal, buat berkas temuan
  baru di `docs/bugs/` (`temuan_bugs_<topik>.md`) dan catat di `workplan2.md`; jangan menghidupkan
  kembali "dokumen temuan" kosong.
  **Catatan angka yang tetap berlaku:** friksi FSM Tokocrypto USDT yang tertulis 0,8226% di dokumen
  temuan awal itu **salah**; angka yang benar **0,9735%** (lihat `docs/LAPORAN-batch4-j7-2026-10-16.md` §2).
- **~~`temuan_bugs_notifikasi.md`~~ — DIPINDAHKAN 2026-10-17** ke
  **`docs/laporan_temuan_bugs_notifikasi.md`**. Seluruh N1–N17 tertutup (batch 1, 2, 5, 6b, 6f, 6g,
  6h, 6i), jadi berkasnya keluar dari `docs/bugs/` dan sekarang berperan sebagai laporannya.
  **Jangan membuat ulang berkas di `docs/bugs/` dengan nama itu**, dan jangan menambah laporan
  batch baru untuk area ini — tambahkan baris ke § Riwayat berkas laporan tersebut.
  **N11** ditutup dengan **tidak menampilkan** tabel tier lintas-mode; cap confidence
  `ScalpingMtfEvaluator` sengaja **tidak** diubah. **N6 + N17** ditutup dengan satu titik sambung evaluasi
  (`engine/ModeSignalEvaluator.kt`): worker hanya menghitung mode aktif, dan penalti anomali makro
  dihitung di dalam titik sambung itu dari candle D1.

- **`temuan_bugs_intraday.md` — DIBUAT 2026-10-27.** Mode INTRADAY (`OFFICE_DAILY`) belum pernah
  masuk audit mana pun. Paket **A+B** langsung ditutup di berkas yang sama (angka biaya replay,
  jam sesi, gate R:R palsu, config intraday terpisah); **C–F masih terbuka** — geometri SL/TP
  (net R:R TP1 ≈0,87 di Indodax IDR / ≈0,85 Tokocrypto IDR / ≈0,74 di USDT, vs ambang 1,5,
  sehingga sinyal BUY nol — koreksi angka 28 Okt 2026), alat replay/ukur, mesin exit, dan UI.
  Berkas tetap di `docs/bugs/` sampai C–F tuntas. Rincian: `workplan2.md` §79–§80.

## Folder eksperimen (`docs/exp/`)

Berisi **hasil eksperimen terukur** — satu berkas per eksperimen, bernama
`exp_<testname>_<pair>.md`. Berbeda dari tiga folder lain di `docs/`:

| Folder | Isi | Aturan |
|---|---|---|
| `docs/bugs/` | temuan audit yang **masih hidup** | `temuan_bugs_intraday.md` (C–F terbuka) |
| `docs/laporan_temuan_bugs_*.md` | laporan audit **yang sudah tutup** | satu per topik |
| `docs/LAPORAN-*.md` | jejak eksekusi batch (arsip) | jangan ditambah, kecuali laporan port eksperimen BERHASIL |
| **`docs/exp/`** | **hasil eksperimen** — angka yang sudah diukur | tambah bebas, satu per eksperimen |

**Aturan isi — ini yang membedakan `docs/exp/` dari dokumen lain:**

- **Angka wajib diukur, bukan karangan.** Tulis command-nya, dataset-nya, dan hasilnya.
  Kalau angka belum diukur, tulis "belum diukur" — jangan menebak.
- **Sertakan angka negatif.** Eksperimen yang gagal adalah informasi paling berharga di
  folder ini; laporan yang hanya berisi hasil bagus tidak berguna.
- **Tulis batasan eksperimen secara eksplisit** — cakupan config, jumlah pair, rentang data,
  dan apa yang **tidak** dibuktikan. Pembaca berikutnya harus bisa menilai apakah hasil itu
  masih berlaku.
- **Tunjuk eksperimen terkait** di laporan lain agar angka tidak berbeda antar dokumen.
- Nama berkas memakai pair yang dites; kalau multi-pair, pisahkan dengan `-`
  (contoh: `exp_scalping_expectancy_ADA-DOGE-XRP.md`).

**Contoh isi saat ini:** `exp_scalping_expectancy_ADA-DOGE-XRP.md` — eksperimen lama: win rate
42,7% (8 jam) dan 45,2% (+48 jam) vs ambang 60,1% pada SL 1,94% / TP2 2,42%, tanpa time-stop.
Pengulangan parity ada di `exp_scalping_expectancy_parity_ADA-DOGE-XRP.md`: **14,88%**,
avg R **−0,4898**, 963 dari 1.149 trade TIMEOUT, ambang impas payoff **55,08%**. Kesimpulan
tetap: scalping di Tokocrypto tidak layak untuk engine saat ini; hipotesis "trade UNRESOLVED =
tren berlanjut" juga terbantah (92 WIN vs 92 LOSS).

Perbandingan P0 ada di `exp_p0_gatekeeper_ADA-DOGE-XRP.md`: baseline **996 trade / WR 12,85% /
avg R −0,1642** vs P0 **620 trade / 14,84% / −0,1569**, dengan **616 TIMEOUT, 4 SL, 0 TP1/TP2**.
Gate MTF dan BTC bekerja (frekuensi turun 37,8%), tetapi **avg R masih negatif** dan perbaikan
+0,0073R berada di dalam noise. Angka WR **14,84%** di laporan P0 **bukan** pengulangan angka
**14,88%** parity: geometri exit dan komposisi trade berbeda, jadi jangan dibandingkan langsung.

Eksperimen rerata exit: `exp_exit_modes_ADA-DOGE-XRP.md` (2026-10-22) — MFE/MAE dari bar masuk
sinyal (alat `scalp_replay.py` kini mengeluarkan `mfe_/mae_` 30/60/90 menit + `summary.entry_mfe`)
dan mode exit **EARLY** (varian `scalp_config_exit_early_p0.json`). Hasil terukur: median MFE
90 menit **+0,506%** (positif tapi hanya ≈ setengah friksi); ≥TP1 hanya 1/618 sinyal; EARLY
mengunci 53 trade (+0,3208R) + 28 BE, tetapi avg R hanya **−0,1522 (−0,1569 klasik)** —
**+0,0047R, di dalam noise**. Kesimpulan: mode exit pada unit risiko 5,81% tidak menciptakan
edge; ambang impas masih WR ~50% vs hit rate ~15%. Saat itu penjaga parity **22 test** (kini
**30**).

Eksperimen paritas entri: `exp_entry_parity_kriptoyoi_ADA-DOGE-XRP.md` (2026-10-22) — app vs
spek kriptoyoi frontend (`app.js` `generateScalperSignals`, port di `tools/entry_parity_kriptoyoi.py`)
pada data + fee truth identik. **Entri BUY tidak sama**: kriptoyoi menandai 15.269 entri vs 620
(≈24,6×), irisan hanya 77 (12,4% entri app, Jaccard ≈0,5%), dan entri kriptoyoi terukur lebih
buruk (WR 10,33%, avg R −0,1627). Lapisan A kriptoyoi **tidak di-port**; jangan mengutip angka
kedua sumber sebagai satu kebenaran.

Eksperimen exit net-TP: `exp_exit_net_tp_ADA-DOGE-XRP.md` (2026-10-22) — mode replay-only
`net_tp` / `net_tp_stop` di `tools/scalp_replay.py` + grid `tools/exit_grid.py` (evaluasi gate
sekali per pair, kontrol `classic` identik CLI 620/14,84%/−0,1569). **0 dari 88 varian positif**:
TP kecil menaikkan WR sampai 75,7% tetapi avg R tetap negatif; stop rapat tidak menolong karena
friksi aditif (TP net 0,4% vs stop kotor 2,0% ⇒ rasio net ≈1:7). Batas atas membuktikan
exit-only mustahil: pada 1440m hanya 72,0/54,5/60,4% sinyal pernah mencapai TP net 1,0% (exit
sempurna, tanpa stop) sedangkan WR impas 76,8/72,6/72,8%. Kriteria owner "1 exit net/hari" tidak
tercapai konsisten (maksimum 54,6% hari; frekuensi tertinggi 1,82/hari dengan net −2,06%/hari).
**Line exit-only ditutup** untuk sinyal P0; perbaikan harus dari entry/universe dan menunggu
instruksi owner. Saat eksperimen itu penjaga parity **26 test** (kini **30**); mode ini belum
ada di app dan tidak boleh disambungkan tanpa instruksi (stop rapat melanggar lantai friksi 6×).

Sweep lantai friksi: `exp_friction_multiple_ADA-DOGE-XRP.md` (2026-10-22) — `tools/friction_sweep.py`
mengubah **hanya** `min_friction_multiple` (data, gate P0, fee, exit sama; kontrol mult 6,0
identik CLI 620/14,84%/−0,1569). **0 dari 27 kombinasi untung.** Hold 420m (rata 3 pair):
mult 3,0 **−0,993 %/hari** (terbaik), 6,0 −1,078, 1,0 −1,735; mult 0,5 degenerate karena
`tp2/risk` melewati `max_tp2_r`. Biaya per trade ≈ satu friksi (−0,65 s.d. −0,75%) di semua
kelipatan ⇒ entry tanpa edge, lantai friksi bukan penyebabnya. **R menyesatkan:** mult 8
avg R terbaik (−0,096) tetapi uang lebih buruk daripada mult 3 (−0,230R, net −0,667/trade).
Default 6,0 dipertahankan dengan alasan baru (plateau uang, stop-out lebih jarang), bukan klaim
lama −1,8R.

Sweep 5 mode exit: `exp_exit_mode_sweep_ADA-DOGE-XRP.md` (2026-10-24) — owner membuka kembali
riset exit secara terbatas; `tools/exit_mode_sweep.py` menguji `classic`, `tp1_be`, `early`,
`trail`, `full` pada hold 90/240/420 dengan gate P0 dan fee yang sama. Evaluasi sekali,
bandingkan dua cara: *sequential* (paritas CLI) dan *same-entry* (N identik). **0 dari 90 sel
pair × mode × hold positif; 0 dari 30 sel agregat positif.** Kontrol `classic` 90m identik CLI
620/14,84%/−0,1569. Sel terbaik `early` 420m XRPUSDT tetap −0,666%/trade. WR bisa naik sampai
44,99% (`trail` 420m sequential) tetapi net/trade tetap negatif; menahan lebih lama hanya
mengurangi frekuensi friksi (−3,12 → −1,08 %/hari). Semua CI 95% same-entry di bawah nol.
**Exit-only kembali ditutup** untuk sinyal P0; tidak ada perubahan app/config. Penjaga parity
kini **30 test**.

Persentil entri: `exp_entry_price_percentile_ADA-DOGE-XRP.md` (2026-10-24) —
`tools/entry_percentile.py` mengukur 620 entri `classic` terhadap jendela 1/3/7 hari. Entry
khas di **persentil 93,3** harga 1 hari (75,6% di kuintil teratas) dan **81,7%** rentang
harian; 3 hari 84,6; 7 hari 82,7. Return close maju: 1 hari −0,108%, 3 hari −0,051%, 7 hari
+1,148%. Kuintil teratas 7 hari −2,596% vs terbawah +8,259% (n=293 vs 43; kuintil bawah kecil
dan bias periode). **Keluhan "beli di pucuk" benar secara posisi**, tetapi bukan sinyal jual
bersih — kuintil teratas 1 hari masih +0,264% dan persentil tinggi sebagian mekanis dalam tren
naik. Ini diagnostik, bukan izin menambah filter/gate entry.

**Spec v2 dihentikan dan artefaknya dihapus (2026-10-21).** Dokumen spec, dua laporan hasil,
tool, dataset, dan hasil JSON sudah dihapus dari pohon kerja. Jangan membuat ulang berkas atau
riset v2; ringkasan alasan ada di `workplan2.md` §64.

**Riset v3 juga dihentikan (2026-10-21).** Spec v3 + harness v3 sudah diukur, hasilnya negatif,
lalu dihentikan owner; seluruh artefaknya (termasuk `exp_spot_evidence_v3.md`) ada di branch
backup `backup/patch-v3-2026-10-21` (`796a9d1`). Jangan membuat berkas v3 baru di `docs/exp/`
branch ini. Arah aktif: engine kriptoyoi yang sudah ada + slider; slider entry sudah ada dan
menurut owner terbukti memaksimalkan entry. Riset exit-only dibuka terbatas lalu diukur negatif
dan ditutup lagi (2026-10-24, 0/90 sel); berikutnya pengukuran entry/universe.

## Aturan turunan dari penghentian riset Spec v2/v3 (2026-10-21)

- **V2 dan v3 berhenti, bukan tertunda.** Keduanya sudah diukur dan **tidak menghasilkan edge**;
  owner menghentikan seluruh line riset ini karena hanya memperpanjang research. Jangan memulai
  v2/v3 baru, jangan membangun engine/spec baru, dan jangan memakai dokumen lama sebagai izin
  untuk mencoba lagi.
- **Seluruh artefak Spec v2 sudah dihapus** — dokumen spec, dua laporan hasil, tool, dataset,
  dan hasil JSON/CSV. Commit v3 (`ada71dc`, `796a9d1`) sudah dibuang dari `patch`; seluruh
  artefak v3 hanya ada di branch backup `backup/patch-v3-2026-10-21` (`796a9d1`, remote).
  **Jangan membuat ulang berkas spec v2/v3.**
- **Arah aktif: engine scalping yang sudah ada** (port kriptoyoi `p0_engine` + slider). Status
  dari owner: implementasi slider entry sudah ada dan terbukti memaksimalkan entry; **exit
  strategi belum**. Jangan mengklaim exit sudah selesai.
- **Jangan mengubah kode aplikasi dulu.** Instruksi owner 2026-10-21: dokumen + hapus artefak
  v2; perubahan kode aplikasi lain menunggu instruksi berikutnya.
- **Jangan mengutip hasil harness Python sebagai "hasil app".** Artefak v2 — spec, dua laporan
  hasil, `tools/spot_*.py`, `data/tokocrypto_spot/`, snapshot
  `data/config/binance_24h_movers_reference.json`, dan hasil JSON/CSV di `data/hasil/` — sudah
  dihapus; sisa jejaknya hanya di riwayat git. Hasil harness Python adalah riset, bukan produksi.
- **Aturan engine existing tetap berlaku dan jangan dilonggarkan:** lantai friksi 6×,
  `PairEdgeGate`, akuntansi `UNRESOLVED` yang jujur, satu rumus P&L (`FeeSchedule`), dan
  larangan memakai hasil satu jendela sebagai edge. Jangan menurunkan gate hanya untuk menambah
  sinyal.

## Aturan turunan dari sweep exit & persentil entry (2026-10-24)

- **Riset exit hanya dengan instruksi owner, dan tetap offline/paper.** Exit-only untuk sinyal
  P0 sudah ditutup dua kali (2026-10-22 dan 2026-10-24). Jangan membukanya lagi, jangan
  menyambungkan mode baru ke app, dan jangan mengubah config default tanpa instruksi eksplisit
  dan mekanisme baru yang belum diuji.
- **Kriteria kandidat ditetapkan sebelum hasil dibaca.** Minimal: net/trade > 0, lebih baik dari
  `classic` di semua pair, CI 95% tidak menyentuh nol, dan sampel cukup (≥60 trade/pair). Tanpa
  itu, hasil sweep hanya diagnostik.
- **Pilih mode dengan net %, bukan R.** R dihitung dari jarak SL, jadi SL lebar membuat R terlihat
  lebih baik walau uangnya lebih buruk. Setiap klaim "WR naik" wajib disertai net/trade dan
  net/hari; WR tinggi dengan net negatif bukan keberhasilan.
- **Kontrol `classic` wajib identik CLI.** `tools/exit_mode_sweep.py` memakai cache gate; bila
  kontrol 90m tidak menghasilkan 620 trade / WR 14,84% / avg R −0,1569, hasil sweep **tidak
  sah** dan cache harus dibangun ulang (`--rebuild`). Jangan membandingkan angka dari cache
  yang belum tervalidasi.
- **Same-entry dan sequential adalah dua pertanyaan berbeda.** Sequential meniru replay (boleh
  masuk lagi setelah exit, N berubah); same-entry mengisolasi exit dengan N identik. Laporkan
  keduanya; jangan memilih salah satu yang terlihat lebih baik tanpa menyebut metodenya.
- **Persentil entry adalah diagnostik, bukan gate.** `tools/entry_percentile.py` mengukur posisi
  entry, bukan membuktikan filter baru. Jangan menambahkan filter persentil/kuintil ke engine
  tanpa eksperimen entry tersendiri yang mengukur net dan dampak jumlah sinyal.
- **Jangan mengutip kuintil bawah 7 hari tanpa konteks.** Sampelnya kecil (n=43) dan bias
  periode; angka +8,259% bukan aturan umum.
