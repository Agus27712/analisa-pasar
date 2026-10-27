# AGENTS.md — analisa-pasar

Aplikasi Android Kotlin 100% (Jetpack Compose M3) untuk analisis & **scalp mode** kripto di **Tokocrypto** dan **Indodax**. Modul tunggal `:app`, package `agu.analys`, MVVM + Coordinator (`TradingViewModel` + MarketData/Position/Simulation/RealTradeCoordinator).

**Versi aplikasi saat ini:** **3.9.2** (`versionCode 91`) — `app/build.gradle.kts`. Rilis 3.9.2
(2026-10-18) berisi batch 6j–6n: seluruh temuan audit koneksi Tokocrypto/Indodax **K1–K15**
(daftar pair resmi, WebSocket Indodax hidup, reconnect berjitter, status live per bursa,
lingkup ticker watchlist, gate watchlist & dashboard). Tag rilis: `v3.9.2`
dibuat **manual** lewat Actions → `Release APK` (`workflow_dispatch`), tidak otomatis saat push.

---

## ▶ MULAI DARI SINI (agen lanjutan — baca sebelum menyentuh kode)

### ⚠️ Langkah 0 — pastikan branch yang Anda baca benar

**Branch kerja: `main`.** Semua pekerjaan aktif dikerjakan di `main`. Branch `patch` sekarang hanya histori dan suatu saat akan dihapus owner. Jangan kerja atau commit di `patch`.

Ukur keadaan repo dulu, jangan dari ingatan:

```sh
git clone https://github.com/Agus27712/analisa-pasar.git
cd analisa-pasar && git checkout main && git pull --ff-only origin main
git status --short                 # harus kosong sebelum mulai
git log --oneline -3
git ls-remote --heads origin       # main, patch, backup/patch-v3-2026-10-21
```

Repo ini **public**, clone tidak butuh token. Selalu `git pull --ff-only` sebelum mulai, karena `main` lokal bisa tertinggal dari `origin/main`.

**⛔ SPESIFIKASI BARU V2/V3 DIHENTIKAN (2026-10-21).** Spec v2 dan v3 sudah diukur dan
keduanya **tidak menghasilkan edge**; owner menghentikan seluruh line riset ini karena hanya
memperpanjang research. **Seluruh artefak v2 sudah dihapus** — dokumen spec, dua laporan hasil,
tool, dataset, dan hasil JSON/CSV; commit v3 (`ada71dc`, `796a9d1`) sudah dibuang dari `patch`
dan artefaknya hanya ada di branch backup **`backup/patch-v3-2026-10-21`** (`796a9d1`, remote).
**Jangan membangun engine/spec baru.**
Arah aktif: **engine scalping yang sudah ada** (port kriptoyoi `p0_engine` + slider). Status
dari owner: implementasi slider entry sudah ada dan terbukti memaksimalkan entry. **P0 (TTL,
MTF 4 TF, veto BTC, regime OR) sudah disinkronkan 2026-10-22** dan diukur: gate bekerja,
tetapi **edge masih negatif** (avg R −0,1569, 616/620 trade TIMEOUT). Jangan mengklaim P0
menyelesaikan masalah profit. Setelah P0, **jangan menambah gate entry baru tanpa instruksi**.

**Riset exit dibuka kembali secara terbatas oleh owner pada 2026-10-24** (paper/offline),
lalu diukur: **0 dari 90 sel pair × mode × hold positif** pada sweep 5 mode
(`exp_exit_mode_sweep_ADA-DOGE-XRP.md`). Exit-only untuk sinyal P0 **kembali ditutup**;
jangan membukanya lagi tanpa instruksi owner dan mekanisme baru yang eksplisit. Riset
sebelumnya (0/88 varian `exp_exit_net_tp`, 0/27 kombinasi `exp_friction_multiple`) tetap
berlaku. Diagnostik persentil entry (`exp_entry_price_percentile_ADA-DOGE-XRP.md`) menunjukkan
entry khas di persentil 93 harga 1 hari terakhir — keluhan "beli di pucuk" benar secara posisi,
tetapi itu **bukan** izin menambah filter/gate tanpa pengukuran entry tersendiri. Fokus
berikutnya tetap **pengukuran entry/universe**; keputusan gate menunggu owner.

### Alur kerja dokumen & release (wajib, berlaku mulai 2026-10-25)

