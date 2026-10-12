# workplan2.md — Workplan aktif (Oktober 2026)

> **Sumber kebenaran workplan.**  
> `workplan.md`, `checkpoint.md`, dan `checkpoint_progres.md` **DEPRECATED** — jangan diikuti agen. Baca ini + `AGENTS.md` + `APP_CONTEXT.md` (§ arah produk).

**Update:** 2026-10-10 (WIB)

---

## 1. Arah produk (kunci)

**analisa-pasar** = app Android screener + **scalp mode** untuk Tokocrypto (USDT/IDR) & Indodax (IDR only).

| Pilar | Keputusan |
|-------|-----------|
| Mode fokus | **Scalp mode** (setup MTF, skor, risk, exit paper) |
| Uang riil | **Tidak** untuk validasi strategi sampai paper + fee truth stabil |
| Validasi | **Paper trade** di app + **`tools/scalp_replay.py`** dengan config yang sama |
| Sensitifitas | Slider UI ↔ `ScalpingRuntimeConfig` ↔ JSON mirror `data/config/scalp_config.json` |
| Edge | Belum terbukti di data historis (v2/v3/Binance45d). Slider mengubah **frekuensi** sinyal, **bukan** menciptakan expectancy |
| Engine asal | Port logika dari [dikigambol/kriptoyoi](https://github.com/dikigambol/kriptoyoi) `analyzer/p0_engine.py` + spec; **bukan** copy seluruh web app |

**Satu kalimat:** bantu melihat setup & risiko (termasuk biaya all-in) dan menguji scalp di paper; jangan klaim profit otomatis.

---

## 2. Alur kerja (wajib diikuti agen)

```text
┌─────────────────────┐
│ ScalpingConfig.kt   │  konstanta default (seed)
│ (defaults only)     │
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ ScalpingRuntimeConfig│  runtime: slider / import JSON / preset
│ (DataStore + memory) │
└──────────┬──────────┘
           ├──────────────────────┐
           ▼                      ▼
┌─────────────────────┐  ┌────────────────────────┐
│ Engine live/paper   │  │ Export JSON            │
│ detector/score/risk │  │ = format scalp_config  │
└──────────┬──────────┘  └───────────┬────────────┘
           ▼                         ▼
┌─────────────────────┐  ┌────────────────────────┐
│ Paper trade journal │  │ tools/scalp_replay.py  │
│ log + thresholds    │  │ --config <exported>    │
└─────────────────────┘  └────────────────────────┘
```

1. Ubah threshold hanya lewat **RuntimeConfig** / JSON — jangan hardcode tersebar di detector.
2. Setelah ubah slider: uji **paper**; untuk historis, **export JSON** lalu replay.
3. Fee **bukan** slider “biar hijau”: ikut Fee Truth (all-in Tokocrypto/Indodax).
4. Commit docs/engine non-rilis: sertakan **`[skip ci]`** di pesan commit bila diminta owner.
5. Hipotesis entry baru = folder/tool terpisah, **jangan** merge ke config live sebelum gate edge kotor > fee (train+test).

---

## 3. Fase kerja aktif

### P0 — Fee Truth (prioritas) — [SELESAI ✅]
- [x] Dokumen singkat fee resmi (`FEE_TRUTH.md`: Tokocrypto USDT all-in 0.4044%, IDR taker 0.2222/0.4322; Indodax terpisah)
- [x] `TradingFeeConfig` + `FeeCalculator` + default exchange = Fee Truth (all-in Tokocrypto USDT/IDR)
- [x] `data/config/scalp_config*.json` fee block selaras (USDT 0.4044% per sisi)
- [x] UI Friction / VIABLE tidak pakai model upstream ~0,72% tanpa label
- [x] Unit test / assert RT fee snapshot (`FeeCalculatorTest.kt` passing)

### P1 — Runtime sensitivity + paritas alat — [SELESAI ✅]
- [x] `ScalpingRuntimeConfig` (seed dari `ScalpingConfig`, mutable, preset DEFAULT/STRICT/LOOSE/CUSTOM)
- [x] Wiring: `ScalpSetupDetector`, `ScalpingMtfEvaluator`, `ScalpingRiskEngine` baca runtime
- [x] UI Settings: preset Ketat/Default/Longgar + 11 slider (di `ScalpingSensitivitySettings.kt`)
- [x] Export / import JSON mirror `scalp_config.json` (tombol Export/Import di UI pengaturan scalping)
- [x] Paper trade log menyimpan snapshot thresholds (`TradeSignalSnapshot.scalpingThresholds`)
- [x] `scalp_replay` TIMEOUT fix di main (semua trade resolve + R net)
- [x] Audit BTC gate / TTL vs upstream p0 (terdokumentasi: tidak ada gate diskriminatif, TTL 10m standar di `SignalLifecycleManager`)

### P2 — Paper scalp auto & exit (decision support) — [SELESAI ✅]
> **Catatan Status:** Sinyal scalping + slider threshold aktif penuh. Auto-buy paper, engine exit 5 mode,
> paritas replay, visual FSM, dan kartu statistik sudah masuk. Seluruhnya **paper trade** — tidak ada
> jalur ke eksekusi uang riil dari sinyal scalp.
- [x] **Paper Auto-Buy (Simulasi)**: Toggle di Pengaturan → Engine Scalping. Membuka posisi simulasi saat sinyal
      `BUY` dengan `scalpingStage` ≥ ambang terpilih (`ENTRY` / `STRONG_ENTRY`). Alokasi **nominal tetap** atau
      **berbasis risiko** (`PaperAutoTradeConfig`). Tidak pernah memicu order uang riil.
- [x] **Paper Auto-Exit Modes**: `PaperExitResolver` + `PaperExitStateMachine` meniru `resolve_trade()` di
      `tools/scalp_replay.py` untuk 5 mode (`classic`, `tp1_be`, `early`, `trail`, `full`), termasuk R seragam
      net setelah fee all-in + slippage. Parameter exit masuk `ScalpingRuntimeConfig.exit` dan ikut ter-ekspor
      sebagai blok `exit` di JSON (dibaca replay).
- [x] Paritas diuji: `PaperExitResolverParityTest` — angka harapan dihasilkan langsung dari Python
      `tools/scalp_replay.py`, bukan dihitung manual.
- [x] `PaperTradeStats` menutup 2 keterbatasan yang dicatat di `SCALP_EXIT.md`: TIMEOUT **tidak** dibuang
      diam-diam, dan exit breakeven (r = 0) berlabel BREAKEVEN (bukan LOSS).
- [x] **FSM visual di UI paper trade** — `PaperExitFsmCard`: stepper fase (`HOLDING` → `TP1 50%` → `BE` → `TRAIL`),
      tangga level (entry / SL awal / SL aktif / BE net / peak / TP1 / TP2), bar posisi SL↔TP2, dan metrik
      berjalan (net %, R, jumlah bar M1, jarak ke SL). Angka diambil dari `PaperExitSnapshot` — mesin yang sama
      dengan yang memutuskan exit, jadi tidak ada dua sumber kebenaran.
- [x] **Kartu statistik R net terakumulasi** — `PaperTradeStatsCard`: total R, rata-rata R, win rate, profit
      factor, hitungan menang/kalah/breakeven/timeout, rincian per alasan exit, dan 4 baris jurnal terakhir.
      Hasil tiap trade dipersistensikan lewat `PaperTradeJournalStore` (maks 500 baris), jadi akurat lintas restart.
- [x] **Kepemilikan exit tunggal** — saat engine P2 aktif untuk sebuah pair, jalur auto-sell lama
      (`checkTrailingForMode` → TP1/TP2/SL dari `positionStore`) dimatikan untuk pair itu, sehingga tidak ada
      dua mesin yang menjual posisi yang sama.
- [x] Evaluasi per-tick aman: pemeriksaan level (SL/TP/BE/trailing) jalan tiap tick, sedangkan hitungan bar M1
      dan time-stop hanya maju sekali per menit. Early-profit sengaja dilewati bila jendela momentum M1 belum
      tersedia — lebih baik menahan posisi daripada keluar dari data yang tidak terukur.
- [x] Larangan keras: **tidak ada** jalur real auto-execute dari sinyal scalp di kode yang ditambahkan.

### P3 — Riset entry (di luar path live) — [SELESAI ✅ — gate TIDAK lolos]
> **Hasil:** 8 hipotesis entry diuji pada 60 hari data Tokocrypto (12 Agu – 11 Okt 2026, 8 pair,
> 87.000 bar M1/pair, tanpa celah). **0 dari 8 lolos gate.** Gross mean terbaik di TEST hanya
> +0,03% terhadap ambang 1%; semua NET berada di −0,94%..−1,05%, yaitu praktis sama dengan
> friksi round-trip 0,97%. **Tidak ada yang dipromosikan ke config live** — dan memang tidak boleh.
- [x] Tool hipotesis terpisah + label HIPOTESIS: `tools/entry_hypothesis_lab.py`
- [x] Gate dijalankan: mean GROSS ≥ 1% di **TRAIN dan TEST**, n ≥ 30 per split,
      mengalahkan baseline acak (500 permutasi, ambang Bonferroni 0,05/8)
- [x] Harness divalidasi peka: `tools/selftest_entry_lab.py` — pada data sintetis ber-edge
      tanam +2% gate **lolos** (p = 0,0033), pada hipotesis kontrol gate **gagal**.
      Jadi hasil 0/8 adalah temuan, bukan alat yang buta.
- [x] Hasil lengkap tersimpan: `data/hasil/p3_entry_hypothesis_lab.json` + `.md`
- [ ] Promote ke RuntimeConfig default — **tidak dilakukan**, tidak ada kandidat yang lolos gate

---

## 4. Slider sensitifitas (map JSON)

Default = nilai di `ScalpingConfig.kt` / `data/config/scalp_config.json`.

| UI label | Key JSON | Default | Range usulan |
|----------|----------|---------|--------------|
| Skor min LONG | `min_score_long` | 60 | 45–80 |
| Skor STRONG | `min_score_strong` | 75 | 60–90 |
| Skor retest | `min_score_breakout_retest` | 75 | 60–90 |
| Min Net R:R | `min_net_rr` | 1.15 | 0.8–2.0 |
| Target Net R:R | `target_net_rr` | 1.25 | 1.0–2.5 |
| RVOL breakout | `rvol_breakout_min` | 1.5 | 1.0–2.5 |
| RVOL setup | `rvol_setup_min` | 1.2 | 0.8–2.0 |
| RVOL retest | `rvol_retest_min` | 1.5 | 1.0–2.5 |
| CHOP hard ranging | `chop_hard_ranging` | 65 | 55–75 |
| Jarak max retest % | `retest_max_dist_pct` | 1.2 | 0.5–2.5 |
| Strength struktur retest | `retest_min_structure_strength` | 60 | 40–80 |

Advanced (opsional): `buy_pressure_*`, `min_risk_pct`, `max_tp2_r`, `slippage_pct`, exit paper params.

**Bukan slider:** fee all-in, bobot skor internal (structure 25, dll.).

---

## 5. Temuan empiris (jangan diabaikan)

- Tokocrypto v2 (up) / v3 (down) + fee all-in: exit full mengatur loss, **bukan** +EV stabil.
- Matrix `min_score` / `min_net_rr`: bottleneck utama di **entry** (s1/s3), bukan parameter risk saja.
- Binance vision ~45 hari (XRP/SOL/BTC): full exit tetap −EV orde besar; BTC sering 0 sinyal.
- 5 hipotesis entry baru di 45d: tidak lolos gate gross ≥1% train+test.
- Upstream p0 friction ~0,72% RT vs all-in riset ~0,97% → UI “VIABLE” bisa terlalu optimis.
- **P3 — 60 hari Tokocrypto (12 Agu – 11 Okt 2026), 8 pair, 8 hipotesis entry: 0/8 lolos gate.**
  Gross mean TEST terbaik hanya +0,03% (ambang 1%); semua NET −0,94%..−1,05%, yaitu ≈ friksi
  round-trip 0,97%. Artinya pada periode ini **timing entry tidak menyumbang apa pun** — rugi
  bersih praktis seluruhnya berasal dari biaya. MFE rata-rata hanya +0,33%..+0,58% dalam 1 jam,
  jadi bahkan gerakan 1% pun jarang tersedia untuk diambil.
  Satu hipotesis (`sweep reclaim`) **signifikan di train (p=0,002) lalu hilang di test (p=0,35)** —
  contoh langsung kenapa gate wajib diuji di dua periode, bukan satu.

Detail: `SCALP_EXIT.md`, `data/hasil/p3_entry_hypothesis_lab.md`, artefak lokal uji (bila ada).

---

## 6. Batasan exchange (dari AGENTS — wajib)

- Tokocrypto: **USDT + IDR** saja. Indodax: **IDR** saja.
- Dilarang request ke `binance.com` / `stream.binance.com` (diblok di ID).
- Cache/Room/prefs: partisi per `exchange`.
- Quote BIDR dieliminasi (kecuali nama koin seperti BNBIDR).

---

## 7. Dokumen terkait

| File | Peran |
|------|--------|
| **workplan2.md** (ini) | Workplan aktif |
| AGENTS.md | Aturan agen, build, batasan |
| APP_CONTEXT.md | Arsitektur + § arah produk terkini |
| SCALP_EXIT.md | Exit paper / R net |
| data/config/scalp_config.json | Mirror threshold + fee untuk replay |
| tools/scalp_replay.py | Offline paritas engine |
| tools/entry_hypothesis_lab.py | Lab hipotesis entry (P3) — offline, berlabel HIPOTESIS, tidak menyentuh config live |
| tools/selftest_entry_lab.py | Uji daya-deteksi lab (data sintetis ber-edge tanam) |
| data/hasil/p3_entry_hypothesis_lab.md | Hasil P3: 0/8 hipotesis lolos gate |

**Jangan baca sebagai instruksi aktif:** `workplan.md`, `checkpoint.md`, `checkpoint_progres.md` (legacy checklist tahap lama).

---

## 8. Definition of Done (milestone berikutnya)

### Milestone P0 & P1 (Selesai):
- [x] RuntimeConfig + minimal 1 layar slider + export JSON
- [x] Paper trade memakai RuntimeConfig + log threshold
- [x] Fee Truth tersambung di app + JSON replay
- [x] AGENTS/APP_CONTEXT menunjuk workplan2 saja untuk arah

### Milestone P2 (Target Aktif):
- [x] Toggle Auto-Buy Paper Trade (eksekusi order beli simulasi otomatis saat transisi sinyal scalping terkonfirmasi READY/STRONG_ENTRY)
- [x] Engine Auto-Exit Paper Trade 5 mode (`classic`, `tp1_be`, `early`, `trail`, `full`) selaras `SCALP_EXIT.md`
- [x] Parameter exit tersambung ke `ScalpingRuntimeConfig` + ekspor JSON (blok `exit`) untuk paritas replay
- [x] Statistik performa paper trade (R net terakumulasi setelah fee all-in) — mesin `PaperTradeStats` + unit test
- [x] FSM visual + kartu statistik di UI layar paper trade (`PaperExitFsmCard` + `PaperTradeStatsCard`),
      hasil per trade dipersistensikan di `PaperTradeJournalStore`
