# 📘 Konteks Arsitektur & Domain Aplikasi (analisa-pasar / TradingView AI)

> **Arah produk terkini (2026-10-21):** baca **`workplan2.md`** dulu.
> **Spec v2 dan v3 dihentikan** — tidak ada spec/engine baru. Fokus = **engine kriptoyoi yang
> sudah ada + slider** (slider entry sudah ada dan menurut owner terbukti memaksimalkan entry;
> exit belum). Workplan aktif = scalp mode + paper trade + slider sensitifitas (mirror
> `scalp_config.json`).
> Status temuan audit ada di **`docs/bugs/`** (sudah direview owner 2026-10-16 @ `547048d`).
> `workplan.md` / `checkpoint.md` / `checkpoint_progres.md` = **DEPRECATED dan sudah dihapus (2026-10-16)**.

---

## 0. Arah produk & alur scalp (SSOT singkat)

### 0.1 Identitas fokus
1. Screener real-time Tokocrypto (USDT/IDR) & Indodax (IDR).
2. **Scalp mode**: MTF, struktur, regime, setup, skor, risk/net R:R, friction **all-in**.
3. **Paper trade** untuk menguji scalp (bukan mengandalkan real money saat engine masih diuji).
4. Mode real trading API tetap ada untuk eksekusi manual/terproteksi PIN — **bukan** validasi edge scalp otomatis.

### 0.2 Alur kerja threshold
`ScalpingConfig` (default) → `ScalpingRuntimeConfig` (slider / import JSON) → engine live & paper → export JSON → `tools/scalp_replay.py`.

Slider mengubah seberapa ketat gate (frekuensi sinyal). Status dari owner (2026-10-21): slider
entry sudah ada dan terbukti memaksimalkan entry; **exit strategi belum**. Slider bukan jaminan
profit. Fee tetap Fee Truth.

### 0.3 Asal engine scalping
Port logika kuantitatif dari proyek open KriptoYoi (`dikigambol/kriptoyoi`, `analyzer/p0_engine.py` + specification). Visi upstream: **decision support**, bukan bot peramal harga.

### 0.4 Status empiris (ringkas)
Replay historis dengan fee all-in belum menunjukkan edge stabil pada kelas sinyal long-scalp teknikal yang diuji. Jangan tampilkan probabilitas palsu. Lihat `workplan2.md` §5.

---

## 📑 Daftar Isi
0. [Arah produk & alur scalp](#0-arah-produk--alur-scalp-ssot-singkat)
1. [Ringkasan Eksekutif & Identitas Aplikasi](#1-ringkasan-eksekutif--identitas-aplikasi)
2. [Fakta teknis kunci (SSOT cepat)](#fakta-teknis-kunci-ssot-cepat)
3. [Referensi cepat file](#referensi-cepat-file)

> **Catatan agen:** Dokumen ini **sengaja ringkas** dan berfokus pada arah produk. Untuk detail arsitektur, struktur direktori, Room, dan pipeline order, lihat **`README.md`**. Untuk **prioritas kerja & larangan**, ikuti `workplan2.md` + `AGENTS.md`, bukan checklist `checkpoint*.md`.
>
> Perubahan besar yang belum tercermin di bagian lain: **Paper Auto-Trade** kini jalur utama validasi scalp (exit FSM 5 mode, paritas replay), **`FeeSchedule`** menjadi satu-satunya sumber kebenaran biaya bursa, dan **`PairRules`** menegakkan batasan kuotasi per bursa (Indodax = IDR saja).

---

## 1. Ringkasan Eksekutif & Identitas Aplikasi

**analisa-pasar (TradingView AI)** adalah aplikasi Android berbasis **Kotlin** dan **Jetpack Compose (Material Design 3)** yang berfungsi sebagai:
1. **Pusat Analisis Pasar Real-Time**: Streaming data pasar (ticker, candlestick, orderbook depth, order trade flow) dari **Tokocrypto** dan **Indodax** (WebSocket + REST).
2. **Mesin sinyal multi-mode** termasuk **scalp mode** (MTF, setup, skor, risk) plus swing/intraday; AI (Gemini/Groq) sebagai pendukung, bukan pengganti fee/risk engine.
3. **Dual Execution Mode**:
   - **Paper / simulasi**: saldo virtual terisolasi per bursa & kuotasi — **jalur utama validasi scalp**.
   - **Real trading**: API resmi + PIN — eksekusi sadar user, bukan klaim auto-edge.
4. **Pengaman latar**: trailing, TP berjenjang, notifikasi (lihat `README.md` §8 *Manajemen Risiko & Eksekusi Otomatis*).

Detail komponen, isolasi exchange, Room, dan pipeline order: **lihat `README.md`** (bagian *Arsitektur & Teknologi* dan *Struktur Direktori Utama*) sebagai rujukan struktur kode terkini. Dokumen ini sengaja diringkas ke arah produk; tidak ada versi panjang lama yang disimpan di commit lain. Untuk pekerjaan baru, **mulai dari workplan2.md**.

### Fakta teknis kunci (SSOT cepat)
| Aspek | Nilai terkini |
|---|---|
| Room Database | **versi 8** — 4 entitas: `RealTradeEntity`, `RealOpenOrderEntity`, `SignalLogEntity`, `TradeHistoryRecordEntity` |
| DAO | `realTradeDao` (termasuk query `real_open_orders`), `signalLogDao`, `tradeHistoryRecordDao` — **tidak ada** DAO terpisah bernama `OpenOrderDao` |
| Android SDK | minSdk 24, targetSdk **35**, compileSdk 36 |
| Jaringan | OkHttp (REST + WebSocket) — **tanpa Ktor** |
| Bursa didukung | Tokocrypto (USDT + IDR), Indodax (IDR saja) — Binance **dilarang** di runtime |
| Model biaya | `object FeeSchedule` di `config/AppConfiguration.kt` = satu-satunya sumber kebenaran fee |
| Sesi intraday | **5 sesi** (Open Pagi, Hold Siang, Sore Trailing, Close Malam, Rest Malam) |

### Referensi cepat file
| File | Isi |
|------|-----|
| workplan2.md | Fase P0–P3, slider map, alur RuntimeConfig |
| AGENTS.md | Aturan agen, batasan exchange, larangan |
| SCALP_EXIT.md | Exit paper / R net |
| data/config/scalp_config.json | Mirror threshold + fee replay |
| tools/scalp_replay.py | Offline paritas engine |