- **Acuan arah aplikasi: `workplan2.md`.** Dokumen pendukung: `APP_CONTEXT.md`, `FEE_TRUTH.md`, `SCALP_EXIT.md`. Catatan perbaikan eksperimen ada di folder masing-masing (`docs/exp/`, `docs/bugs/`).
- **Bug terbuka** ada di `docs/bugs/`. Saat selesai, pindahkan berkasnya ke `docs/` sebagai laporan (`git mv` ke `docs/laporan_temuan_bugs_<topik>.md`), mengikuti format bugs document: tabel status dan § Riwayat.
- **Eksperimen** ada di `docs/exp/` (`exp_<testname>_<pair>.md`). Tulis status di header berkas: `RUNNING`, `BERHASIL`, atau `GAGAL`.
  - `BERHASIL`: port ke engine, lalu buat laporan di `docs/` mengikuti format experiment document. Laporan menunjuk ke berkas `docs/exp/` asalnya.
  - `GAGAL`: tetap di `docs/exp/` dengan alasan dan angka negatifnya. **Jangan di-port.**
- **Arti `BERHASIL`:** lolos kontrol negatif, terbukti di data di luar sampel (walk-forward), dan memakai fee all-in. Satu window data tidak cukup.
- **Release manual.** Rilis dijalankan lewat Actions → `Release APK`. Push ke `main` tidak memicu rilis. Naikkan versi di `app/build.gradle.kts` hanya saat owner minta rilis.
- **Push langsung ke `main` diizinkan** atas instruksi owner (2026-10-25).

**Urutan baca 5 menit yang benar:**

1. **Berkas ini** sampai habis — terutama "Yang tidak boleh dilakukan agen" dan tabel aturan per
   area di bawah. Sejak 2026-10-27 aturan per area **tidak lagi ditulis di sini**; berkasnya
   dimuat otomatis saat area itu dibaca.
2. **`docs/PANDUAN-LANJUT-AGEN.md`** — status terkini, apa yang sudah selesai, apa yang berikutnya,
   dan perangkap yang sudah pernah ditemukan. **Ini titik masuk utama.**
3. **`docs/BELUM-DIKERJAKAN-2026-10-14.md`** — inventaris 27 item (per 2026-10-20: **22 selesai**,
   **P4.2 dibatalkan** karena taker-only; yang terbuka = **P5**, **P6** diblokir, dan **V1–V4**
   verifikasi lapangan). Baca sebelum memilih apa yang akan dikerjakan.
4. **`workplan2.md` § 13** — pekerjaan terakhir yang masih berlaku; §9, §11, §12 menjelaskan
   **kenapa** kode hari ini berbentuk seperti sekarang. **§ 14 dan § 15 DIBATALKAN** (fitur
   kesiapan multi-horizon, dihapus dari kode 2026-10-13) — dibaca sebagai riwayat keputusan,
   **jangan dibangun ulang**.
5. **`docs/bugs/`** — 🔴 sumber kebenaran status temuan audit (**kosong** sejak 2026-10-18; berkas
   yang seluruh temuannya tutup sudah pindah ke `docs/laporan_temuan_bugs_*.md`). Aturan folder,
   pemindahan berkas, dan daftar topik ada di **`docs/AGENTS.md`**.
6. **`docs/exp/`** — 🟠 hasil eksperimen terukur. **Baca ini sebelum mengubah parameter engine atau
   mengklaim "strategi ini pasti bisa profit"**; hampir semua pertanyaan "apakah X berhasil" sudah
   dijawab dengan angka. Aturan folder + ringkasan angka terukur ada di **`docs/AGENTS.md`**.
7. **`docs/LAPORAN-temuan-akuntansi-replay-2026-10-14.md`** — 🔴 **wajib sebelum memakai angka edge
   apa pun.** Angka "+0,0216 avg R / LOLOS" dari `u4` **tidak sah** karena trade `UNRESOLVED` dulu
   dibuang (±81% sinyal); setelah dihitung jujur, avg R ≈ **−0,17** dengan **0/40 pair positif**.
8. **`SCALP_EXIT.md`** bila menyentuh exit; **`docs/LAPORAN-eksekusi-s1s9-2026-10-14.md`** bila
   menyentuh entry/exit scalping — dua keputusan di sana menyimpang dari daftar aslinya: **S9
   dikirim nonaktif** (ambang 25% menghapus seluruh trade GNS yang 4/4 menang) dan **S2 aktif**
   dengan default 2× friksi.
