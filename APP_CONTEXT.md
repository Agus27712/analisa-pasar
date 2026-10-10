# 📘 Konteks Arsitektur & Domain Aplikasi (analisa-pasar / TradingView AI)

> **Arah produk terkini (2026-10-10):** baca **`workplan2.md`** dulu.  
> Workplan aktif = scalp mode + paper trade + slider sensitifitas (mirror `scalp_config.json`).  
> `workplan.md` / `checkpoint.md` / `checkpoint_progres.md` = **DEPRECATED**.

---

## 0. Arah produk & alur scalp (SSOT singkat)

### 0.1 Identitas fokus
1. Screener real-time Tokocrypto (USDT/IDR) & Indodax (IDR).
2. **Scalp mode**: MTF, struktur, regime, setup, skor, risk/net R:R, friction **all-in**.
3. **Paper trade** untuk menguji scalp (bukan mengandalkan real money saat engine masih diuji).
4. Mode real trading API tetap ada untuk eksekusi manual/terproteksi PIN — **bukan** validasi edge scalp otomatis.

### 0.2 Alur kerja threshold
`ScalpingConfig` (default) → `ScalpingRuntimeConfig` (slider / import JSON) → engine live & paper → export JSON → `tools/scalp_replay.py`.

Slider mengubah seberapa ketat gate (frekuensi sinyal). **Tidak** menggantikan riset entry. Fee tetap Fee Truth.

### 0.3 Asal engine scalping
Port logika kuantitatif dari proyek open KriptoYoi (`dikigambol/kriptoyoi`, `analyzer/p0_engine.py` + specification). Visi upstream: **decision support**, bukan bot peramal harga.

### 0.4 Status empiris (ringkas)
Replay historis dengan fee all-in belum menunjukkan edge stabil pada kelas sinyal long-scalp teknikal yang diuji. Jangan tampilkan probabilitas palsu. Lihat `workplan2.md` §5.

---

## 📑 Daftar Isi
0. [Arah produk & alur scalp](#0-arah-produk--alur-scalp-ssot-singkat)
1. [Ringkasan Eksekutif & Identitas Aplikasi](#1-ringkasan-eksekutif--identitas-aplikasi)
2. [Arsitektur Sistem Tingkat Tinggi](#2-arsitektur-sistem-tingkat-tinggi)
3. [Model Data Inti & Single Source of Truth (SSOT)](#3-model-data-inti--single-source-of-truth-ssot)
4. [Integrasi Bursa: Tokocrypto vs Indodax](#4-integrasi-bursa-tokocrypto-vs-indodax)
5. [Mesin Analisis Teknikal & AI Trading Engine](#5-mesin-analisis-teknikal--ai-trading-engine)
6. [Simulasi Trading & Real Execution Flow](#6-simulasi-trading--real-execution-flow)
7. [Jalur Notifikasi, Background Service, & Safety Guardrails](#7-jalur-notifikasi-background-service--safety-guardrails)
8. [Pencatatan Siklus Trade (Trade Journey Lifecycle 4-Stage)](#8-pencatatan-siklus-trade-trade-journey-lifecycle-4-stage)
9. [Keamanan, Kredensial, & PIN Protection](#9-keamanan-kredensial--pin-protection)
10. [Struktur Direktori & Kamus Komponen Utama](#10-struktur-direktori--kamus-komponen-utama)

> **Catatan agen:** Body arsitektur detail (§1–10) di bawah ini masih valid untuk struktur kode. Untuk **prioritas kerja & larangan**, ikuti `workplan2.md` + `AGENTS.md`, bukan checklist `checkpoint*.md`.

---

## 1. Ringkasan Eksekutif & Identitas Aplikasi

**analisa-pasar (TradingView AI)** adalah aplikasi Android berbasis **Kotlin** dan **Jetpack Compose (Material Design 3)** yang berfungsi sebagai:
1. **Pusat Analisis Pasar Real-Time**: Streaming data pasar (ticker, candlestick, orderbook depth, order trade flow) dari **Tokocrypto** dan **Indodax** (WebSocket + REST).
2. **Mesin sinyal multi-mode** termasuk **scalp mode** (MTF, setup, skor, risk) plus swing/intraday; AI (Gemini/Groq) sebagai pendukung, bukan pengganti fee/risk engine.
3. **Dual Execution Mode**:
   - **Paper / simulasi**: saldo virtual terisolasi per bursa & kuotasi — **jalur utama validasi scalp**.
   - **Real trading**: API resmi + PIN — eksekusi sadar user, bukan klaim auto-edge.
4. **Pengaman latar**: trailing, TP berjenjang, notifikasi (lihat §7).

Detail komponen, isolasi exchange, Room, dan pipeline order: lanjutkan §2–10 di riwayat dokumen arsitektur repo (isi penuh dipertahankan di commit sebelumnya bila diperlukan). Untuk pekerjaan baru, **mulai dari workplan2.md**.

### Referensi cepat file
| File | Isi |
|------|-----|
| workplan2.md | Fase P0–P3, slider map, alur RuntimeConfig |
| AGENTS.md | Aturan agen, batasan exchange, larangan |
| SCALP_EXIT.md | Exit paper / R net |
| data/config/scalp_config.json | Mirror threshold + fee replay |
| tools/scalp_replay.py | Offline paritas engine |