9. **`docs/LAPORAN-*.md`** — jejak eksekusi. Baca dari tanggal terbaru kalau butuh tahu **kenapa**
   suatu perbaikan berbentuk seperti sekarang.
10. Baru buka kode.

> **Dokumen yang sudah dihapus (2026-10-16):** `workplan.md`, `checkpoint.md`,
> `checkpoint_progres.md` — isinya cuma stub "DEPRECATED" yang menunjuk ke `workplan2.md` /
> `AGENTS.md`. Kalau kamu menemukan rujukan ke sana, itu rujukan basi: **jangan dibuat ulang** dan
> jangan dipakai sebagai urutan kerja.

**Aturan kerja yang berlaku di repo ini:**

- **🔤 Bahasa: Balas chat & pertanyaan ke owner dalam Bahasa Indonesia. Bahasa Mandarin hanya
  untuk reasoning/internal, tidak pernah untuk jawaban yang terlihat.** Owner pernah menerima
  jawaban Mandarin dan tidak bisa membacanya. Semua dokumen, komentar kode, dan pesan commit
  juga Bahasa Indonesia (kecuali istilah teknis).
- **Branch kerja: `main`.** `patch` hanya histori dan akan dihapus owner. Push ke `main` diizinkan atas instruksi owner; rilis manual.
- **Setiap perubahan wajib memperbarui markdown terkait** (`workplan2.md` + `AGENTS.md` +
  `docs/LAPORAN-*.md`). Pekerjaan tanpa jejak dokumen dianggap belum selesai.
- **Setiap klaim angka harus diukur, bukan diingat.** Hitung test dari
  `app/build/test-results/testDebugUnitTest/*.xml`, bukan dari pesan lama. Untuk mengukur baseline,
  pakai `git stash` → jalankan test → `git stash pop` (pola ini sudah dipakai beberapa kali).
- **Kontrol negatif wajib** untuk perbaikan bug: matikan perbaikannya, buktikan test GAGAL, lalu
  kembalikan. Test yang selalu hijau tidak membuktikan apa pun.
- **Bila ragu, eksekusi keputusan terbaik dan catat alasannya** di dokumen — jangan berhenti
  menunggu jawaban.

## Aturan per area (berkas terpisah, dimuat saat area dibaca)

Sejak **2026-10-27** aturan yang hanya berlaku untuk satu area tidak lagi ditulis di berkas ini.
Tujuannya satu: konteks tiap permintaan tidak membengkak oleh aturan yang tidak relevan. OpenCode
V2 memuat `AGENTS.md` di sebuah folder **hanya ketika berkas di area itu dibaca**, jadi aturan area
tidak membebani pekerjaan yang tidak menyentuhnya.

| Area | Berkas | Isi |
|---|---|---|
| Kode aplikasi (`app/`) | **`app/AGENTS.md`** | engine scalping, P0 (TTL/MTF/BTC/regime), lantai friksi, `PairEdgeGate`, `ScalpReadiness`, paper trading (P1–P9), notifikasi (N1–N17), jurnal & P&L (J1–J10), koneksi bursa (K1–K15), FEE_TRUTH, UI & Compose, larangan membangun ulang fitur multi-horizon |
| Tooling riset (`tools/`, `data/config/`) | **`tools/AGENTS.md`** | mirror replay `scalp_config.json`, uji parity, larangan config kedua, replay wajib memakai exit yang sama dengan app |
| Dokumen (`docs/`) | **`docs/AGENTS.md`** | aturan folder `bugs/` dan `exp/`, kriteria kandidat eksperimen, ringkasan angka terukur, penghentian Spec v2/v3 |

**Wajib:** sebelum mengubah, menambah, atau menghapus kode/alat/dokumen di sebuah area, baca
`AGENTS.md` area itu. Bila aturan area bertentangan dengan berkas ini, **aturan yang lebih spesifik
menang**, dan pertentangannya dicatat di `workplan2.md`.

> Catatan teknis (OpenCode V2): perubahan pada berkas **bertingkat** seperti `app/AGENTS.md` baru
> dipakai pada **sesi baru** bila berkas itu sudah terlanjur dimuat di sesi berjalan. Kalau baru
> mengubahnya di tengah sesi, mulai sesi baru sebelum mengandalkan isinya.

## Hemat konteks sesi (2026-10-27)

Tiga hal yang menjaga jendela konteks tetap lega. Jangan membatalkannya tanpa alasan ukur:

1. **Aturan per area di berkas terpisah** (lihat tabel di atas) — jangan menaruh aturan satu area
   di akar; akar dibayar di setiap permintaan.
2. **`opencode.jsonc` proyek** menolak tool `opencode_console_*` (kelola member, provider, model,
   billing). Server tetap tersambung, tetapi katalognya tidak ikut masuk system prompt. `ctx_*` dan
   `mem0` sengaja tetap aktif. Kalau butuh tool konsol, jalankan dari proyek lain atau ubah
   `permissions` di berkas itu.
3. **Compaction lebih awal** (`~/.config/opencode/opencode.jsonc`, per mesin): `keep.tokens` 8.000
   dan `buffer` 32.000. Lebih agresif = lebih lossy; naikkan `keep.tokens` bila detail terbaru
   penting.

Angka dan alasan lengkap: `workplan2.md` §78.

## Arah produk (ringkas)

- **Fokus: engine scalping yang sudah ada** (port kriptoyoi `p0_engine` / spec) + **paper trade**
  untuk menguji engine. **Bukan** membangun spec/engine baru; Spec v2/v3 sudah dihentikan.
- **Slider adalah jalur peningkatan**: slider entry sudah ada dan menurut owner terbukti
  memaksimalkan entry; riset exit-only diukur negatif dan ditutup (§68–§69 `workplan2.md`).
  Owner membukanya lagi secara terbatas pada 2026-10-24; hasilnya **0/90 sel**, jadi ditutup
  lagi (§70). Jangan mengklaim exit sudah terpecahkan.
- **P0 sudah disinkronkan (2026-10-22)**: TTL 5 candle, gate MTF 4 TF, veto BTC, regime OR.
  Hasil terukur: 620 trade, WR 14,84%, **avg R −0,1569**, **616 TIMEOUT**, 0 TP. Gate bekerja,
  tetapi **edge belum ada**; jangan memakai P0 sebagai alasan promosi uang riil.
- Real money untuk strategi scalp: **belum**, sampai paper + fee truth stabil **dan** exit
  punya expectancy positif.
- Slider sensitifitas mengubah threshold (frekuensi sinyal), **bukan** jaminan profit; status
  slider entry dari owner dicatat di poin sebelumnya.
- Fee: **all-in** Tokocrypto/Indodax (Fee Truth) — jangan pakai model upstream p0 ~0,72% RT sebagai default tanpa label.
- Engine scalping di-port dari logika [dikigambol/kriptoyoi](https://github.com/dikigambol/kriptoyoi) `p0_engine` / spec — decision support, bukan bot profit terjamin.

## Perintah build & tes

- `chmod +x gradlew` bila perlu.
- Unit test: `./gradlew :app:testDebugUnitTest` (JDK 17).
- Scope scalping: `./gradlew :app:testDebugUnitTest --tests "agu.analys.engine.scalping.*"`.
- Build: `./gradlew :app:assembleDebug` / `assembleRelease`.
- Uji parity replay engine existing: `python3 tools/test_scalp_replay_parity.py`.
- Alat riset Spec v2 (`tools/spot_*.py`), dataset `data/tokocrypto_spot/`, dan hasilnya **sudah
  dihapus 2026-10-21**; jangan dibuat ulang dan jangan dipakai untuk riset baru.
- String `compile_applet` di dokumen lama **bukan** perintah repo — pakai `./gradlew` di atas.

## Secrets & environment

- Jangan commit `.env`, `*.keystore`, `*.jks`.
- `app/build.gradle.kts`: `.env` → `.env.example` → env → Gradle props. Tanpa `.env`, key AI boleh kosong.
- API key bursa: terenkripsi di Pengaturan app, bukan `.env`.
- Jika `EncryptedSharedPreferences` gagal: hapus MasterKey corrupt di AndroidKeyStore sebelum retry; fallback plaintext hanya last resort + log error.

## Batasan pasar (jangan dilanggar)

- **Tokocrypto: hanya USDT + IDR. Indodax: hanya IDR.** Jangan USDT di Indodax.
- Quote BIDR dieliminasi (kecuali koin bernama `…BIDR` seperti `BNBIDR`/`SHIBIDR`).
- **Dilarang** request ke `binance.com` / `stream.binance.com` (REST/WS) — diblok di Indonesia.
- Data historis riset boleh dari arsip publik (mis. data.binance.vision) **hanya** di tools offline, bukan di app runtime.

## Isolasi exchange & kuotasi

- Cache, SharedPreferences, Room: partisi per exchange (`${exchange}_…`, kolom `exchange`). Query DAO filter `exchange`.
- `OrderBookDepthCache.updateOrderBook()` default exchange menipu — **selalu** teruskan exchange eksplisit.
- Format harga/PnL mengikuti `quoteAsset` (Rp vs $).

## Penulisan dokumen Markdown

Dokumen dibaca owner di editor/HP, bukan hanya di terminal. Tiga hal yang pernah merusak
keterbacaan dan **tidak boleh terulang**:

- **UTF-8 tanpa BOM, akhir baris LF.** Jangan menyimpan `.md` sebagai UTF-16/ANSI atau CRLF.
- **Tidak boleh ada aksara CJK** (Mandarin, Jepang, Korea) di berkas mana pun. Aksara seperti itu
  biasanya masuk lewat tempel-mentempel dari hasil terjemahan atau editor lain, dan membuat
  kalimat terbaca sebagai "tulisan aneh". Ganti dengan kata Indonesia yang sebenarnya.
- **Tulis dengan istilah yang jelas, bukan jargon pinjaman.** Contoh pengganti: *seam* → titik
  sambung/tempat perakitan; *chokepoint* → satu-satunya pintu masuk; *uji menggigit* → uji gagal
  saat kode dirusak. Kalau satu kalimat perlu dibaca dua kali, tulis ulang.
- **Satu laporan per berkas temuan.** Ringkasan batch ditulis ke § Riwayat berkas laporan temuan
  (`docs/laporan_temuan_bugs_<topik>.md`) dan ke `workplan2.md` — bukan berkas baru.
- **Teks yang diekspor aplikasi ikut aturan ini.** Jurnal trading diekspor sebagai `.md`
  (`trading/TradeLogExporter.kt`). Jangan memakai simbol matematika di labelnya — simbol irisan
  sudah dibuang dari label "Skor Konfidensi" karena tampil seperti aksara CJK di sebagian font.

Periksa sebelum commit:

```sh
python3 tools/check_md_text.py
```

Skrip itu memeriksa BOM, CRLF, UTF-8 rusak, aksara CJK, dan sisa mojibake dari penyuntingan
sebelumnya.

## Git

- **Branch kerja: `main`** (`patch` hanya histori, akan dihapus owner). Push langsung ke `main` diizinkan atas instruksi owner.
- Tidak ada lagi merge `patch` → `main`. Selalu `git pull --ff-only origin main` sebelum commit, karena `origin/main` bisa lebih baru dari `main` lokal.
- **Naikkan versi di `main`** saat owner minta rilis. `versionName`/`versionCode` ada di `app/build.gradle.kts`; `release.yml` membaca versi itu dan membuat tag `v<versionName>` saat dijalankan manual.
- Branch lama di remote (`audit/menyeluruh-2026-10`, `engine_scalping_update`, tag versi)
  **sudah tidak ada** — per 2026-10-15 `git ls-remote --heads origin` hanya mengembalikan
  `main` dan `patch`. Jangan mencari branch itu lagi.
- Jangan commit artefak data candle besar tanpa diminta.
- Jangan commit `.env`, `*.keystore`, `*.jks`, atau token.
- Pesan commit jelas, dalam Bahasa Indonesia.
- Clone di sandbox ini kadang dibatasi `remote.origin.fetch=+refs/heads/main:...`. Bila
  `git branch -r` terlihat hanya berisi `main`, **perbaiki refspec dulu** sebelum menyimpulkan
  bahwa branch lain tidak ada.

## Yang tidak boleh dilakukan agen

- Menghidupkan Binance di path app production.
- Longgarkan semua gate “supaya banyak sinyal” tanpa paper/replay log.
- Menulis di UI bahwa sinyal punya winrate/expectancy historis tanpa data cukup.
- Mengikuti checklist `checkpoint*.md` / `workplan.md` lama sebagai urutan kerja (ketiganya sudah dihapus 2026-10-16).
- Port auto-trading real dari state machine exit tanpa instruksi eksplisit owner + fee truth.
